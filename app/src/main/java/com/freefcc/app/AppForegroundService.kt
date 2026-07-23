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
        internal const val ACTION_SELECT_HOME_POINT =
            "com.freefcc.app.notification.SELECT_HOME_POINT"
        internal const val ACTION_SELECT_PERIODIC =
            "com.freefcc.app.notification.SELECT_PERIODIC"
        internal const val ACTION_SELECT_OFF =
            "com.freefcc.app.notification.SELECT_OFF"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppForegroundService::class.java))
        }

        fun refresh(context: Context) {
            try {
                context.startService(Intent(context, AppForegroundService::class.java))
            } catch (_: Exception) {
                start(context)
            }
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
        runCatching { applyNotificationAction(intent?.action) }
            .onFailure {
                FccViewModel.logServiceEvent(
                    "NOTIFICATION: action failed: ${it.javaClass.simpleName}: ${it.message}"
                )
            }
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun applyNotificationAction(action: String?) {
        val selectedMode = AppNotificationActionPolicy.selectedMode(action)
        if (selectedMode != null) {
            val currentMode = AutoFccSelection.load(this)
            if (
                currentMode == selectedMode &&
                FccKeepaliveService.isRunningFlagSet(this)
            ) {
                return
            }
            if (currentMode != null && currentMode != selectedMode) {
                FccKeepaliveService.stop(this, clearSelection = false)
            }
            AutoFccSelection.save(this, selectedMode)
            if (
                selectedMode == AutoFccMode.HOME_POINT_TEXT &&
                !FccKeepaliveService.isDjiFlyTextAccessEnabled(this)
            ) {
                FccViewModel.logServiceEvent(
                    "NOTIFICATION: Home Point selected; Accessibility setup required"
                )
                return
            }
            runCatching { FccKeepaliveService.start(this, selectedMode) }
                .onFailure {
                    FccViewModel.logServiceEvent(
                        "NOTIFICATION: could not start ${selectedMode.wireValue}: ${it.message}"
                    )
                }
        } else if (AppNotificationActionPolicy.turnsOff(action)) {
            AutoFccSelection.save(this, null)
            FccKeepaliveService.stop(this)
        }
    }

    private fun createNotification(): Notification {
        val selectedMode = AutoFccSelection.load(this)
        val accessibilityEnabled = FccKeepaliveService.isDjiFlyTextAccessEnabled(this)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val homePointPendingIntent = if (accessibilityEnabled) {
            serviceActionPendingIntent(ACTION_SELECT_HOME_POINT, requestCode = 1)
        } else {
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    action = ACTION_SELECT_HOME_POINT
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val homePointAction = notificationAction(
            pendingIntent = homePointPendingIntent,
            icon = android.R.drawable.ic_menu_mylocation,
            label = if (selectedMode == AutoFccMode.HOME_POINT_TEXT) {
                "✓ Home Point"
            } else {
                "Home Point"
            }
        )
        val periodicAction = notificationAction(
            pendingIntent = serviceActionPendingIntent(
                ACTION_SELECT_PERIODIC,
                requestCode = 2
            ),
            icon = android.R.drawable.ic_popup_sync,
            label = if (selectedMode == AutoFccMode.PERIODIC_5S) {
                "✓ Every 5 sec"
            } else {
                "Every 5 sec"
            }
        )
        val offAction = notificationAction(
            pendingIntent = serviceActionPendingIntent(ACTION_SELECT_OFF, requestCode = 3),
            icon = android.R.drawable.ic_menu_close_clear_cancel,
            label = if (selectedMode == null) "✓ Off" else "Off"
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SkylabFCCfree")
            .setContentText(
                AppNotificationActionPolicy.statusText(selectedMode, accessibilityEnabled)
            )
            .setSubText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .addAction(homePointAction)
            .addAction(periodicAction)
            .addAction(offAction)
            .build()
    }

    private fun notificationAction(
        pendingIntent: PendingIntent,
        icon: Int,
        label: String
    ): Notification.Action {
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            label,
            pendingIntent
        ).build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AppForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal object AppNotificationActionPolicy {
    fun selectedMode(action: String?): AutoFccMode? = when (action) {
        AppForegroundService.ACTION_SELECT_HOME_POINT -> AutoFccMode.HOME_POINT_TEXT
        AppForegroundService.ACTION_SELECT_PERIODIC -> AutoFccMode.PERIODIC_5S
        else -> null
    }

    fun turnsOff(action: String?): Boolean =
        action == AppForegroundService.ACTION_SELECT_OFF

    fun statusText(mode: AutoFccMode?, accessibilityEnabled: Boolean): String = when (mode) {
        AutoFccMode.HOME_POINT_TEXT -> {
            if (accessibilityEnabled) {
                "Auto FCC: Home Point"
            } else {
                "Auto FCC: Home Point · enable Accessibility"
            }
        }
        AutoFccMode.PERIODIC_5S -> "Auto FCC: every 5 seconds"
        null -> "Auto FCC: Off"
    }
}
