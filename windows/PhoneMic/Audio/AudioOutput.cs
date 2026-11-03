using System.Diagnostics;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace PhoneMic.Audio;

/// <summary>
/// A render endpoint shown in the Phone Mic output dropdown.
/// </summary>
internal sealed record AudioDevice(string Id, string Name)
{
    /// <summary>
    /// VB-Cable's playback endpoint is called "CABLE Input". Its recording
    /// endpoint, "CABLE Output", is what Discord/Zoom/OBS should select.
    /// Some VB-Cable installs also expose a "CABLE In 16ch" endpoint, which is
    /// not the normal stereo microphone path.
    /// </summary>
    public bool IsVbCableInput => VbCablePriority > 0;

    public bool IsVbCableRelated =>
        Name.Contains("CABLE", StringComparison.OrdinalIgnoreCase) ||
        Name.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase);

    public int VbCablePriority
    {
        get
        {
            if (Name.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase))
            {
                return 2;
            }

            if (Name.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase) &&
                !Name.Contains("CABLE Output", StringComparison.OrdinalIgnoreCase) &&
                !Name.Contains("CABLE In 16ch", StringComparison.OrdinalIgnoreCase))
            {
                return 1;
            }

            return 0;
        }
    }

    public override string ToString() => Name;
}

/// <summary>
/// Owns the NAudio output device and the shared jitter buffer.
/// The jitter buffer survives device switches so received audio is not lost
/// while the user changes the output.
/// </summary>
internal sealed class AudioOutput : IDisposable
{
    private const int WasapiLatencyMs = 20;
    private const int WasapiFallbackLatencyMs = 40;

    private readonly object _gate = new();
    private readonly JitterBufferWaveProvider _jitterBuffer = new();
    private readonly MMDeviceEnumerator _deviceEnumerator;

    private IWavePlayer? _output;
    private MMDevice? _currentDevice;
    private bool _disposed;

    public AudioOutput()
    {
        _deviceEnumerator = new MMDeviceEnumerator();
    }

    /// <summary>
    /// Raised on an audio thread when Windows reports an output failure.
    /// </summary>
    public event EventHandler<string>? OutputError;

    public string? ActiveDeviceId { get; private set; }

    public bool ActiveUseDefaultSpeakers { get; private set; }

    public IReadOnlyList<AudioDevice> GetDevices()
    {
        try
        {
            return EnumerateDevices();
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Audio device enumeration failed: {ex}");
            throw new AudioOutputException(
                "Could not list audio output devices. Check that Windows audio is working, then click Refresh.",
                ex);
        }
    }

    public AudioDevice? GetVbCableInput()
    {
        return GetDevices().FirstOrDefault(device => device.IsVbCableInput);
    }

    /// <summary>
    /// Returns the normal Windows speaker endpoint. If the user has made
    /// CABLE Input the default playback device, a real speaker is preferred for
    /// the "Test with speakers" option.
    /// </summary>
    public AudioDevice? GetDefaultSpeaker()
    {
        try
        {
            using MMDevice defaultDevice = _deviceEnumerator.GetDefaultAudioEndpoint(
                DataFlow.Render,
                Role.Multimedia);

            var defaultInfo = new AudioDevice(defaultDevice.ID, defaultDevice.FriendlyName);
            if (!defaultInfo.IsVbCableRelated)
            {
                return defaultInfo;
            }

            return GetDevices().FirstOrDefault(device => !device.IsVbCableRelated);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not find the default speaker: {ex}");
            return null;
        }
    }

    /// <summary>
    /// Starts (or restarts) playback on the requested device.
    /// Pass <paramref name="useDefaultSpeakers"/> for the test-with-speakers
    /// checkbox; otherwise pass the dropdown's device id.
    /// </summary>
    public void Start(string? deviceId, bool useDefaultSpeakers)
    {
        lock (_gate)
        {
            if (_disposed) return;

            AudioDevice target;
            try
            {
                if (useDefaultSpeakers)
                {
                    target = GetDefaultSpeaker() ?? throw new AudioOutputException(
                        "No speakers were found. Connect speakers or headphones, then click Refresh.");
                }
                else if (!string.IsNullOrWhiteSpace(deviceId))
                {
                    target = GetDevices().FirstOrDefault(device => device.Id == deviceId)
                        ?? throw new AudioOutputException(
                            "The selected audio output is no longer available. Click Refresh and choose another device.");
                }
                else
                {
                    target = GetDefaultSpeaker() ?? throw new AudioOutputException(
                        "No audio output was found. Connect speakers or headphones, then click Refresh.");
                }
            }
            catch (AudioOutputException)
            {
                throw;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Could not resolve audio output: {ex}");
                throw new AudioOutputException(
                    "Could not open the selected audio output. Check your sound settings and try again.",
                    ex);
            }

            // Nothing to do if this exact device and mode are already active.
            if (_output != null &&
                ActiveDeviceId == target.Id &&
                ActiveUseDefaultSpeakers == useDefaultSpeakers)
            {
                return;
            }

            MMDevice? newDevice = null;
            IWavePlayer? newOutput = null;

            // Initialize the new device before touching the current output. If
            // this fails, the user keeps whatever audio path was already working.
            try
            {
                newDevice = FindDeviceById(target.Id)
                    ?? throw new AudioOutputException(
                        "The selected audio output is no longer available. Click Refresh and choose another device.");

                bool allowWaveOutFallback = IsDefaultAudioEndpoint(target.Id);
                newOutput = CreateInitializedOutput(newDevice, target.Name, allowWaveOutFallback);
            }
            catch (AudioOutputException)
            {
                newOutput?.Dispose();
                newDevice?.Dispose();
                throw;
            }
            catch (Exception ex)
            {
                newOutput?.Dispose();
                newDevice?.Dispose();
                Debug.WriteLine($"Could not prepare audio output: {ex}");
                throw new AudioOutputException(
                    $"Could not start audio output on \"{target.Name}\". Try another output device or reconnect it.",
                    ex);
            }

            // The new output is ready. Replace the old one.
            StopOutputLocked();

            _currentDevice = newDevice;
            _output = newOutput;
            ActiveDeviceId = target.Id;
            ActiveUseDefaultSpeakers = useDefaultSpeakers;
            _output.PlaybackStopped += OnPlaybackStopped;

            newDevice = null;
            newOutput = null;

            try
            {
                _output.Play();
            }
            catch (Exception ex)
            {
                CleanupFailedStart();
                Debug.WriteLine($"Could not start audio output: {ex}");
                throw new AudioOutputException(
                    $"Could not start audio output on \"{target.Name}\". Try another output device or reconnect it.",
                    ex);
            }
        }
    }

    public void AddPacket(uint sequenceNumber, byte[] pcm)
    {
        _jitterBuffer.AddPacket(sequenceNumber, pcm);
    }

    private void CleanupFailedStart()
    {
        if (_output != null)
        {
            _output.PlaybackStopped -= OnPlaybackStopped;
            try
            {
                _output.Stop();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Stopping failed audio output error: {ex}");
            }

            try
            {
                _output.Dispose();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Disposing failed audio output error: {ex}");
            }

            _output = null;
        }

        _currentDevice?.Dispose();
        _currentDevice = null;
        ActiveDeviceId = null;
        ActiveUseDefaultSpeakers = false;
    }

    private List<AudioDevice> EnumerateDevices()
    {
        var devices = new List<AudioDevice>();

        MMDeviceCollection collection = _deviceEnumerator.EnumerateAudioEndPoints(
            DataFlow.Render,
            DeviceState.Active);

        foreach (MMDevice device in collection)
        {
            try
            {
                devices.Add(new AudioDevice(device.ID, device.FriendlyName));
            }
            finally
            {
                device.Dispose();
            }
        }

        return devices;
    }

    private MMDevice? FindDeviceById(string id)
    {
        MMDeviceCollection collection = _deviceEnumerator.EnumerateAudioEndPoints(
            DataFlow.Render,
            DeviceState.Active);

        foreach (MMDevice device in collection)
        {
            if (device.ID == id)
            {
                // Ownership moves to the caller.
                return device;
            }

            device.Dispose();
        }

        return null;
    }

    private bool IsDefaultAudioEndpoint(string id)
    {
        try
        {
            using MMDevice defaultDevice = _deviceEnumerator.GetDefaultAudioEndpoint(
                DataFlow.Render,
                Role.Multimedia);
            return defaultDevice.ID == id;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not check the default audio endpoint: {ex}");
            return false;
        }
    }

    private IWavePlayer CreateInitializedOutput(
        MMDevice device,
        string deviceName,
        bool allowWaveOutFallback)
    {
        Exception? firstError = null;

        try
        {
            return CreateWasapi(device, useEventSync: true, WasapiLatencyMs);
        }
        catch (Exception ex)
        {
            firstError = ex;
            Debug.WriteLine($"WASAPI event-driven output failed: {ex}");
        }

        try
        {
            return CreateWasapi(device, useEventSync: false, WasapiFallbackLatencyMs);
        }
        catch (Exception ex)
        {
            firstError = new AggregateException(firstError!, ex);
            Debug.WriteLine($"WASAPI output failed: {ex}");
        }

        if (allowWaveOutFallback)
        {
            try
            {
                return CreateWaveOut();
            }
            catch (Exception ex)
            {
                firstError = new AggregateException(firstError!, ex);
                Debug.WriteLine($"WaveOut fallback failed: {ex}");
            }
        }

        throw new AudioOutputException(
            $"Could not start audio output on \"{deviceName}\". Try another output device or reconnect it.",
            firstError!);
    }

    private IWavePlayer CreateWasapi(MMDevice device, bool useEventSync, int latencyMs)
    {
        var output = new WasapiOut(
            device,
            AudioClientShareMode.Shared,
            useEventSync,
            latencyMs);

        try
        {
            output.Init(_jitterBuffer);
            return output;
        }
        catch
        {
            output.Dispose();
            throw;
        }
    }

    private IWavePlayer CreateWaveOut()
    {
        var output = new WaveOutEvent
        {
            DesiredLatency = 60,
            NumberOfBuffers = 3,
        };

        try
        {
            output.Init(_jitterBuffer);
            return output;
        }
        catch
        {
            output.Dispose();
            throw;
        }
    }

    private void OnPlaybackStopped(object? sender, StoppedEventArgs e)
    {
        if (e.Exception == null) return;
        if (!ReferenceEquals(sender, _output)) return;

        Debug.WriteLine($"Audio playback stopped: {e.Exception}");
        OutputError?.Invoke(
            this,
            "Audio output stopped. Check that your speakers or VB-Cable are still connected, then click Refresh.");
    }

    private void StopOutputLocked()
    {
        if (_output != null)
        {
            _output.PlaybackStopped -= OnPlaybackStopped;
            try
            {
                _output.Stop();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Stopping audio output failed: {ex}");
            }

            try
            {
                _output.Dispose();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Disposing audio output failed: {ex}");
            }

            _output = null;
        }

        _currentDevice?.Dispose();
        _currentDevice = null;
        ActiveDeviceId = null;
        ActiveUseDefaultSpeakers = false;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;

            StopOutputLocked();
            _deviceEnumerator.Dispose();
        }
    }
}

internal sealed class AudioOutputException : Exception
{
    public AudioOutputException(string message)
        : base(message)
    {
    }

    public AudioOutputException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}
