package com.phonemic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var preferences: SharedPreferences

    private var streamer: MicStreamer? = null
    private var pendingStartIp: String? = null
    private var streamGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        startStopButton = findViewById(R.id.startStopButton)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        startStopButton.setOnClickListener { onStartStopClicked() }
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

    override fun onStop() {
        // Stage 1 captures inside the Activity. Stopping when the app is no
        // longer visible avoids a misleading "Streaming" status while Android
        // restricts background microphone access. Stage 5 moves this into a
        // foreground service so streaming can continue with the screen off.
        if (streamer != null) {
            stopStreaming()
        }
        super.onStop()
    }

    override fun onDestroy() {
        streamGeneration++
        streamer?.stop()
        streamer = null
        clearKeepScreenOn()
        super.onDestroy()
    }

    private fun onStartStopClicked() {
        if (streamer != null) {
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

        beginStreaming(ipAddress)
    }

    private fun beginStreaming(ipAddress: String) {
        hideKeyboard()
        pendingStartIp = null
        showStartingUi()
        showStatus(getString(R.string.status_starting), R.color.status_starting)

        // Ignore callbacks from an older stream if the user stops and starts
        // again quickly while queued UI messages are still arriving.
        val generation = ++streamGeneration
        val newStreamer = MicStreamer { status ->
            runOnUiThread {
                if (generation == streamGeneration && !isFinishing && !isDestroyed) {
                    handleStreamerStatus(status)
                }
            }
        }
        streamer = newStreamer
        newStreamer.start(ipAddress)
    }

    private fun stopStreaming() {
        if (streamer == null) return

        startStopButton.isEnabled = false
        showStatus(getString(R.string.status_stopping), R.color.status_neutral)
        streamer?.stop()
    }

    private fun handleStreamerStatus(status: MicStreamer.Status) {
        when (status) {
            MicStreamer.Status.Starting -> {
                showStartingUi()
                showStatus(getString(R.string.status_starting), R.color.status_starting)
            }

            is MicStreamer.Status.Streaming -> {
                showStreamingUi()
                showStatus(
                    getString(R.string.status_streaming, status.ipAddress),
                    R.color.status_success,
                )
            }

            MicStreamer.Status.Stopped -> {
                streamer = null
                showIdleUi()
                showStatus(getString(R.string.status_stopped), R.color.status_neutral)
            }

            is MicStreamer.Status.Error -> {
                streamer = null
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
    }

    private fun showStartingUi() {
        setKeepScreenOn()
        ipInput.isEnabled = false
        startStopButton.isEnabled = false
        startStopButton.text = getString(R.string.start_streaming_progress)
        startStopButton.setBackgroundResource(R.drawable.bg_button_start)
    }

    private fun showStreamingUi() {
        setKeepScreenOn()
        ipInput.isEnabled = false
        startStopButton.isEnabled = true
        startStopButton.text = getString(R.string.stop_streaming)
        startStopButton.setBackgroundResource(R.drawable.bg_button_stop)
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

        if (requestCode != REQUEST_RECORD_AUDIO) return

        val ipAddress = pendingStartIp
        pendingStartIp = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted && ipAddress != null) {
            beginStreaming(ipAddress)
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
        const val PREFERENCES_NAME = "phone_mic"
        const val KEY_AUDIO_PERMISSION_ASKED = "audio_permission_asked"
    }
}
