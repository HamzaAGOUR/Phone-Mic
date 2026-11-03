# Phone Mic

Turn an Android phone into a microphone for a Windows PC over Wi-Fi.

<p align="center">
  <a href="https://github.com/HamzaAGOUR/Phone-Mic/releases/latest/download/PhoneMic-Android.apk">
    <img height="42" src="https://img.shields.io/badge/Download-Android%20APK-2563EB?style=for-the-badge&logo=android&logoColor=white" alt="Download the Android APK" />
  </a>
  &nbsp;
  <a href="https://github.com/HamzaAGOUR/Phone-Mic/releases/latest/download/PhoneMic-Windows.zip">
    <img height="42" src="https://img.shields.io/badge/Download-Windows%20app-1D4ED8?style=for-the-badge&logo=windows&logoColor=white" alt="Download the Windows app" />
  </a>
</p>

## Current build: Stage 3, PC microphone mode

This build can now be used as a microphone in Discord, Zoom, OBS, and other Windows apps that accept a microphone.

What works in this build:

- The Android app captures 48 kHz mono microphone audio and sends it over UDP.
- The Windows app receives the audio, smooths out Wi-Fi jitter with a 40 ms buffer, and fills short gaps with silence.
- The Windows app can play into **VB-Cable**, so other apps see **CABLE Output** as a real microphone.
- VB-Cable is detected automatically. The app selects **CABLE Input (VB-Audio Virtual Cable)** when it is installed.
- A **Test with speakers instead** checkbox lets you check the audio through your normal speakers.
- The PC window shows the address to enter on the phone and reports **Receiving** while audio is arriving.
- If VB-Cable is missing, the app shows a warning and a button that opens the VB-Cable download page.

The jitter buffer and VB-Cable routing are the two features that make this build usable as a PC microphone. Later stages add level meters, background streaming, reconnect, saved settings, a tray icon, and an automatic firewall rule.

## What you need

- A Windows 10 or Windows 11 PC.
- An Android 8.0 (API 26) or newer phone.
- Both devices on the **same Wi-Fi network**. A normal home Wi-Fi network works best. Guest networks and networks with "client isolation" often block phone-to-PC traffic.
- **VB-Cable** for microphone mode. It is free. The Windows app can open its download page for you.
- .NET 8 Desktop Runtime for Windows. It is already installed on the PC used to build this test build. On another PC, install it from <https://dotnet.microsoft.com/download/dotnet/8.0> and choose the **Desktop Runtime**.

## Project layout

```text
phone-mic/
  android/   Android Studio / Gradle app (Kotlin)
  windows/   Visual Studio / .NET 8 WPF app (C# + NAudio)
  README.md
```

## Ready-built files

The two buttons at the top download the latest built versions from [GitHub Releases](https://github.com/HamzaAGOUR/Phone-Mic/releases/latest):

- **Android APK:** `PhoneMic-Android.apk`
- **Windows app:** `PhoneMic-Windows.zip` (extract it, then run `PhoneMic.exe`)

If you build the project yourself, the outputs are:

- **Windows app:** `windows\publish\PhoneMic.exe` (keep the other files in that folder next to it)
- **Android APK:** `android\app\build\outputs\apk\debug\app-debug.apk`

If those have not been built yet, use the [build instructions](#build-from-source) below.

## Step 1: Install VB-Cable

VB-Cable is a free virtual audio driver. It creates two endpoints:

- **CABLE Input** is the playback device Phone Mic sends audio into.
- **CABLE Output** is the recording device Discord, Zoom, or OBS use as the microphone.

Install it once:

1. Open the **Get VB-Cable** button in Phone Mic, or go to <https://vb-audio.com/Cable/>.
2. Download the VB-Cable package and extract the ZIP file.
3. Right-click **VBCABLE_Setup_x64.exe** and choose **Run as administrator**.
4. Click **Install Driver**.
5. Restart the PC when the installer asks. A restart is the simplest way to make the new devices appear everywhere.

You do **not** need to make VB-Cable the default playback device. Phone Mic selects CABLE Input directly.

## Step 2: Start the Windows app

Double-click `PhoneMic.exe`.

- On the first launch, Windows Security may ask whether to allow **PhoneMic** on public and private networks. Choose **Allow**. Windows needs this so the phone can reach the PC.
- The app shows a **PC IP ADDRESS** such as `192.168.1.23` and **UDP port 48123**.
- The status starts as **Waiting for phone...**.
- If VB-Cable is installed, the output dropdown should show **CABLE Input (VB-Audio Virtual Cable)** and select it automatically.
- If the app says **VB-Cable not found**, install it using Step 1, then click **Refresh**.
- The **Test with speakers instead** checkbox is only for checking the audio on your normal speakers. Leave it off when using Discord, Zoom, or OBS.

If Windows does not show a firewall prompt and the phone cannot connect, see [No connection / stuck on "Waiting for phone..."](#no-connection--stuck-on-waiting-for-phone).

## Step 3: Install the Android app

Transfer `app-debug.apk` to the phone using a USB cable, Google Drive, or another file transfer method. Open the file on the phone and install it.

Android may ask you to allow installs from the app you used to open the APK. Allow it for that file manager/browser, install Phone Mic, then you can turn that permission off again.

With USB debugging enabled and `adb` available, you can also install it from this project folder:

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

## Step 4: Connect the phone

1. Open **Phone Mic** on the phone.
2. Type the IP address shown in the Windows app. For example: `192.168.1.23`.
3. Tap **Start streaming**.
4. The first time, Android asks for microphone permission. Phone Mic shows an explanation first. Choose **Continue**, then allow the microphone.
5. The phone should show **Streaming to ...** and the PC should show **Receiving**.

> **Keep the Phone Mic app open during this test.** This build runs the microphone inside the main screen. It stops when the app leaves the foreground. The screen is kept awake while streaming. Background streaming arrives in Stage 5.

## Step 5: Select the microphone in Discord, Zoom, or OBS

In the app where you want to talk, open its audio settings and choose:

```text
CABLE Output (VB-Audio Virtual Cable)
```

as the **microphone** / **input device**.

Examples:

- **Discord:** User Settings > Voice & Video > Input Device > **CABLE Output (VB-Audio Virtual Cable)**.
- **Zoom:** Settings > Audio > Microphone > **CABLE Output (VB-Audio Virtual Cable)**.
- **OBS:** Settings > Audio > Mic/Auxiliary Audio > **CABLE Output (VB-Audio Virtual Cable)**.

Important naming rule:

- In **Phone Mic**, select or leave **CABLE Input** selected.
- In **Discord/Zoom/OBS**, select **CABLE Output**.
- Do not select CABLE Output inside Phone Mic, and do not select CABLE Input inside Discord/Zoom/OBS. The two ends have similar names, so this is the most common mistake.

You can test the microphone inside Discord with its **Let's Check** microphone test, or inside Windows **Settings > System > Sound > Input** by watching the level for **CABLE Output**.

## Troubleshooting

### VB-Cable is not found

1. Make sure you installed the **64-bit** VB-Cable driver as administrator.
2. Restart the PC. The new audio endpoints usually do not appear reliably until after a restart.
3. Start Phone Mic again, then click **Refresh** in the output card.
4. If it still does not appear, open Windows **Settings > System > Sound > More sound settings** and check that **CABLE Input** and **CABLE Output** are listed and enabled.
5. If the devices are disabled, right-click them and choose **Enable**.

### No connection / stuck on "Waiting for phone..."

Work through these in order:

1. **Check the IP address.** Use the address displayed in the Windows app. The phone and PC must be on the same network. If the PC reconnected to Wi-Fi or the router restarted, the address may have changed; restart Phone Mic and enter the new one.
2. **Allow the Windows Firewall rule.** If you clicked Cancel or missed the first prompt, restart Phone Mic and choose **Allow**, or allow PhoneMic through the firewall:
   - Open **Windows Security**.
   - Go to **Firewall & network protection** > **Allow an app through firewall**.
   - Find **PhoneMic** or **Phone Mic** and allow it on **Private** networks.
   - If it is not listed, use the manual command below.
   - Stage 3 uses the normal Windows prompt, which may allow both public and private networks. Stage 6 will create a Private-only rule automatically.
3. **Check for the wrong adapter.** VPNs and virtual adapters can hide the real LAN address. Disconnect the VPN, restart Phone Mic, and use the newly displayed address.
4. **Check Wi-Fi isolation.** Guest Wi-Fi and some managed networks block phone-to-PC traffic. Try a normal home Wi-Fi network.
5. **Close extra copies.** Only one copy of Phone Mic can listen on UDP port 48123. Close other copies and restart the app.
6. **Check the subnet.** For example, if the PC is `192.168.1.23`, the phone should normally have an address such as `192.168.1.50`, not a mobile-data address.

Manual firewall command (for this stage only; Stage 6 will automate it). Open **Terminal/PowerShell as Administrator** and run:

```powershell
netsh advfirewall firewall add rule name="Phone Mic UDP 48123" dir=in action=allow protocol=UDP localport=48123 profile=private
```

### The PC says "Receiving", but Discord/Zoom/OBS hears nothing

1. Check that **CABLE Input (VB-Audio Virtual Cable)** is selected in the Phone Mic output dropdown.
2. Check that **CABLE Output (VB-Audio Virtual Cable)** is selected as the microphone in Discord/Zoom/OBS. These names are easy to mix up.
3. Open Windows **Settings > System > Sound > Volume mixer** and make sure Phone Mic is not muted and its volume is not at zero.
4. In Windows **Settings > System > Sound > More sound settings > Recording**, open **CABLE Output**, go to **Levels**, and make sure it is not muted or at zero.
5. In the other app, disable noise suppression or automatic gain control temporarily and test again. Some noise gates cut quiet speech.
6. Turn off **Test with speakers instead** in Phone Mic. If it is on, audio goes to the speakers instead of CABLE Input.

### Audio is heard on the speakers, but not in Discord

This is expected if **Test with speakers instead** is checked, or if the dropdown is set to a normal speaker instead of CABLE Input. Select CABLE Input and uncheck the speaker test.

### Crackling, stuttering, or robotic audio

This build has a 40 ms jitter buffer, so short Wi-Fi hiccups should be much less audible than in Stage 1. If audio still breaks up:

- Move the phone closer to the Wi-Fi router.
- Use a 5 GHz Wi-Fi network if available.
- Turn off VPNs.
- Stop large downloads or other high-bandwidth activity.
- Disconnect and reconnect the phone's Wi-Fi, then tap Stop and Start again.
- If only one app sounds bad, check that app's noise suppression and audio processing settings.

### Audio is delayed

The jitter buffer adds about 40 ms. Windows and VB-Cable add a little more. On a decent 5 GHz network the total should normally stay under 100 ms. If the delay is much larger:

- Check that the phone is not using mobile data or a VPN.
- Make sure the PC and phone are on the same router, not a repeater or guest network.
- Stop other high-bandwidth activity.

### Phone says "Could not send audio"

The phone could not send the UDP packet. Check that Wi-Fi is still connected, make sure the PC is awake, then tap **Start streaming** again. Automatic reconnect every second is added in Stage 6. For now, stop and start the stream.

### Phone says "Could not start the microphone"

Another app may be holding the microphone, or Android may have denied permission. Close other recording/calling apps and try again. If permission is blocked:

1. Open Android **Settings**.
2. Go to **Apps** > **Phone Mic** > **Permissions** > **Microphone**.
3. Choose **Allow**.
4. Return to Phone Mic and tap **Start streaming**.

### The phone stops streaming when the screen turns off or the app is minimized

This is expected in Stage 3. The microphone currently runs inside the main screen. Keep Phone Mic open and in front while using it. Stage 5 moves capture into a foreground service with a Wi-Fi lock, wake lock, and a persistent notification so it can continue with the screen off.

### "Could not listen on UDP port 48123"

Another program (or another copy of Phone Mic) is already using the port. Close other copies of Phone Mic, then restart the app. If it keeps happening, restart Windows.

### PC says "No network connection found"

Connect the PC to Wi-Fi or Ethernet, then restart Phone Mic. The app only displays a LAN address while an active connection is available.

### PC says "No audio output devices were found" or an output error

1. Connect speakers or headphones and check that Windows audio is working.
2. Click **Refresh** in the output card.
3. If you are trying to use VB-Cable, reinstall it as administrator and restart the PC.
4. If a selected device was unplugged, choose another device from the dropdown or use **Test with speakers instead**.

### I can't install the APK

- The phone must be Android 8.0 or newer.
- Enable "Install unknown apps" for the file manager or browser you use to open the APK.
- If the install still fails, uninstall an older Phone Mic build first, then try again.

## Stage 3 limitations

These are intentionally deferred to later stages:

- No live input/output level meters yet.
- No foreground service, notification, wake lock, or Wi-Fi lock. The phone must stay on the Phone Mic screen.
- No automatic reconnect after a network drop.
- The PC output device is auto-selected each launch. A manually chosen device is not remembered yet.
- No tray icon or automatic firewall rule.
- No installer. The Windows app is a folder with `PhoneMic.exe` and its DLLs.
- Jitter buffer size is fixed at about 40 ms.

## How it works

Both apps use the same deliberately simple wire format:

| Part | Value |
| --- | --- |
| Sample rate | 48,000 Hz |
| Format | Signed 16-bit little-endian PCM |
| Channels | Mono |
| Transport | UDP |
| Port | 48123 |
| Packet header | 4-byte big-endian unsigned sequence number |
| Audio per packet | 960 bytes (480 samples / 10 ms) |
| Total packet size | 964 bytes |

The phone opens a UDP socket and sends one packet every 10 ms. There is no handshake and no compression.

On the PC:

1. A background thread receives packets and reads the sequence number.
2. A 40 ms prebuffer collects four packets before playback starts.
3. Packets are played in sequence order. Late packets are dropped.
4. A missing packet becomes 10 ms of silence, so the audio timeline stays continuous.
5. If more than 100 ms is queued, older audio is discarded so latency cannot grow.
6. If packets stop for 500 ms, playback returns to silence and the status returns to **Waiting for phone...**. It resumes automatically when packets return.
7. NAudio sends the PCM to the selected output device. When the selected device is **CABLE Input**, the virtual cable exposes the audio on **CABLE Output** for other apps.

Sequence numbers are unsigned 32-bit values and are compared with wrap-around-safe arithmetic, so the stream survives the counter rolling over from `0xFFFFFFFF` to `0`.

## Build from source

### Windows

Install the [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0), then from the project root:

```powershell
dotnet build windows\PhoneMic.sln -c Release
dotnet publish windows\PhoneMic\PhoneMic.csproj -c Release -o windows\publish
```

Run `windows\publish\PhoneMic.exe`.

### Android

The easiest option is to open the `android` folder in Android Studio with JDK 17 and press **Run**. To build an APK from a terminal:

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

On macOS/Linux, use `./gradlew testDebugUnitTest assembleDebug` instead. The APK is written to `android\app\build\outputs\apk\debug\app-debug.apk`.

The Android app has no runtime third-party dependencies. The only Windows runtime dependency is NAudio.

## Later stages

This is an incremental build. The remaining milestones are:

1. ~~Android streams to the PC; the PC plays through speakers.~~ **Done**
2. ~~Add the jitter buffer and silence-on-loss on the PC.~~ **Done in this build**
3. ~~Add VB-Cable device selection and detection.~~ **Done in this build**
4. Add live level meters and finish the status/IP display.
5. Move Android capture into a foreground service with a Wi-Fi lock, wake lock, and battery-optimization prompt.
6. Add auto-reconnect, saved settings, tray icon, and automatic firewall rule.

---

Made by **Hamza AGOUR** · [github.com/HamzaAGOUR/Phone-Mic](https://github.com/HamzaAGOUR/Phone-Mic)
