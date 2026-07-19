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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps FCC mode active by re-applying the FCC
 * profile every [INTERVAL_MS] milliseconds. Runs independently of the
 * Activity lifecycle so it continues working when the user switches to DJI Fly.
 *
 * The full FCC profile and lightweight keepalive profile are loaded once at
 * service creation and cached. Every service start first runs the full profile
 * once, because the four-frame sequence is only intended for low-cost repeats
 * and cannot reliably switch an RC2 back from CE after DJI Fly has reset it.
 *
 * The persistent keepalive flag (stored in SharedPreferences) is read at start
 * so a sticky restart after a system kill respects the user's last intent.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = "fcc_keepalive"
        const val NOTIFICATION_ID = 9012
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"
        private const val INTERVAL_MS = 2000L
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

    /** Cached at onCreate — JSON parsing and CRC building do not belong in the loop. */
    private var cachedBootstrapProfile: Profiles.Profile? = null
    private var cachedKeepaliveProfile: Profiles.Profile? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // The full profile establishes FCC from CE. The smaller profile only
        // maintains it after that bootstrap succeeds.
        runCatching {
            cachedBootstrapProfile = Profiles.load(this, "fcc.json")
            cachedKeepaliveProfile = Profiles.load(this, "fcc_keepalive.json")
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
                if (cachedBootstrapProfile == null || cachedKeepaliveProfile == null) {
                    runRequested.set(false)
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    FccRuntime.tracker.serviceFailed("FCC keepalive profile unavailable")
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
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            val bootstrapProfile = cachedBootstrapProfile ?: return@launch
            val keepaliveProfile = cachedKeepaliveProfile ?: return@launch
            val schedule = FccKeepaliveSchedule(bootstrapProfile, keepaliveProfile)
            FccRuntime.tracker.serviceRunning()
            while (runRequested.get()) {
                // Apply immediately. The first successful pass is the complete
                // FCC profile; later passes use the lightweight keepalive.
                // Retry acquiring the lock with a short backoff instead of
                // silently skipping the tick. This prevents a gap where DJI
                // Fly can reset the radio to CE while another operation
                // (LED, device info, manual FCC apply) holds the lock for
                // ~1-2s. With the retry, FCC is re-applied within ~200ms
                // of the lock being released, instead of up to INTERVAL_MS
                // later.
                for (retry in 0 until 10) {
                    if (!runRequested.get()) break
                    val hardwareLease = HardwareLock.tryBegin()
                    if (hardwareLease != null) {
                        try {
                            if (runRequested.get()) {
                                val profile = schedule.nextProfile()
                                val written = transport.sendFrames(
                                    frames = profile.frames,
                                    rounds = profile.rounds,
                                    interFrameDelayMs = profile.interFrameDelay,
                                    interRoundDelayMs = profile.interRoundDelay,
                                    readWindowMs = profile.readWindowMs,
                                    port = profile.port
                                )
                                schedule.recordWrite(written)
                                FccRuntime.tracker.recordWrite(written)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            FccRuntime.tracker.recordWrite(false)
                        } finally {
                            hardwareLease.close()
                        }
                        break
                    }
                    // Lock held by another op — wait 200ms and retry.
                    // 10 retries × 200ms = 2s max wait, covering any
                    // reasonable hardware operation.
                    delay(200)
                }
                // The synchronous send time is added to the period between
                // successive loop starts.
                delay(INTERVAL_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FCC Keepalive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps FCC mode active in the background"
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
            .setContentText("Maintaining FCC mode...")
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
