package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        internal const val ACTION_GPS_ON =
            "com.freefcc.app.notification.GPS_ON"
        internal const val ACTION_GPS_OFF =
            "com.freefcc.app.notification.GPS_OFF"

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gpsActionBusy = AtomicBoolean(false)

    /**
     * Sends what is owed as soon as the controller has a link again.
     *
     * The controller is powered for a flight and put away, often with no
     * network while it is on. A report that waits for its daily turn assumes
     * the controller will still be running when that turn comes, which is the
     * one thing it usually is not.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            // Validated, not merely available: a link is up well before it can
            // carry anything, and retrying on `onAvailable` would spend the
            // retry before there was anywhere to send.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                UsageStatistics.onNetworkAvailable(this@AppForegroundService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppForegroundNotification.createChannel(this)
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build(),
                networkCallback
            )
        }
        // Also on start: the service comes up with the controller, and by then
        // a link may already be there with a report still owed from last time.
        UsageStatistics.onNetworkAvailable(this)
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
        AppNotificationActionPolicy.gpsEnabled(action)?.let { enabled ->
            startGpsAction(enabled)
            return
        }
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
            UsageStatistics.recordAction(this, UsageAction.forAutoMode(selectedMode))
            runCatching { FccKeepaliveService.start(this, selectedMode) }
                .onFailure {
                    FccViewModel.logServiceEvent(
                        "NOTIFICATION: could not start ${selectedMode.wireValue}: ${it.message}"
                    )
                }
        } else if (AppNotificationActionPolicy.turnsOff(action)) {
            UsageStatistics.recordAction(this, UsageAction.AUTO_FCC_OFF)
            AutoFccSelection.save(this, null)
            FccKeepaliveService.stop(this)
        }
    }

    private fun startGpsAction(enabled: Boolean) {
        val requestedLabel = if (enabled) "ON" else "OFF"
        if (!gpsActionBusy.compareAndSet(false, true)) {
            AppForegroundNotification.updateGpsStatus("GPS busy — please wait")
            refreshNotification()
            return
        }
        UsageStatistics.recordAction(
            this,
            if (enabled) UsageAction.GPS_ON else UsageAction.GPS_OFF
        )

        AppForegroundNotification.updateGpsStatus("GPS $requestedLabel: starting...")
        refreshNotification()
        FccViewModel.logServiceEvent("NOTIFICATION: GPS $requestedLabel requested")

        serviceScope.launch {
            try {
                val sendResult = GpsCommandRunner.send(
                    enabled = enabled,
                    onProgress = { cycle, cycles, write, writes ->
                        if (write == 1) {
                            AppForegroundNotification.updateGpsStatus(
                                "GPS $requestedLabel: cycle $cycle/$cycles"
                            )
                            refreshNotification()
                        }
                        FccViewModel.logServiceEvent(
                            "NOTIFICATION: GPS $requestedLabel cycle $cycle/$cycles, " +
                                "write $write/$writes"
                        )
                    },
                    onCycleRepeated = { cycle, cycles ->
                        FccViewModel.logServiceEvent(
                            "NOTIFICATION: GPS $requestedLabel cycle $cycle/$cycles sent; " +
                                "duplicating command"
                        )
                    }
                )

                when (sendResult) {
                    GpsCommandSendResult.PORT_BUSY -> {
                        AppForegroundNotification.updateGpsStatus("GPS: port 40007 busy")
                        FccViewModel.logServiceEvent(
                            "NOTIFICATION: GPS $requestedLabel could not acquire port 40007"
                        )
                    }
                    GpsCommandSendResult.TRANSPORT_FAILED -> {
                        AppForegroundNotification.updateGpsStatus(
                            "GPS $requestedLabel: transport failed"
                        )
                        FccViewModel.logServiceEvent(
                            "NOTIFICATION: GPS $requestedLabel command transport failed"
                        )
                    }
                    GpsCommandSendResult.SENT -> {
                        GpsControlStateStore.clear(this@AppForegroundService)
                        AppForegroundNotification.updateGpsStatus(
                            "GPS $requestedLabel sent · checking status..."
                        )
                        refreshNotification()
                        delay(250)
                        val statusResult = GpsCommandRunner.readFresh(
                            onAttempt = { attempt, attempts ->
                                AppForegroundNotification.updateGpsStatus(
                                    "GPS $requestedLabel: status $attempt/$attempts..."
                                )
                                refreshNotification()
                            },
                            onMissing = { attempt, attempts ->
                                FccViewModel.logServiceEvent(
                                    "NOTIFICATION: GPS status attempt $attempt/$attempts missing"
                                )
                            }
                        )
                        applyGpsResult(enabled, statusResult.readback)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppForegroundNotification.updateGpsStatus(
                    "GPS $requestedLabel error: ${e.message ?: e.javaClass.simpleName}"
                )
                FccViewModel.logServiceEvent(
                    "NOTIFICATION: GPS $requestedLabel failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            } finally {
                gpsActionBusy.set(false)
                refreshNotification()
            }
        }
    }

    private fun applyGpsResult(enabled: Boolean, readback: GpsReadback?) {
        val requestedState = if (enabled) GpsState.ON else GpsState.OFF
        val requestedLabel = requestedState.name
        if (readback == null) {
            AppForegroundNotification.updateGpsStatus(
                "GPS $requestedLabel sent · status unknown"
            )
            FccViewModel.logServiceEvent(
                "NOTIFICATION: GPS $requestedLabel sent; fresh status unavailable"
            )
            return
        }

        GpsControlStateStore.persist(this, readback, System.currentTimeMillis())
        if (readback.state == requestedState) {
            AppForegroundNotification.updateGpsStatus("GPS: verified $requestedLabel")
            FccViewModel.logServiceEvent("NOTIFICATION: GPS verified $requestedLabel")
        } else {
            AppForegroundNotification.updateGpsStatus(
                "GPS: ${readback.state.name} · requested $requestedLabel"
            )
            FccViewModel.logServiceEvent(
                "NOTIFICATION: GPS mismatch: requested $requestedLabel, read ${readback.state}"
            )
        }
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun createNotification(): Notification =
        AppForegroundNotification.create(this)

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal object AppForegroundNotification {
    @Volatile private var gpsStatusOverride: String? = null

    fun updateGpsStatus(status: String) {
        gpsStatusOverride = status
    }

    fun clearGpsStatus() {
        gpsStatusOverride = null
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            AppForegroundService.CHANNEL_ID,
            "SkylabFCCfree status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that SkylabFCCfree is running"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun create(context: Context): Notification {
        val selectedMode = AutoFccSelection.load(context)
        val accessibilityEnabled = FccKeepaliveService.isDjiFlyTextAccessEnabled(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val homePointPendingIntent = if (accessibilityEnabled) {
            serviceActionPendingIntent(
                context,
                AppForegroundService.ACTION_SELECT_HOME_POINT,
                requestCode = 1
            )
        } else {
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    action = AppForegroundService.ACTION_SELECT_HOME_POINT
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val compactView = createRemoteViews(
            context = context,
            selectedMode = selectedMode,
            accessibilityEnabled = accessibilityEnabled,
            openPendingIntent = openPendingIntent,
            homePointPendingIntent = homePointPendingIntent,
            showGpsControls = false
        )
        val expandedView = createRemoteViews(
            context = context,
            selectedMode = selectedMode,
            accessibilityEnabled = accessibilityEnabled,
            openPendingIntent = openPendingIntent,
            homePointPendingIntent = homePointPendingIntent,
            showGpsControls = true
        )
        return Notification.Builder(context, AppForegroundService.CHANNEL_ID)
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
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .build()
    }

    /**
     * Draws one control of the notification: its label, its tick and whether it
     * reads as the option in force.
     *
     * The tick used to be a plain character in the label, which on a dark shade
     * is the same colour as every other option and says nothing until it is
     * read. Green carries the meaning at a glance, and the same green fills the
     * control behind it so the state survives even where the glyph is small.
     */
    /** Same green as the active control's fill, so both read as one state. */
    private const val SELECTED_TICK_COLOR = 0xFF41C463.toInt()

    private fun applyControl(
        views: RemoteViews,
        viewId: Int,
        label: String,
        selected: Boolean
    ) {
        views.setTextViewText(viewId, controlLabel(label, selected))
        views.setInt(
            viewId,
            "setBackgroundResource",
            if (selected) R.drawable.notification_chip_active else R.drawable.notification_chip
        )
    }

    /** `✓ label` with the tick in green, or the bare label when not selected. */
    private fun controlLabel(label: String, selected: Boolean): CharSequence {
        if (!selected) return label
        val marked = SpannableString("✓ $label")
        marked.setSpan(
            ForegroundColorSpan(SELECTED_TICK_COLOR),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return marked
    }

    private fun createRemoteViews(
        context: Context,
        selectedMode: AutoFccMode?,
        accessibilityEnabled: Boolean,
        openPendingIntent: PendingIntent,
        homePointPendingIntent: PendingIntent,
        showGpsControls: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_controls)
        val gpsState = GpsControlStateStore.load(context)?.first?.state
        views.setTextViewText(
            R.id.notification_auto_status,
            AppNotificationActionPolicy.statusText(selectedMode, accessibilityEnabled)
        )
        applyControl(
            views,
            R.id.notification_home_point,
            "Home Point",
            selectedMode == AutoFccMode.HOME_POINT_TEXT
        )
        applyControl(
            views,
            R.id.notification_periodic,
            "Every 10 sec",
            selectedMode == AutoFccMode.PERIODIC_10S
        )
        applyControl(views, R.id.notification_fcc_off, "Off", selectedMode == null)
        views.setTextViewText(
            R.id.notification_gps_status,
            gpsStatusOverride ?: gpsState?.let { "GPS: Last verified ${it.name}" }
                ?: "GPS: status unknown"
        )
        applyControl(views, R.id.notification_gps_on, "GPS ON", gpsState == GpsState.ON)
        applyControl(views, R.id.notification_gps_off, "GPS OFF", gpsState == GpsState.OFF)
        views.setViewVisibility(
            R.id.notification_fcc_row,
            if (showGpsControls) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.notification_gps_section,
            if (showGpsControls) View.VISIBLE else View.GONE
        )
        views.setOnClickPendingIntent(R.id.notification_root, openPendingIntent)
        views.setOnClickPendingIntent(R.id.notification_home_point, homePointPendingIntent)
        views.setOnClickPendingIntent(
            R.id.notification_periodic,
            serviceActionPendingIntent(
                context,
                AppForegroundService.ACTION_SELECT_PERIODIC,
                requestCode = 2
            )
        )
        views.setOnClickPendingIntent(
            R.id.notification_fcc_off,
            serviceActionPendingIntent(
                context,
                AppForegroundService.ACTION_SELECT_OFF,
                requestCode = 3
            )
        )
        views.setOnClickPendingIntent(
            R.id.notification_gps_on,
            serviceActionPendingIntent(
                context,
                AppForegroundService.ACTION_GPS_ON,
                requestCode = 4
            )
        )
        views.setOnClickPendingIntent(
            R.id.notification_gps_off,
            serviceActionPendingIntent(
                context,
                AppForegroundService.ACTION_GPS_OFF,
                requestCode = 5
            )
        )
        return views
    }

    private fun serviceActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, AppForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

internal object AppNotificationActionPolicy {
    fun gpsEnabled(action: String?): Boolean? = when (action) {
        AppForegroundService.ACTION_GPS_ON -> true
        AppForegroundService.ACTION_GPS_OFF -> false
        else -> null
    }

    fun selectedMode(action: String?): AutoFccMode? = when (action) {
        AppForegroundService.ACTION_SELECT_HOME_POINT -> AutoFccMode.HOME_POINT_TEXT
        AppForegroundService.ACTION_SELECT_PERIODIC -> AutoFccMode.PERIODIC_10S
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
        AutoFccMode.PERIODIC_10S -> "Auto FCC: every 10 seconds"
        null -> "Auto FCC: Off"
    }
}
