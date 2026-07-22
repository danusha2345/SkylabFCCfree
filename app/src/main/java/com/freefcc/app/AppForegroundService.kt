package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Keeps the app process visible and less likely to be reclaimed while the controller is on. */
class AppForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "app_runtime"
        const val NOTIFICATION_ID = 9011

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SkylabFCCfree status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that SkylabFCCfree is running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SkylabFCCfree")
            .setContentText("App is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
