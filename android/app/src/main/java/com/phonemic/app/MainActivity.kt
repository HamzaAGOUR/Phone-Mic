package com.phonemic.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.phonemic.app.audio.Ipv4Address
import com.phonemic.app.audio.MicStreamer

class MainActivity : Activity() {

    private lateinit var ipInput: EditText
    private lateinit var startStopButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var micLevelPanel: View
    private lateinit var micLevelTrack: View
    private lateinit var micLevelFill: View
    private lateinit var micLevelText: TextView
    private lateinit var silentMicHint: TextView
    private lateinit var muteButton: Button
    private lateinit var preferences: SharedPreferences

    private var streamService: MicStreamService? = null
    private var serviceBinding = false
    private var uiActive = false
    private var startRequested = false
    private var pendingStartIp: String? = null
    private var displayedMicLevel = 0.0
    private var silentSinceMs = 0L
    private var isMuted = false

    private val serviceListener = object : MicStreamService.Listener {
        override fun onStreamStateChanged() {
            runOnUiThread {
                if (uiActive) renderServiceState()
            }
        }

        override fun onMicLevelChanged(level: Int) {
            runOnUiThread {
                if (uiActive) showMicLevel(level)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? MicStreamService.LocalBinder ?: return
            if (!uiActive) return

            streamService = localBinder.service
            localBinder.service.setListener(serviceListener)
            renderServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        startStopButton = findViewById(R.id.startStopButton)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        micLevelPanel = findViewById(R.id.micLevelPanel)
        micLevelTrack = findViewById(R.id.micLevelTrack)
        micLevelFill = findViewById(R.id.micLevelFill)
        micLevelText = findViewById(R.id.micLevelText)
        silentMicHint = findViewById(R.id.silentMicHint)
        muteButton = findViewById(R.id.muteButton)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        startStopButton.setOnClickListener { onStartStopClicked() }
        muteButton.setOnClickListener { toggleMute() }
        ipInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startStopButton.performClick()
                true
            } else {
                false
            }
        }

        showStatus(getString(R.string.status_stopped), R.color.status_neutral)
    }

    override fun onStart() {
        super.onStart()
        uiActive = true

        if (!serviceBinding) {
            val intent = Intent(this, MicStreamService::class.java)
            serviceBinding = try {
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "Could not bind the streaming service", e)
                false
            }
        }
    }

    override fun onStop() {
        uiActive = false
        streamService?.setListener(null)

        if (serviceBinding) {
            unbindService(serviceConnection)
            serviceBinding = false
        }
        streamService = null
        clearKeepScreenOn()
        super.onStop()
    }

    override fun onDestroy() {
        clearKeepScreenOn()
        super.onDestroy()
    }

    private fun onStartStopClicked() {
        if (startRequested || streamService?.isActive == true) {
            stopStreaming()
            return
        }

        val ipAddress = Ipv4Address.normalize(ipInput.text.toString())
        if (ipAddress == null) {
            ipInput.error = getString(R.string.error_invalid_ip)
            ipInput.requestFocus()
            showStatus(getString(R.string.error_invalid_ip), R.color.status_error)
            return
        }
        ipInput.error = null

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartIp = ipAddress
            requestMicrophonePermission()
            return
        }

        continueStart(ipAddress)
    }

    /**
     * Continues the start flow after the microphone permission is available.
     * Android 13 also needs notification permission for the foreground service
     * notification. Denying it does not stop streaming, so it is asked once.
     */
    private fun continueStart(ipAddress: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
        ) {
            pendingStartIp = ipAddress
            preferences.edit()
                .putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)
                .apply()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
            return
        }

        beginStreaming(ipAddress)
    }

    private fun beginStreaming(ipAddress: String) {
        hideKeyboard()
        pendingStartIp = null
        startRequested = true
        showStartingUi()
        showStatus(getString(R.string.status_starting), R.color.status_starting)

        val intent = Intent(this, MicStreamService::class.java)
            .setAction(MicStreamService.ACTION_START)
            .putExtra(MicStreamService.EXTRA_IP_ADDRESS, ipAddress)

        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start background streaming", e)
            startRequested = false
            showIdleUi()
            showStatus(getString(R.string.error_background_start), R.color.status_error)
        }
    }

    private fun stopStreaming() {
        startRequested = false
        startStopButton.isEnabled = false
        showStatus(getString(R.string.status_stopping), R.color.status_neutral)

        val service = streamService
        if (service != null) {
            service.stopStreaming()
            return
        }

        try {
            startService(
                Intent(this, MicStreamService::class.java)
                    .setAction(MicStreamService.ACTION_STOP),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not stop the streaming service", e)
            showIdleUi()
            showStatus(getString(R.string.status_stopped), R.color.status_neutral)
        }
    }

    /**
     * Rebuilds the screen from the service. This runs when the Activity
     * reconnects after being in the background, so the UI returns to the
     * correct state instead of showing a stale "Stopped" screen.
     */
    private fun renderServiceState() {
        val service = streamService
        if (service == null) {
            startRequested = false
            showIdleUi()
            showStatus(getString(R.string.status_stopped), R.color.status_neutral)
            return
        }

        when (val status = service.streamStatus) {
            null -> {
                startRequested = false
                showIdleUi()
                showStatus(getString(R.string.status_stopped), R.color.status_neutral)
            }

            MicStreamer.Status.Starting -> {
                startRequested = true
                showStartingUi()
                showStatus(getString(R.string.status_starting), R.color.status_starting)
            }

            is MicStreamer.Status.Streaming -> {
                startRequested = false
                showStreamingUi()
                applyMuteState(service.isMuted)

                if (service.isMuted) {
                    showStatus(
                        getString(R.string.status_muted, status.ipAddress),
                        R.color.status_starting,
                    )
                } else {
                    showStatus(
                        getString(R.string.status_streaming, status.ipAddress),
                        R.color.status_success,
                    )
                }

                showMicLevel(service.currentLevel)
                maybeShowBatteryOptimizationPrompt()
            }

            MicStreamer.Status.Stopped -> {
                startRequested = false
                showIdleUi()
                showStatus(getString(R.string.status_stopped), R.color.status_neutral)
            }

            is MicStreamer.Status.Error -> {
                startRequested = false
                showIdleUi()
                showStatus(errorMessage(status.kind), R.color.status_error)
            }
        }
    }

    private fun showIdleUi() {
        clearKeepScreenOn()
        ipInput.isEnabled = true
        startStopButton.isEnabled = true
        startStopButton.text = getString(R.string.start_streaming)
        startStopButton.setBackgroundResource(R.drawable.bg_button_start)
        micLevelPanel.visibility = View.GONE
        resetMuteState()
        resetMicLevel()
    }

    private fun showStartingUi() {
        setKeepScreenOn()
        ipInput.isEnabled = false
        startStopButton.isEnabled = false
        startStopButton.text = getString(R.string.start_streaming_progress)
        startStopButton.setBackgroundResource(R.drawable.bg_button_start)
        micLevelPanel.visibility = View.GONE
        resetMuteState()
        resetMicLevel()
    }

    private fun showStreamingUi() {
        setKeepScreenOn()
        ipInput.isEnabled = false
        startStopButton.isEnabled = true
        startStopButton.text = getString(R.string.stop_streaming)
        startStopButton.setBackgroundResource(R.drawable.bg_button_stop)
        micLevelPanel.visibility = View.VISIBLE
    }

    /**
     * Draws the 0..100 microphone level with fast attack and slow release.
     * A stream of exact digital silence usually means Android is muting the
     * microphone (privacy toggle, mic mute, or another app holding it).
     */
    private fun showMicLevel(level: Int) {
        if (isMuted) {
            displayedMicLevel = 0.0
            updateMeterFill(0.0)
            micLevelText.text = getString(R.string.mic_level_zero)
            silentSinceMs = 0L
            silentMicHint.visibility = View.GONE
            return
        }

        displayedMicLevel = if (level >= displayedMicLevel) {
            level.toDouble()
        } else {
            maxOf(0.0, displayedMicLevel - 6)
        }

        updateMeterFill(displayedMicLevel)
        micLevelText.text = getString(
            R.string.mic_level_percent,
            displayedMicLevel.toInt(),
        )

        if (level < 2) {
            if (silentSinceMs == 0L) {
                silentSinceMs = SystemClock.elapsedRealtime()
            }
        } else {
            silentSinceMs = 0L
        }

        val silent = silentSinceMs != 0L &&
            SystemClock.elapsedRealtime() - silentSinceMs >= SILENCE_HINT_DELAY_MS
        silentMicHint.visibility = if (silent) View.VISIBLE else View.GONE
    }

    private fun toggleMute() {
        val service = streamService ?: return
        service.setMuted(!service.isMuted)
    }

    /**
     * Asks once whether Phone Mic may ignore battery optimization. This is
     * Android's standard exemption for services that must keep running with
     * the screen off. Declining leaves normal foreground-service behavior.
     */
    private fun maybeShowBatteryOptimizationPrompt() {
        if (preferences.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)) return

        val powerManager = getSystemService(PowerManager::class.java)
        preferences.edit()
            .putBoolean(KEY_BATTERY_PROMPT_SHOWN, true)
            .apply()

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_open_settings) { _, _ ->
                openBatterySettings()
            }
            .setNegativeButton(R.string.permission_not_now, null)
            .show()
    }

    // This app is distributed as a sideloaded APK, not through Google Play, so
    // the Play policy cited by the BatteryLife lint check does not apply.
    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            } catch (_: Exception) {
                // The README has manual battery settings instructions.
            }
        }
    }

    private fun applyMuteState(muted: Boolean) {
        isMuted = muted
        if (muted) {
            muteButton.setText(R.string.unmute_microphone)
            muteButton.setBackgroundResource(R.drawable.bg_button_unmute)
            muteButton.setTextColor(getColor(R.color.danger_dark))
        } else {
            muteButton.setText(R.string.mute_microphone)
            muteButton.setBackgroundResource(R.drawable.bg_button_mute)
            muteButton.setTextColor(getColor(R.color.primary_dark))
        }
    }

    private fun resetMuteState() {
        applyMuteState(false)
    }

    private fun updateMeterFill(percent: Double) {
        val fillWidth = (micLevelTrack.width * (percent / 100.0)).toInt()
        val params = micLevelFill.layoutParams
        if (params.width != fillWidth) {
            params.width = fillWidth
            micLevelFill.layoutParams = params
        }
    }

    private fun resetMicLevel() {
        displayedMicLevel = 0.0
        silentSinceMs = 0L
        updateMeterFill(0.0)
        micLevelText.text = getString(R.string.mic_level_percent, 0)
        silentMicHint.visibility = View.GONE
    }

    private fun showStatus(message: String, colorResource: Int) {
        val color = getColor(colorResource)
        statusText.text = message
        statusText.setTextColor(color)
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun errorMessage(kind: MicStreamer.ErrorKind): String = when (kind) {
        MicStreamer.ErrorKind.MICROPHONE -> getString(R.string.error_microphone_unavailable)
        MicStreamer.ErrorKind.NETWORK -> getString(R.string.error_network)
        MicStreamer.ErrorKind.BACKGROUND -> getString(R.string.error_background_start)
        MicStreamer.ErrorKind.UNKNOWN -> getString(R.string.error_unknown)
    }

    private fun requestMicrophonePermission() {
        val hasAskedBefore = preferences.getBoolean(KEY_AUDIO_PERMISSION_ASKED, false)
        val canShowRationale =
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

        if (hasAskedBefore && !canShowRationale) {
            showPermissionBlockedDialog()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.permission_continue) { _, _ ->
                preferences.edit()
                    .putBoolean(KEY_AUDIO_PERMISSION_ASKED, true)
                    .apply()
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO,
                )
            }
            .setNegativeButton(R.string.permission_not_now) { _, _ ->
                pendingStartIp = null
                showStatus(
                    getString(R.string.error_microphone_permission),
                    R.color.status_error,
                )
            }
            .setOnCancelListener {
                pendingStartIp = null
                showStatus(
                    getString(R.string.error_microphone_permission),
                    R.color.status_error,
                )
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATIONS) {
            val ipAddress = pendingStartIp
            pendingStartIp = null
            if (ipAddress != null) {
                beginStreaming(ipAddress)
            }
            return
        }

        if (requestCode != REQUEST_RECORD_AUDIO) return

        val ipAddress = pendingStartIp
        pendingStartIp = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted && ipAddress != null) {
            continueStart(ipAddress)
            return
        }

        showStatus(
            getString(R.string.error_microphone_permission),
            R.color.status_error,
        )

        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showPermissionBlockedDialog()
        }
    }

    private fun showPermissionBlockedDialog() {
        pendingStartIp = null
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_blocked_title)
            .setMessage(R.string.permission_blocked_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showStatus(
                getString(R.string.error_microphone_permission),
                R.color.status_error,
            )
        }
    }

    private fun hideKeyboard() {
        val inputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        inputMethodManager.hideSoftInputFromWindow(ipInput.windowToken, 0)
        ipInput.clearFocus()
    }

    private fun setKeepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 100
        const val REQUEST_NOTIFICATIONS = 101
        const val PREFERENCES_NAME = "phone_mic"
        const val KEY_AUDIO_PERMISSION_ASKED = "audio_permission_asked"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
        const val SILENCE_HINT_DELAY_MS = 3000L
        const val TAG = "MainActivity"
    }
}
