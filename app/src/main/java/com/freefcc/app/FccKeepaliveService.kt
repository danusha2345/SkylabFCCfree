package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that waits for the aircraft to record Home Point, applies
 * the complete FCC profile once, then stops. It runs independently of the
 * Activity lifecycle while the user is in DJI Fly.
 *
 * Home Point is read from the wrapped `03:44` stream through one long-lived
 * port-40007 connection. No periodic FCC writes are performed.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = "fcc_keepalive"
        const val NOTIFICATION_ID = 9012
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val PREFS_NAME = "freefcc"
        private const val PREF_KEEPALIVE = "keepalive_running"
        private val runRequested = AtomicBoolean(false)

        @Synchronized
        fun start(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hadPersistentRequest = preferences.getBoolean(PREF_KEEPALIVE, false)
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_START
            }
            val wasRequested = runRequested.getAndSet(true)
            if (!wasRequested) FccRuntime.tracker.serviceStartRequested()
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                runRequested.set(wasRequested)
                if (!wasRequested) FccRuntime.tracker.serviceFailed(e.message)
                throw e
            }
            // Persist the user's intent only after Android accepted the
            // foreground-service start request. A transient start restriction
            // must not manufacture a new persistent request.
            preferences.edit().putBoolean(PREF_KEEPALIVE, true).apply()
            return !hadPersistentRequest
        }

        @Synchronized
        fun stop(context: Context) {
            // Set this before posting ACTION_STOP. The service intent is
            // asynchronous, while disableFcc() must prevent another keepalive
            // write immediately after its hardware lease is released.
            runRequested.set(false)
            FccRuntime.tracker.serviceStopped()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Returns whether the service should be running, based on the persistent flag. */
        fun isRunningFlagSet(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEPALIVE, false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    private val transport = DumlTransport()

    /** Cached at onCreate — JSON parsing and CRC building do not belong in the worker. */
    private var cachedBootstrapProfile: Profiles.Profile? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            cachedBootstrapProfile = Profiles.load(this, "fcc.json")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        synchronized(Companion) {
            onStartCommandLocked(intent, startId)
        }

    /** Runs under the same monitor as companion start()/stop(). */
    private fun onStartCommandLocked(intent: Intent?, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A newer start() may already have superseded this queued
                // stop intent. The process-wide desired state is updated
                // synchronously before either intent is posted.
                if (runRequested.get()) return START_STICKY
                keepaliveJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                FccRuntime.tracker.serviceStopped()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            else -> {
                if (intent?.action == ACTION_START) {
                    // Ignore an ACTION_START that was queued before a newer
                    // stop(). It must not resurrect the service or pref.
                    if (!runRequested.get()) {
                        keepaliveJob?.cancel()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        FccRuntime.tracker.serviceStopped()
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                } else {
                    // Null-intent sticky restart after a process/service kill.
                    // In a new process the persistent flag is the desired state.
                    val requested = isRunningFlagSet(this)
                    runRequested.set(requested)
                    if (!requested) {
                        FccRuntime.tracker.serviceStopped()
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                }
                if (!runRequested.get()) {
                    FccRuntime.tracker.serviceStopped()
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                // If the profile failed to load, don't become a silent
                // foreground no-op — stop immediately.
                if (cachedBootstrapProfile == null) {
                    runRequested.set(false)
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    FccRuntime.tracker.serviceFailed("FCC profile unavailable")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification())
                startKeepaliveLoop()
            }
        }
        return START_STICKY
    }

    private fun startKeepaliveLoop() {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob = scope.launch {
            val bootstrapProfile = cachedBootstrapProfile ?: return@launch
            val workerJob = currentCoroutineContext()[Job]
            FccRuntime.tracker.serviceRunning()
            var homePointRecorded = false
            while (runRequested.get() && !homePointRecorded) {
                if (Port40007Lock.shouldYieldToLed()) {
                    delay(25)
                    continue
                }
                val portLease = Port40007Lock.tryBegin()
                if (portLease == null) {
                    delay(250)
                    continue
                }
                val waitResult = try {
                    HomePointMonitor().waitUntilRecorded {
                        runRequested.get() &&
                            workerJob?.isActive != false &&
                            !Port40007Lock.shouldYieldToLed()
                    }
                } finally {
                    portLease.close()
                }
                when (waitResult) {
                    HomePointWaitResult.RECORDED -> homePointRecorded = true
                    HomePointWaitResult.STOPPED -> {
                        if (!runRequested.get() || workerJob?.isActive == false) return@launch
                        while (runRequested.get() && Port40007Lock.shouldYieldToLed()) {
                            delay(25)
                        }
                    }
                    HomePointWaitResult.DISCONNECTED -> delay(RECONNECT_DELAY_MS)
                }
            }
            if (!runRequested.get() || !homePointRecorded) return@launch

            var hardwareLease: HardwareLock.Lease? = null
            while (runRequested.get() && hardwareLease == null) {
                hardwareLease = HardwareLock.tryBegin()
                if (hardwareLease == null) delay(200)
            }
            if (!runRequested.get() || hardwareLease == null) return@launch

            val written = try {
                sendBootstrapProfile(bootstrapProfile)?.also {
                    FccRuntime.tracker.recordWrite(it)
                } ?: return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                FccRuntime.tracker.recordWrite(false)
                false
            } finally {
                hardwareLease.close()
            }

            runRequested.set(false)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (written) FccRuntime.tracker.serviceStopped()
            else FccRuntime.tracker.serviceFailed("FCC apply failed after Home Point")
            stopSelf()
        }
    }

    /** Returns null when STOP was requested before the full profile completed. */
    private suspend fun sendBootstrapProfile(profile: Profiles.Profile): Boolean? {
        if (!runRequested.get()) return null
        if (profile.port == DumlTransport.PORT && !transport.connect()) return false
        val port = if (profile.port == DumlTransport.PORT) {
            transport.getDetectedPort().takeIf { it > 0 } ?: DumlTransport.PORT
        } else {
            profile.port
        }

        var allSuccess = true
        for (round in 0 until profile.rounds) {
            for ((frameIndex, frame) in profile.frames.withIndex()) {
                if (!runRequested.get()) return null
                if (!transport.sendFrame(frame, profile.readWindowMs, port)) {
                    allSuccess = false
                }
                if (!runRequested.get()) return null
                if (profile.interFrameDelay > 0 && frameIndex < profile.frames.lastIndex) {
                    delay(profile.interFrameDelay)
                }
            }
            if (profile.interRoundDelay > 0 && round < profile.rounds - 1) {
                delay(profile.interRoundDelay)
            }
        }
        return allSuccess
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Auto-FCC",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Waits for Home Point before applying FCC"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        // Tapping the notification opens the app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("FreeFCC")
            .setContentText("Waiting for Home Point...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        keepaliveJob?.cancel()
        scope.cancel()
        if (FccRuntime.tracker.state.value.keepaliveStatus != KeepaliveRuntimeStatus.FAILED) {
            FccRuntime.tracker.serviceStopped()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
