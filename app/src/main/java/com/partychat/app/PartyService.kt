package com.partychat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The key piece: a foreground service of type "microphone".
 *
 * Android cuts off the mic for any app in the background unless it is running one of
 * these (same mechanism Discord and WhatsApp use during calls). While this service is
 * up, the WebView keeps sending your voice even with the app minimized or the screen off.
 * It also holds the CPU + Wi-Fi awake and owns the headset audio routing.
 */
class PartyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var router: AudioRouter
    private var routeText = "Starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        router = AudioRouter(this).apply {
            speakerWhenNoHeadset = Prefs.speakerFallback(this@PartyService)
            onRouteChanged = { desc ->
                routeText = desc
                updateNotification()
                routeListener?.invoke(desc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequestedListener?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REROUTE -> {
                router.speakerWhenNoHeadset = Prefs.speakerFallback(this)
                router.route()
                return START_NOT_STICKY
            }
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Happens if mic permission isn't granted yet or the app isn't in the foreground.
            Log.e(TAG, "Could not start foreground mic service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        router.start()
        running = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents = leave the party.
        stopRequestedListener?.invoke()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        router.stop()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PartyChat:voice").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "PartyChat:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Party voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while you're connected to party chat"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val leave = PendingIntent.getService(
            this, 1,
            Intent(this, PartyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reroute = PendingIntent.getService(
            this, 2,
            Intent(this, PartyService::class.java).setAction(ACTION_REROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Party chat mic active")
            .setContentText("Audio: $routeText")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Fix audio", reroute)
            .addAction(0, "Leave", leave)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) { /* notification permission denied; service still works */ }
    }

    companion object {
        private const val TAG = "PartyService"
        private const val CHANNEL_ID = "party_voice"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.partychat.app.STOP"
        const val ACTION_REROUTE = "com.partychat.app.REROUTE"

        @Volatile var running = false
            private set

        /** Set by MainActivity so "Leave" in the notification closes the app too. */
        var stopRequestedListener: (() -> Unit)? = null
        /** Set by MainActivity to show the current audio device in the title bar. */
        var routeListener: ((String) -> Unit)? = null

        /** Must be called while the app is on screen (Android 14+ rule for mic services). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PartyService::class.java))
        }

        fun reroute(context: Context) {
            if (running) context.startService(Intent(context, PartyService::class.java).setAction(ACTION_REROUTE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PartyService::class.java))
        }
    }
}
