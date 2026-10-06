package com.livevip.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.livevip.app.MainActivity
import com.livevip.app.R
import com.livevip.app.core.StreamState
import com.livevip.app.data.ProfileRepository
import com.livevip.app.engine.LiveStreamingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the LiveStreamingEngine. The Activity is only UI: minimising the app,
 * locking the screen or destroying the Activity never stops the stream.
 */
class LiveStreamingForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.livevip.app.action.START"
        const val ACTION_STOP = "com.livevip.app.action.STOP"
        const val EXTRA_PROFILE_ID = "profileId"
        private const val CHANNEL_ID = "live_vip_streaming"
        private const val NOTIFICATION_ID = 4411

        fun start(context: Context, profileId: String) {
            val intent = Intent(context, LiveStreamingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveStreamingForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val engine = LiveStreamingEngine.get(this)
        engine.serviceRunning = true
        // Screen state is diagnostics only: streaming NEVER stops because the
        // screen turned off or the Activity went away.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> engine.screenOn = false
                    Intent.ACTION_SCREEN_ON -> engine.screenOn = true
                }
            }
        }
        screenReceiver = receiver
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                startForegroundSafely(notification("PREPARING", "Starting live"))
                acquireWakeLock()
                val repo = ProfileRepository.get(this)
                val profile = repo.get(profileId)
                if (profile == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                repo.markUsed(profileId)
                val engine = LiveStreamingEngine.get(this)
                engine.startLive(profile, repo.streamKey(profileId))
                observe(engine, profile.name)
            }
            ACTION_STOP -> {
                LiveStreamingEngine.get(this).stopLive()
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun observe(engine: LiveStreamingEngine, name: String) {
        collectJob?.cancel()
        collectJob = scope.launch {
            // State changes stop the service; a 1 second ticker keeps the
            // notification timer and bitrate in sync with the real engine.
            launch {
                while (isActive) {
                    val state = engine.state.value
                    if (state.isActive) updateNotification(notification(name, statusText(engine)))
                    delay(1000)
                }
            }
            engine.state.collectLatest { state ->
                updateNotification(notification(name, statusText(engine)))
                if (state == StreamState.STOPPED || state == StreamState.ERROR) {
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun statusText(engine: LiveStreamingEngine): String {
        val state = engine.state.value
        val stats = engine.stats.value
        return buildString {
            append(if (state.isLive) "● LIVE — STREAMING" else state.label())
            if (state.isActive) {
                append("  ").append(stats.elapsedLabel())
                if (stats.sentFps > 0) append("  ").append(String.format("%.0f fps", stats.sentFps))
                if (stats.bitrateBps > 0) {
                    append("  ").append(String.format("%.1f Mbps", stats.bitrateBps / 1_000_000.0))
                }
                if (stats.networkTransport != "-") append("  ").append(stats.networkTransport)
            }
        }
    }

    private fun startForegroundSafely(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            runCatching { startForeground(NOTIFICATION_ID, n, type) }
                .onFailure { startForeground(NOTIFICATION_ID, n) }
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun notification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveStreamingForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LIVE VIP — $title")
            .setSubText("Direct RTMP/RTMPS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_live)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "STOP LIVE", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Live streaming", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing live broadcast" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** Partial wake lock only while actually streaming, released on stop. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveVIP::stream").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        LiveStreamingEngine.get(this).serviceRunning = false
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        collectJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
