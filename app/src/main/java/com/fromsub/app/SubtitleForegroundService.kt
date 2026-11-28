package com.fromsub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SubtitleForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            isRunning = false
            try {
                onCancelRequested?.invoke()
            } catch (_: Exception) {
            }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP || intent == null) {
            isRunning = false
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: "Generating subtitles..."
        val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
        val cancelLabel = intent.getStringExtra(EXTRA_CANCEL_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: "Cancel"

        val notification = buildNotificationStatic(this, statusText, progress, cancelLabel)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
        } catch (_: Exception) {
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SubLyter::SubtitleGenWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // до 60 минут фоновой генерации
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SubLyter Subtitle Generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio processing and subtitle generation"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "sublater_subtitle_gen_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START_OR_UPDATE = "com.fromsub.app.action.START_OR_UPDATE_FG"
        const val ACTION_STOP = "com.fromsub.app.action.STOP_FG"
        const val ACTION_CANCEL = "com.fromsub.app.action.CANCEL_FG"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_CANCEL_LABEL = "extra_cancel_label"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var onCancelRequested: (() -> Unit)? = null

        fun buildNotificationStatic(
            context: Context,
            contentText: String,
            progress: Int = -1,
            cancelLabel: String = "Cancel"
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, openIntent, pendingFlags)

            val cancelIntent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = PendingIntent.getService(context, 1, cancelIntent, pendingFlags)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("SubLyter")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, cancelLabel, cancelPendingIntent)

            if (progress in 0..100) {
                builder.setProgress(100, progress, false)
                builder.setSubText("$progress%")
            }

            return builder.build()
        }

        fun updateNotificationIfRunning(
            context: Context,
            statusText: String,
            progress: Int,
            cancelLabel: String
        ): Boolean {
            if (!isRunning) return false
            return try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false
                val text = statusText.takeIf { it.isNotBlank() } ?: "Generating subtitles..."
                val label = cancelLabel.takeIf { it.isNotBlank() } ?: "Cancel"
                nm.notify(NOTIFICATION_ID, buildNotificationStatic(context, text, progress, label))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
