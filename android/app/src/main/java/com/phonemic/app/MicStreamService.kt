package com.phonemic.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.phonemic.app.audio.MicStreamer

/**
 * Owns the microphone stream so it survives the app leaving the foreground or
 * the screen turning off. Android requires a visible foreground notification,
 * and the wake/Wi-Fi locks keep the CPU and radio responsive while the screen
 * is off.
 */
class MicStreamService : Service() {

    interface Listener {
        fun onStreamStateChanged()
        fun onMicLevelChanged(level: Int)
    }

    inner class LocalBinder : Binder() {
        val service: MicStreamService get() = this@MicStreamService
    }

    private val binder = LocalBinder()

    @Volatile
    private var streamer: MicStreamer? = null

    @Volatile
    private var status: MicStreamer.Status? = null

    @Volatile
    var isMuted = false
        private set

    @Volatile
    private var lastLevel = 0

    @Volatile
    private var listener: Listener? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val streamStatus: MicStreamer.Status? get() = status
    val currentLevel: Int get() = lastLevel
    val isActive: Boolean
        get() = status is MicStreamer.Status.Starting || status is MicStreamer.Status.Streaming

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ipAddress = intent.getStringExtra(EXTRA_IP_ADDRESS)
                if (ipAddress.isNullOrBlank()) {
                    stopSelf()
                } else {
                    startStreaming(ipAddress)
                }
            }

            ACTION_STOP -> stopStreaming()

            ACTION_TOGGLE_MUTE -> if (isActive) setMuted(!isMuted)
        }
        return START_NOT_STICKY
    }

    fun stopStreaming() {
        val activeStreamer = streamer
        if (activeStreamer != null) {
            activeStreamer.stop()
            return
        }

        status = MicStreamer.Status.Stopped
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyState()
        stopSelf()
    }

    /**
     * Mutes or unmutes the outgoing audio while keeping the stream connected.
     */
    fun setMuted(muted: Boolean) {
        if (!isActive || isMuted == muted) return

        isMuted = muted
        streamer?.setMuted(muted)
        updateNotification()
        notifyState()
    }

    private fun startStreaming(ipAddress: String) {
        if (streamer != null) return

        status = MicStreamer.Status.Starting
        isMuted = false
        lastLevel = 0
        acquireLocks()

        try {
            startForegroundWithNotification(buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter the foreground state", e)
            status = MicStreamer.Status.Error(MicStreamer.ErrorKind.BACKGROUND)
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifyState()
            stopSelf()
            return
        }

        val activeStreamer = MicStreamer(
            listener = ::handleStreamerStatus,
            onLevelChanged = { level ->
                lastLevel = level
                try {
                    listener?.onMicLevelChanged(level)
                } catch (t: Throwable) {
                    Log.w(TAG, "Microphone level listener failed", t)
                }
            },
        )
        streamer = activeStreamer
        activeStreamer.start(ipAddress)
    }

    private fun handleStreamerStatus(newStatus: MicStreamer.Status) {
        status = newStatus

        when (newStatus) {
            is MicStreamer.Status.Streaming, MicStreamer.Status.Starting -> {
                updateNotification()
            }

            MicStreamer.Status.Stopped, is MicStreamer.Status.Error -> {
                streamer = null
                isMuted = false
                lastLevel = 0
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        notifyState()
    }

    private fun notifyState() {
        try {
            listener?.onStreamStateChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "Stream state listener failed", t)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val currentStatus = status
        val text = when {
            currentStatus is MicStreamer.Status.Streaming && isMuted ->
                getString(R.string.status_muted, currentStatus.ipAddress)

            currentStatus is MicStreamer.Status.Streaming ->
                getString(R.string.status_streaming, currentStatus.ipAddress)

            else -> getString(R.string.status_starting)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setColor(getColor(R.color.primary))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (currentStatus is MicStreamer.Status.Streaming) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification_mic),
                    getString(
                        if (isMuted) R.string.unmute_microphone else R.string.mute_microphone,
                    ),
                    servicePendingIntent(ACTION_TOGGLE_MUTE, REQUEST_TOGGLE_MUTE),
                ).build(),
            )
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_mic),
                getString(R.string.stop_streaming),
                servicePendingIntent(ACTION_STOP, REQUEST_STOP),
            ).build(),
        )

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MicStreamService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("NotificationPermission")
    private fun updateNotification() {
        if (!isActive) return

        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not update the notification", t)
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        }.onFailure { Log.w(TAG, "Could not acquire the wake lock", it) }

        runCatching {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        }.onFailure { Log.w(TAG, "Could not acquire the Wi-Fi lock", it) }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
        wakeLock = null

        runCatching {
            wifiLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
        wifiLock = null
    }

    override fun onDestroy() {
        listener = null
        streamer?.stop()
        streamer = null
        status = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.phonemic.app.action.START"
        const val ACTION_STOP = "com.phonemic.app.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.phonemic.app.action.TOGGLE_MUTE"
        const val EXTRA_IP_ADDRESS = "com.phonemic.app.extra.IP_ADDRESS"

        private const val TAG = "MicStreamService"
        private const val CHANNEL_ID = "phone_mic_streaming"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "PhoneMic:stream"
        private const val WIFI_LOCK_TAG = "PhoneMic:wifi"
        private const val REQUEST_TOGGLE_MUTE = 10
        private const val REQUEST_STOP = 11
    }
}
