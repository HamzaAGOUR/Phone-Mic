namespace PhoneMic.Audio;

internal sealed class AudioPacketEventArgs : EventArgs
{
    public AudioPacketEventArgs(uint sequenceNumber, byte[] pcm)
    {
        SequenceNumber = sequenceNumber;
        Pcm = pcm;
    }

    public uint SequenceNumber { get; }

    /// <summary>960 bytes of mono, 16-bit PCM at 48 kHz.</summary>
    public byte[] Pcm { get; }
}
