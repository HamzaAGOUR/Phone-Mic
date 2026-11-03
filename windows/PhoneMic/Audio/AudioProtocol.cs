namespace PhoneMic.Audio;

/// <summary>
/// Wire format shared with the Android sender.
/// 4-byte big-endian sequence number followed by 960 bytes of
/// 48 kHz, mono, 16-bit little-endian PCM.
/// </summary>
internal static class AudioProtocol
{
    public const int Port = 48123;
    public const int SampleRate = 48_000;
    public const int Channels = 1;
    public const int BitsPerSample = 16;
    public const int SamplesPerPacket = 480;
    public const int PcmBytesPerPacket = 960;
    public const int SequenceHeaderBytes = 4;
    public const int PacketBytes = SequenceHeaderBytes + PcmBytesPerPacket;

    /// <summary>Length of one packet in time (480 samples at 48 kHz).</summary>
    public static readonly TimeSpan PacketDuration = TimeSpan.FromMilliseconds(10);
}
