using System.Buffers.Binary;
using System.ComponentModel;
using System.Diagnostics;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Navigation;
using System.Windows.Threading;
using PhoneMic.Audio;
using PhoneMic.Networking;

namespace PhoneMic;

public partial class MainWindow : Window
{
    private static readonly Brush NeutralBrush =
        new SolidColorBrush(Color.FromRgb(100, 116, 139));

    private static readonly Brush ReceivingBrush =
        new SolidColorBrush(Color.FromRgb(21, 128, 61));

    private static readonly Brush ErrorBrush =
        new SolidColorBrush(Color.FromRgb(185, 28, 28));

    private const int SilenceHintDelayMs = 3000;
    private const int AudibleLevelPercent = 2;

    private readonly DispatcherTimer _statusTimer;

    private UdpAudioReceiver? _receiver;
    private AudioOutput? _audio;
    private IReadOnlyList<AudioDevice> _devices = Array.Empty<AudioDevice>();
    private long _lastPacketTickCount;
    private int _latestMicLevelPercent;
    private double _displayedMicLevel;
    private long _silentSinceTickCount;
    private string? _fatalMessage;
    private volatile string? _receiverWarning;
    private bool _closing;
    private bool _updatingDeviceList;

    public MainWindow()
    {
        InitializeComponent();

        // Keep the window inside the usable screen area on high-DPI laptops.
        double maxWindowHeight = SystemParameters.WorkArea.Height - 24;
        if (maxWindowHeight > 360 && Height > maxWindowHeight)
        {
            Height = maxWindowHeight;
        }

        _statusTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(100),
        };
        _statusTimer.Tick += (_, _) => UpdateConnectionStatus();

        Loaded += OnLoaded;
        Closing += OnClosing;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;

        string? ipAddress = NetworkAddress.GetPreferredLocalIPv4();
        IpAddressText.Text = ipAddress ?? "Not connected";

        if (ipAddress == null)
        {
            ShowFatal(
                "No network connection found. Connect this PC to Wi-Fi or Ethernet, then restart Phone Mic.");
            return;
        }

        try
        {
            _audio = new AudioOutput();
            _audio.OutputError += OnAudioOutputError;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not initialize Windows audio: {ex}");
            ShowOutputError(
                "Could not initialize Windows audio. Check your sound settings, then restart Phone Mic.");
        }

        RefreshDevices(initial: true);

        try
        {
            _receiver = new UdpAudioReceiver();
            _receiver.PacketReceived += OnPacketReceived;
            _receiver.ReceiveError += OnReceiverError;
            _receiver.Start();
        }
        catch (SocketException ex)
        {
            Debug.WriteLine($"Could not bind UDP port {AudioProtocol.Port}: {ex}");
            ShowFatal(
                $"Could not listen on UDP port {AudioProtocol.Port}. Close any other copy of Phone Mic and restart.");
            DisposeReceiver();
            DisposeAudio();
            return;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not start the UDP receiver: {ex}");
            ShowFatal("Could not start the network listener. Please restart Phone Mic.");
            DisposeReceiver();
            DisposeAudio();
            return;
        }

        SetStatus("Waiting for phone...", NeutralBrush);
        _statusTimer.Start();
    }

    /// <summary>
    /// Refreshes the render-device list and picks CABLE Input when it exists.
    /// Otherwise it falls back to the normal Windows speakers.
    /// </summary>
    private void RefreshDevices(bool initial)
    {
        if (_audio == null)
        {
            VbCableWarningPanel.Visibility = Visibility.Visible;
            ShowOutputError(
                "Windows audio is not available. Restart Phone Mic after checking your sound settings.");
            return;
        }

        string? previousId = (OutputDeviceComboBox.SelectedItem as AudioDevice)?.Id;

        IReadOnlyList<AudioDevice> devices;
        AudioDevice? defaultSpeaker;

        try
        {
            devices = _audio.GetDevices();
            defaultSpeaker = _audio.GetDefaultSpeaker();
        }
        catch (AudioOutputException ex)
        {
            Debug.WriteLine($"Could not list audio outputs: {ex}");
            ShowOutputError(ex.Message);
            return;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Unexpected audio device error: {ex}");
            ShowOutputError(
                "Could not list audio output devices. Check your sound settings, then click Refresh.");
            return;
        }

        _devices = devices;
        _updatingDeviceList = true;

        try
        {
            OutputDeviceComboBox.ItemsSource = _devices;

            AudioDevice? selected = null;

            // Keep the user's current choice if it still exists. On first load,
            // prefer CABLE Input automatically.
            if (!initial && previousId != null)
            {
                selected = _devices.FirstOrDefault(device => device.Id == previousId);
            }

            // Prefer the standard "CABLE Input" endpoint. Some installs also
            // expose "CABLE In 16ch", which is not the normal microphone path.
            selected ??= _devices
                .Where(device => device.IsVbCableInput)
                .OrderByDescending(device => device.VbCablePriority)
                .FirstOrDefault();

            if (selected == null && defaultSpeaker != null)
            {
                selected = _devices.FirstOrDefault(device => device.Id == defaultSpeaker.Id);
            }

            selected ??= _devices.FirstOrDefault();

            OutputDeviceComboBox.SelectedItem = selected;

            bool vbCableFound = _devices.Any(device => device.IsVbCableInput);
            VbCableWarningPanel.Visibility = vbCableFound
                ? Visibility.Collapsed
                : Visibility.Visible;

            OutputDeviceComboBox.IsEnabled =
                !TestWithSpeakersCheckBox.IsChecked.GetValueOrDefault();
        }
        finally
        {
            _updatingDeviceList = false;
        }

        if (_devices.Count == 0)
        {
            ShowOutputError(
                "No audio output devices were found. Connect speakers or install VB-Cable, then click Refresh.");
            return;
        }

        ApplyOutputSelection();
    }

    private void ApplyOutputSelection()
    {
        if (_audio == null) return;

        bool useSpeakers = TestWithSpeakersCheckBox.IsChecked.GetValueOrDefault();
        string? deviceId = useSpeakers
            ? null
            : (OutputDeviceComboBox.SelectedItem as AudioDevice)?.Id;

        try
        {
            _audio.Start(deviceId, useSpeakers);
            ClearOutputError();
        }
        catch (AudioOutputException ex)
        {
            Debug.WriteLine($"Could not start audio output: {ex}");
            ShowOutputError(ex.Message);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Unexpected audio output error: {ex}");
            ShowOutputError(
                "Could not start audio output. Check your sound settings, then try again.");
        }
    }

    private void OnPacketReceived(object? sender, AudioPacketEventArgs e)
    {
        Interlocked.Exchange(ref _lastPacketTickCount, Environment.TickCount64);
        _receiverWarning = null;

        // Measure the raw PCM before it reaches the jitter buffer. If this
        // stays at zero while packets arrive, the phone is capturing silence.
        Volatile.Write(ref _latestMicLevelPercent, CalculateMicLevelPercent(e.Pcm));

        _audio?.AddPacket(e.SequenceNumber, e.Pcm);
    }

    private void OnReceiverError(object? sender, string message)
    {
        _receiverWarning = message;
        try
        {
            Dispatcher.BeginInvoke(() =>
            {
                if (!_closing) UpdateConnectionStatus();
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not marshal receiver error: {ex}");
        }
    }

    private void OnAudioOutputError(object? sender, string message)
    {
        try
        {
            Dispatcher.BeginInvoke(() =>
            {
                if (_closing) return;
                ShowOutputError(message);
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not marshal audio output error: {ex}");
        }
    }

    private void UpdateConnectionStatus()
    {
        if (_closing || _fatalMessage != null) return;

        long lastPacketTickCount = Interlocked.Read(ref _lastPacketTickCount);
        long packetAgeMs = lastPacketTickCount == 0
            ? long.MaxValue
            : Environment.TickCount64 - lastPacketTickCount;

        bool receiving = packetAgeMs is >= 0 and <= 500;

        UpdateMicLevel(receiving);

        if (!receiving && _receiverWarning != null)
        {
            SetStatus(_receiverWarning, ErrorBrush);
            return;
        }

        if (receiving)
        {
            SetStatus("Receiving", ReceivingBrush);
        }
        else
        {
            SetStatus("Waiting for phone...", NeutralBrush);
        }
    }

    /// <summary>
    /// Converts one 10 ms PCM packet to a 0-100 meter value on a -60..0 dBFS
    /// scale. Digital silence is exactly zero, which distinguishes a muted
    /// phone microphone from very quiet sound.
    /// </summary>
    private static int CalculateMicLevelPercent(byte[] pcm)
    {
        int sampleCount = pcm.Length / 2;
        if (sampleCount == 0) return 0;

        double sumSquares = 0;
        for (int i = 0; i + 1 < pcm.Length; i += 2)
        {
            short sample = BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(i, 2));
            sumSquares += (double)sample * sample;
        }

        double rms = Math.Sqrt(sumSquares / sampleCount);
        if (rms < 1) return 0;

        double decibels = 20 * Math.Log10(rms / 32768d);
        double percent = (decibels + 60d) / 60d * 100d;
        return Math.Clamp((int)Math.Round(percent), 0, 100);
    }

    private void UpdateMicLevel(bool receiving)
    {
        int target = receiving ? Volatile.Read(ref _latestMicLevelPercent) : 0;

        // Fast attack and slow release keep short speech peaks readable.
        _displayedMicLevel = target >= _displayedMicLevel
            ? target
            : Math.Max(0, _displayedMicLevel - 6);

        double trackWidth = MicLevelTrack.ActualWidth;
        MicLevelFill.Width = Math.Max(0, trackWidth * (_displayedMicLevel / 100d));
        MicLevelText.Text = $"{Math.Round(_displayedMicLevel):0}%";

        long now = Environment.TickCount64;

        // Warn only after three seconds of exact digital silence. Real
        // microphone noise normally keeps the meter above zero.
        if (receiving && target < AudibleLevelPercent)
        {
            if (_silentSinceTickCount == 0) _silentSinceTickCount = now;
        }
        else
        {
            _silentSinceTickCount = 0;
        }

        bool showSilenceHint = _silentSinceTickCount != 0 &&
            now - _silentSinceTickCount >= SilenceHintDelayMs;

        SilentMicHint.Visibility = showSilenceHint
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void OnOutputDeviceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updatingDeviceList) return;
        if (TestWithSpeakersCheckBox.IsChecked.GetValueOrDefault()) return;

        ApplyOutputSelection();
    }

    private void OnTestWithSpeakersChanged(object sender, RoutedEventArgs e)
    {
        if (_updatingDeviceList) return;

        bool useSpeakers = TestWithSpeakersCheckBox.IsChecked.GetValueOrDefault();
        OutputDeviceComboBox.IsEnabled = !useSpeakers;
        ApplyOutputSelection();
    }

    private void OnRefreshDevicesClicked(object sender, RoutedEventArgs e)
    {
        RefreshDevices(initial: false);
    }

    private void OnGetVbCableClicked(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("https://vb-audio.com/Cable/")
            {
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not open the VB-Cable page: {ex}");
            ShowOutputError(
                "Could not open the browser. Visit https://vb-audio.com/Cable/ manually.");
        }
    }

    private void OnRepositoryLinkClicked(object sender, RequestNavigateEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri)
            {
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not open the repository link: {ex}");
            ShowOutputError(
                "Could not open the browser. Visit https://github.com/HamzaAGOUR/Phone-Mic manually.");
        }

        e.Handled = true;
    }

    private void ShowFatal(string message)
    {
        _fatalMessage = message;
        _statusTimer.Stop();
        SetStatus(message, ErrorBrush);
    }

    private void ShowOutputError(string message)
    {
        OutputMessageText.Text = message;
        OutputMessageText.Visibility = Visibility.Visible;
    }

    private void ClearOutputError()
    {
        OutputMessageText.Text = string.Empty;
        OutputMessageText.Visibility = Visibility.Collapsed;
    }

    private void SetStatus(string message, Brush brush)
    {
        StatusText.Text = message;
        StatusText.Foreground = brush;
        StatusDot.Fill = brush;
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        _closing = true;
        _statusTimer.Stop();
        DisposeReceiver();
        DisposeAudio();
    }

    private void DisposeReceiver()
    {
        if (_receiver == null) return;

        _receiver.PacketReceived -= OnPacketReceived;
        _receiver.ReceiveError -= OnReceiverError;
        _receiver.Dispose();
        _receiver = null;
    }

    private void DisposeAudio()
    {
        if (_audio == null) return;

        _audio.OutputError -= OnAudioOutputError;
        _audio.Dispose();
        _audio = null;
    }
}
