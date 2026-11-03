using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace PhoneMic.Audio;

/// <summary>
/// Receives one UDP datagram at a time on a dedicated background thread.
/// Stage 1 feeds complete packets straight to the audio buffer; packet
/// reordering and loss concealment are added in stage 2.
/// </summary>
internal sealed class UdpAudioReceiver : IDisposable
{
    private readonly UdpClient _udpClient;
    private readonly Thread _receiveThread;
    private readonly object _disposeLock = new();
    private volatile bool _running;
    private bool _disposed;

    public UdpAudioReceiver()
    {
        // Binds to all IPv4 adapters. A second copy of Phone Mic, or any other
        // process using the port, will make this constructor throw.
        _udpClient = new UdpClient(new IPEndPoint(IPAddress.Any, AudioProtocol.Port));

        try
        {
            // Absorb small Wi-Fi scheduling bursts without asking Windows to
            // drop UDP packets. Linux/Android send at roughly 100 packets/sec.
            _udpClient.Client.ReceiveBufferSize = 256 * 1024;
        }
        catch (SocketException)
        {
            // The requested size is a hint; Windows may cap it. Keep listening
            // with whatever the OS granted.
        }

        _receiveThread = new Thread(ReceiveLoop)
        {
            IsBackground = true,
            Name = "Phone Mic UDP receiver",
            Priority = ThreadPriority.AboveNormal,
        };
    }

    public event EventHandler<AudioPacketEventArgs>? PacketReceived;

    public event EventHandler<string>? ReceiveError;

    public void Start()
    {
        if (_running) return;
        _running = true;
        _receiveThread.Start();
    }

    private void ReceiveLoop()
    {
        var remoteEndpoint = new IPEndPoint(IPAddress.Any, 0);

        while (_running)
        {
            try
            {
                byte[] datagram = _udpClient.Receive(ref remoteEndpoint);
                if (datagram.Length != AudioProtocol.PacketBytes)
                {
                    // Malformed or unrelated UDP traffic is ignored rather than
                    // allowed to interrupt a working stream.
                    continue;
                }

                uint sequence = BinaryPrimitives.ReadUInt32BigEndian(datagram);
                var pcm = new byte[AudioProtocol.PcmBytesPerPacket];
                Buffer.BlockCopy(
                    datagram,
                    AudioProtocol.SequenceHeaderBytes,
                    pcm,
                    0,
                    AudioProtocol.PcmBytesPerPacket);

                try
                {
                    PacketReceived?.Invoke(
                        this,
                        new AudioPacketEventArgs(sequence, pcm));
                }
                catch (Exception ex)
                {
                    // A consumer failure must not kill the receive loop.
                    Debug.WriteLine($"Audio packet handler failed: {ex}");
                }
            }
            catch (SocketException ex) when (!_running)
            {
                Debug.WriteLine($"UDP receive stopped: {ex.SocketErrorCode}");
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                if (!_running) break;

                Debug.WriteLine($"UDP receive error: {ex}");
                ReceiveError?.Invoke(
                    this,
                    "A network error interrupted listening. Phone Mic is still trying.");
                Thread.Sleep(250);
            }
        }
    }

    public void Dispose()
    {
        lock (_disposeLock)
        {
            if (_disposed) return;
            _disposed = true;
        }

        _running = false;

        // Closing the socket unblocks Receive(). Joining ensures no packet
        // callback can touch the audio player after it has been disposed.
        try
        {
            _udpClient.Close();
        }
        catch (SocketException)
        {
            // Already closed.
        }

        if (_receiveThread.IsAlive)
        {
            _receiveThread.Join(TimeSpan.FromSeconds(1));
        }

        _udpClient.Dispose();
    }
}
