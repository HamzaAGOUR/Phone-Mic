using NAudio.Wave;

namespace PhoneMic.Audio;

/// <summary>
/// Turns the UDP packet stream into a continuous 48 kHz mono PCM stream.
///
/// Packets are held for a small prebuffer (about 40 ms), then played in
/// sequence order. Missing packets are replaced with 10 ms of silence so the
/// audio timeline never jumps. The provider always returns the requested
/// number of bytes, which keeps the Windows output device running (and the
/// virtual microphone alive) even while the phone is not sending.
/// </summary>
internal sealed class JitterBufferWaveProvider : IWaveProvider
{
    // 4 packets = 40 ms of prebuffer. This hides normal Wi-Fi jitter without
    // pushing the end-to-end latency past the 100 ms target.
    private const int PrebufferPackets = 4;

    // If packets arrive unusually slowly, start anyway rather than staying
    // silent forever. In practice the packet count is reached first.
    private const int PrebufferTimeoutMs = 60;

    // Keep at most 100 ms queued. Anything beyond that is stale audio and is
    // dropped so latency cannot grow over time.
    private const int MaxBufferedPackets = 10;

    // After this much silence, forget the old sequence position. This lets the
    // phone app restart, or reconnect after a long network outage, cleanly.
    private const int IdleResetMs = 500;

    // A sequence jump over one second is treated as a new stream rather than
    // emitting a full second of silence.
    private const int LargeJumpPackets = 100;

    private static readonly byte[] SilenceFrame = new byte[AudioProtocol.PcmBytesPerPacket];

    private readonly object _gate = new();
    private readonly Dictionary<uint, byte[]> _packets = new();

    private uint _nextSequence;
    private bool _hasSequence;
    private bool _started;
    private long _firstBufferedAtMs;
    private long _lastPacketAtMs;
    private byte[]? _currentFrame;
    private int _currentFrameOffset;

    public WaveFormat WaveFormat { get; } = new(
        AudioProtocol.SampleRate,
        AudioProtocol.BitsPerSample,
        AudioProtocol.Channels);

    /// <summary>
    /// Adds one 10 ms packet. Called from the UDP receive thread.
    /// </summary>
    public void AddPacket(uint sequenceNumber, byte[] pcm)
    {
        if (pcm.Length != AudioProtocol.PcmBytesPerPacket) return;

        long now = Environment.TickCount64;

        lock (_gate)
        {
            if (_hasSequence && now - _lastPacketAtMs > IdleResetMs)
            {
                ResetLocked();
            }

            if (!_hasSequence)
            {
                StartNewStreamLocked(sequenceNumber, now);
            }
            else
            {
                int difference = unchecked((int)(sequenceNumber - _nextSequence));

                if (difference < 0)
                {
                    if (difference < -LargeJumpPackets)
                    {
                        // The phone app restarted and its sequence counter began
                        // again at zero. Accept the new stream instead of
                        // dropping it as a very late packet.
                        ResetLocked();
                        StartNewStreamLocked(sequenceNumber, now);
                    }
                    else
                    {
                        // Late or duplicate packet. It can no longer be played
                        // without breaking the 10 ms timeline.
                        return;
                    }
                }
                else if (difference > LargeJumpPackets && _packets.Count == 0 && _started)
                {
                    // A large forward jump with nothing queued means we missed a
                    // long stretch. Resynchronise instead of waiting for many
                    // seconds of missing sequence numbers.
                    ResetLocked();
                    StartNewStreamLocked(sequenceNumber, now);
                }
            }

            _lastPacketAtMs = now;
            _packets[sequenceNumber] = pcm;

            if (!_started &&
                (_packets.Count >= PrebufferPackets ||
                 now - _firstBufferedAtMs >= PrebufferTimeoutMs))
            {
                _started = true;
            }

            TrimExcessLocked();
        }
    }

    /// <summary>
    /// Called by NAudio's output thread. Always fills the whole buffer.
    /// </summary>
    public int Read(byte[] buffer, int offset, int count)
    {
        if (count <= 0) return 0;

        lock (_gate)
        {
            long now = Environment.TickCount64;

            if (_hasSequence && now - _lastPacketAtMs > IdleResetMs)
            {
                ResetLocked();
            }

            if (!_started &&
                _hasSequence &&
                (_packets.Count >= PrebufferPackets ||
                 now - _firstBufferedAtMs >= PrebufferTimeoutMs))
            {
                _started = true;
            }

            int written = 0;
            while (written < count)
            {
                if (_currentFrame == null ||
                    _currentFrameOffset >= _currentFrame.Length)
                {
                    _currentFrame = GetNextFrameLocked();
                    _currentFrameOffset = 0;
                }

                int toCopy = Math.Min(count - written, _currentFrame.Length - _currentFrameOffset);
                Buffer.BlockCopy(
                    _currentFrame,
                    _currentFrameOffset,
                    buffer,
                    offset + written,
                    toCopy);

                _currentFrameOffset += toCopy;
                written += toCopy;
            }

            return count;
        }
    }

    private byte[] GetNextFrameLocked()
    {
        if (!_started || !_hasSequence)
        {
            return SilenceFrame;
        }

        if (_packets.Remove(_nextSequence, out byte[]? packet))
        {
            _nextSequence = unchecked(_nextSequence + 1);
            return packet;
        }

        // The packet we expected is missing. If a later packet is already here,
        // insert exactly one 10 ms silence frame so the timeline stays intact.
        if (TryFindEarliestAfterLocked(out uint earliest, out int gap))
        {
            if (gap > MaxBufferedPackets)
            {
                // A long gap: jump straight to the packet we have instead of
                // emitting a long run of silence.
                _packets.Remove(earliest, out packet);
                _nextSequence = unchecked(earliest + 1);
                return packet ?? SilenceFrame;
            }

            _nextSequence = unchecked(_nextSequence + 1);
            return SilenceFrame;
        }

        // Nothing queued. Do not advance the sequence: if a slightly late
        // packet arrives now, it can still be played.
        return SilenceFrame;
    }

    private bool TryFindEarliestAfterLocked(out uint earliest, out int gap)
    {
        earliest = 0;
        gap = int.MaxValue;
        bool found = false;

        foreach (uint sequence in _packets.Keys)
        {
            int difference = unchecked((int)(sequence - _nextSequence));
            if (difference > 0 && difference < gap)
            {
                gap = difference;
                earliest = sequence;
                found = true;
            }
        }

        return found;
    }

    private void TrimExcessLocked()
    {
        while (_packets.Count > MaxBufferedPackets)
        {
            uint oldest = 0;
            int oldestDifference = int.MaxValue;

            foreach (uint sequence in _packets.Keys)
            {
                int difference = unchecked((int)(sequence - _nextSequence));
                if (difference < oldestDifference)
                {
                    oldestDifference = difference;
                    oldest = sequence;
                }
            }

            _packets.Remove(oldest);

            // Drop everything up to and including the packet we just discarded.
            // This moves playback toward the newest audio and keeps latency
            // bounded when the network delivers a burst.
            _nextSequence = unchecked(oldest + 1);
        }
    }

    private void StartNewStreamLocked(uint sequenceNumber, long now)
    {
        _hasSequence = true;
        _nextSequence = sequenceNumber;
        _lastPacketAtMs = now;
        _firstBufferedAtMs = now;
        _started = false;
    }

    private void ResetLocked()
    {
        _packets.Clear();
        _hasSequence = false;
        _started = false;
        _firstBufferedAtMs = 0;
        _lastPacketAtMs = 0;
        _currentFrame = null;
        _currentFrameOffset = 0;
    }
}
