package com.freefcc.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class UsageAction(val wireName: String) {
    MANUAL_FCC("manual_fcc"),
    AUTO_FCC_HOME_POINT("auto_fcc_home_point"),
    AUTO_FCC_PERIODIC("auto_fcc_periodic_5s"),
    AUTO_FCC_OFF("auto_fcc_off"),
    GPS_ON("gps_on"),
    GPS_OFF("gps_off"),
    LED_ON("led_on"),
    LED_OFF("led_off"),
    FOUR_G_ACTIVATE("four_g_activate"),
    LAUNCH_DJI_FLY("launch_dji_fly");

    companion object {
        fun forAutoMode(mode: AutoFccMode?): UsageAction = when (mode) {
            AutoFccMode.HOME_POINT_TEXT -> AUTO_FCC_HOME_POINT
            AutoFccMode.PERIODIC_10S -> AUTO_FCC_PERIODIC
            null -> AUTO_FCC_OFF
        }
    }
}

internal data class UsageStatisticsPayload(
    val installationId: String,
    val reportSequence: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val controllerSerial: String,
    val controllerSerialSource: String,
    val controllerDevice: String,
    val controllerModel: String,
    val djiFlyVersionName: String,
    val djiFlyVersionCode: Long?,
    val aircraftSerial: String,
    val aircraftModelCode: String,
    val aircraftModelName: String,
    val settings: Map<String, Any?>,
    val usageByAppVersion: Map<String, Map<String, Long>>
)

internal data class AircraftStatisticsIdentity(
    val serial: String,
    val modelCode: String,
    val modelName: String
)

internal object UsageStatisticsJson {
    fun encode(payload: UsageStatisticsPayload): String = LanJson.objectOf(
        "schema_version" to 2,
        "installation_id" to payload.installationId,
        "report_sequence" to payload.reportSequence,
        "app_version_name" to payload.appVersionName,
        "app_version_code" to payload.appVersionCode,
        "controller_serial" to payload.controllerSerial,
        "controller_serial_source" to payload.controllerSerialSource,
        "controller_device" to payload.controllerDevice,
        "controller_model" to payload.controllerModel,
        "dji_fly_version_name" to payload.djiFlyVersionName,
        "dji_fly_version_code" to payload.djiFlyVersionCode,
        "aircraft_serial" to payload.aircraftSerial,
        "aircraft_model_code" to payload.aircraftModelCode,
        "aircraft_model_name" to payload.aircraftModelName,
        "settings" to payload.settings,
        "usage_by_app_version" to payload.usageByAppVersion
    )
}

/** Extracts only a remote-controller S/N from ordered DJI Fly accessibility labels. */
internal object DjiFlyControllerSerialExtractor {
    private val whitespace = Regex("\\s+")
    private val candidate = Regex("[A-Z0-9]{10,24}")
    private val excludedPrefixes = Regex("^(?:WA|WM)[0-9]{3}[A-Z]?")

    fun find(labels: Collection<String>): String? {
        val normalized = labels.map(::normalize).filter(String::isNotEmpty)
        normalized.forEachIndexed { index, label ->
            if (!isControllerSerialLabel(label)) return@forEachIndexed

            serialCandidates(label).firstOrNull(::isSerial)?.let { return it }
            for (offset in 1..2) {
                val nearby = normalized.getOrNull(index + offset) ?: break
                serialCandidates(nearby).firstOrNull(::isSerial)?.let { return it }
            }
        }
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()

    private fun isControllerSerialLabel(value: String): Boolean {
        val aircraftComponent = listOf(
            "FLIGHT CONTROLLER", "AIRCRAFT", "DRONE", "CAMERA", "GIMBAL", "BATTERY",
            "ПОЛЕТН", "ПОЛЁТН", "ДРОН", "КАМЕР", "СТАБИЛИЗАТОР", "БАТАРЕ",
            "飞行器", "飞控", "相机", "云台", "电池"
        ).any(value::contains)
        if (aircraftComponent) return false

        val controller = listOf(
            "REMOTE CONTROLLER", "REMOTE CONTROL", "CONTROLLER", "RC ",
            "ПУЛЬТ", "КОНТРОЛЛЕР", "遥控器"
        ).any(value::contains)
        val serial = listOf(
            "SERIAL", "S/N", " SN", "SN ", "СЕРИЙН", "С/Н", "序列号", "SN码"
        ).any(value::contains)
        return controller && serial
    }

    private fun serialCandidates(value: String): Sequence<String> =
        candidate.findAll(value).map { it.value }

    private fun isSerial(value: String): Boolean {
        if (excludedPrefixes.containsMatchIn(value)) return false
        val hasDigit = value.any(Char::isDigit)
        val hasLetter = value.any(Char::isLetter)
        return hasDigit && (hasLetter || value.length >= 14)
    }
}

/**
 * Extracts the aircraft factory S/N from ordered DJI Fly accessibility labels.
 *
 * The passive DUML probe cannot be relied on: the frame carrying the serial is
 * pushed only while DJI Fly itself asks for it, so a probe run at any other
 * moment listens to a bus that never mentions the aircraft. The Information
 * screen always spells it out, which makes the screen the dependable source.
 */
internal object DjiFlyAircraftSerialExtractor {
    private val whitespace = Regex("\\s+")
    private val candidate = Regex("[A-Z0-9]{10,24}")
    private val modelCode = Regex("^(?:WA|WM)[0-9]{3}[A-Z]?$")

    fun find(labels: Collection<String>): String? {
        val normalized = labels.map(::normalize).filter(String::isNotEmpty)
        normalized.forEachIndexed { index, label ->
            if (!isAircraftSerialLabel(label)) return@forEachIndexed

            serialCandidates(label).firstOrNull(::isSerial)?.let { return it }
            for (offset in 1..2) {
                val nearby = normalized.getOrNull(index + offset) ?: break
                // DJI Fly leaves a component serial blank when the aircraft
                // does not publish it; the next label then belongs to another
                // component and its value must not be claimed as the aircraft's.
                if (isSerialLabel(nearby)) break
                serialCandidates(nearby).firstOrNull(::isSerial)?.let { return it }
            }
        }
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()

    private fun isAircraftSerialLabel(value: String): Boolean {
        val otherComponent = listOf(
            "FLIGHT CONTROLLER", "CAMERA", "GIMBAL", "BATTERY", "REMOTE CONTROL",
            "ПОЛЕТН", "ПОЛЁТН", "КАМЕР", "СТАБИЛИЗАТОР", "БАТАРЕ", "ПУЛЬТ",
            "飞控", "相机", "云台", "电池", "遥控器"
        ).any(value::contains)
        if (otherComponent) return false

        val aircraft = listOf("AIRCRAFT", "DRONE", "ДРОН", "飞行器").any(value::contains)
        return aircraft && isSerialLabel(value)
    }

    private fun isSerialLabel(value: String): Boolean = listOf(
        "SERIAL", "S/N", " SN", "SN ", "СЕРИЙН", "С/Н", "序列号", "SN码"
    ).any(value::contains)

    private fun serialCandidates(value: String): Sequence<String> =
        candidate.findAll(value).map { it.value }

    private fun isSerial(value: String): Boolean {
        if (modelCode.matches(value)) return false
        return value.any(Char::isDigit) && value.any(Char::isLetter)
    }
}

/**
 * Guards the stored aircraft S/N against the one just left behind.
 *
 * A swap of aircraft clears the serial, but the bus keeps repeating the
 * previous aircraft's frames for a while — live, a Lito X1 was plugged in and
 * the Mini 5 Pro serial was read back six seconds later. Refuse exactly that
 * number for a short guard window; the same aircraft coming back is read again
 * once the window passes.
 */
internal object AircraftSerialGuard {
    const val KEY_SERIAL = "aircraft_serial"
    private const val KEY_DROPPED = "aircraft_serial_dropped"
    private const val KEY_DROPPED_AT = "aircraft_serial_dropped_at"
    internal const val GUARD_MS = 60_000L

    fun accepts(prefs: android.content.SharedPreferences, serial: String, nowMs: Long): Boolean =
        accepts(
            dropped = prefs.getString(KEY_DROPPED, "").orEmpty(),
            droppedAtMs = prefs.getLong(KEY_DROPPED_AT, 0L),
            serial = serial,
            nowMs = nowMs
        )

    internal fun accepts(
        dropped: String,
        droppedAtMs: Long,
        serial: String,
        nowMs: Long
    ): Boolean {
        // The bus spells one serial two ways, so the guard has to recognise the
        // dropped aircraft in either of them.
        if (dropped.isEmpty() || !AircraftSerialForms.sameAircraft(dropped, serial)) return true
        return nowMs - droppedAtMs >= GUARD_MS
    }

    fun rememberDropped(
        editor: android.content.SharedPreferences.Editor,
        serial: String,
        nowMs: Long
    ) {
        editor.putString(KEY_DROPPED, serial)
            .putLong(KEY_DROPPED_AT, nowMs)
            .remove(KEY_SERIAL)
    }
}

internal data class ControllerSerialObservation(val serial: String, val source: String)

/** Reads the controller's Android factory serial without opening or changing any UI. */
internal object AutomaticControllerSerialReader {
    private val serialPattern = Regex("^[A-Z0-9]{10,24}$")
    private val rejected = setOf(
        "UNKNOWN",
        "0123456789ABCDEF",
        "1234567890ABCDEF",
        "0000000000000000"
    )

    @Suppress("DEPRECATION")
    fun read(): ControllerSerialObservation? = firstValid(
        listOf(
            Build.SERIAL.orEmpty() to "build_serial",
            readProperty("ro.serialno") to "getprop_ro_serialno",
            readProperty("ro.boot.serialno") to "getprop_ro_boot_serialno",
            readFile("/sys/class/android_usb/android0/iSerial") to "usb_gadget",
            readFile("/config/usb_gadget/g1/strings/0x409/serialnumber") to "usb_gadget",
            readFile("/sys/kernel/config/usb_gadget/g1/strings/0x409/serialnumber") to "usb_gadget"
        )
    )

    internal fun firstValid(candidates: Iterable<Pair<String, String>>): ControllerSerialObservation? {
        candidates.forEach { (rawValue, source) ->
            val value = Normalizer.normalize(rawValue, Normalizer.Form.NFKC)
                .trim()
                .uppercase(Locale.ROOT)
            if (
                serialPattern.matches(value) &&
                value !in rejected &&
                value.any(Char::isDigit) &&
                value.any(Char::isLetter) &&
                value.toSet().size > 2
            ) {
                return ControllerSerialObservation(value, source)
            }
        }
        return null
    }

    private fun readProperty(key: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) return ""
            process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
        } catch (_: Exception) {
            ""
        } finally {
            process?.destroy()
        }
    }

    private fun readFile(path: String): String = runCatching {
        File(path).bufferedReader().use { it.readLine().orEmpty() }
    }.getOrDefault("")
}

/** Starts one sparse background uploader whenever the app process is created. */
class FreeFccApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageStatistics.scheduleUpload(this)
    }
}

internal object UsageStatistics {
    private const val PREFS_NAME = "usage_statistics"
    private const val PREF_INSTALLATION_ID = "installation_id"
    private const val PREF_REPORT_SEQUENCE = "report_sequence"
    private const val PREF_PENDING_REPORT_PAYLOAD = "pending_report_payload"
    private const val PREF_LAST_SUCCESS_AT = "last_success_at"
    private const val PREF_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val PREF_CONTROLLER_SERIAL = "controller_serial"
    private const val PREF_CONTROLLER_SERIAL_SOURCE = "controller_serial_source"
    private const val PREF_CONTROLLER_SERIAL_PROBE_DONE = "controller_serial_probe_done"
    private const val COUNTER_PREFIX = "count."
    private const val UPLOAD_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_INTERVAL_MS = 60 * 60 * 1000L

    /** Floor between retries triggered by the link coming back. */
    private const val NETWORK_RETRY_MIN_INTERVAL_MS = 60 * 1000L

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_BYTES = 4_096
    private const val DJI_FLY_PACKAGE = "dji.go.v5"

    private val lock = Any()
    private val uploadBusy = AtomicBoolean(false)
    private val forcedUploadPending = AtomicBoolean(false)
    private val networkRestoredPending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FreeFCC-statistics").apply { isDaemon = true }
    }

    fun recordAction(context: Context, action: UsageAction) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = counterKey(BuildConfig.VERSION_NAME, action.wireName)
        synchronized(lock) {
            val current = prefs.getLong(key, 0L)
            prefs.edit().putLong(key, if (current == Long.MAX_VALUE) current else current + 1L).apply()
        }
        scheduleUpload(context)
    }

    fun captureControllerSerialFromUi(context: Context, labels: Collection<String>): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_CONTROLLER_SERIAL, "").orEmpty().isNotBlank()) return true
        val serial = DjiFlyControllerSerialExtractor.find(labels) ?: return false
        prefs.edit()
            .putString(PREF_CONTROLLER_SERIAL, serial)
            .putString(PREF_CONTROLLER_SERIAL_SOURCE, "dji_fly_ui")
            .apply()
        scheduleUpload(context, force = true)
        return true
    }

    /**
     * Stores the aircraft S/N read off the DJI Fly screen. Unlike the
     * controller serial this must follow a swap of aircraft, so an existing
     * value is replaced rather than kept.
     */
    fun captureAircraftSerialFromUi(context: Context, labels: Collection<String>): Boolean {
        val serial = DjiFlyAircraftSerialExtractor.find(labels) ?: return false
        val prefs = context.applicationContext.getSharedPreferences("freefcc", Context.MODE_PRIVATE)
        if (prefs.getString(AircraftSerialGuard.KEY_SERIAL, "").orEmpty() == serial) return true
        // The screen names the aircraft in front of the user, so it outranks
        // the guard against the previous aircraft's lingering bus frames.
        prefs.edit().putString(AircraftSerialGuard.KEY_SERIAL, serial).apply()
        scheduleUpload(context, force = true)
        return true
    }

    /**
     * The link came back. Retries a report that is due and whose last attempt
     * failed; a report that is not due stays not due, so reconnecting cannot
     * turn into a stream of reports.
     */
    fun onNetworkAvailable(context: Context) {
        scheduleUpload(context, networkRestored = true)
    }

    fun scheduleUpload(
        context: Context,
        force: Boolean = false,
        networkRestored: Boolean = false
    ) {
        val endpoints = endpoints(
            BuildConfig.STATISTICS_ENDPOINT,
            BuildConfig.STATISTICS_ENDPOINT_SECONDARY
        )
        if (endpoints.isEmpty()) return
        if (force) forcedUploadPending.set(true)
        // Sticky, like `force`: a link coming back while an offline attempt is
        // still running would otherwise be dropped on the floor — the very
        // moment the retry exists for.
        if (networkRestored) networkRestoredPending.set(true)
        if (!uploadBusy.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        executor.execute {
            try {
                val automaticIdentityChanged = captureAutomaticControllerSerial(appContext)
                val shouldForce = forcedUploadPending.getAndSet(false) || automaticIdentityChanged
                val linkReturned = networkRestoredPending.getAndSet(false)
                try {
                    uploadIfDue(appContext, endpoints, shouldForce, linkReturned)
                } catch (_: Exception) {
                    // Statistics must never affect application behavior.
                }
            } finally {
                uploadBusy.set(false)
                if (forcedUploadPending.get()) {
                    scheduleUpload(appContext)
                } else if (networkRestoredPending.get()) {
                    scheduleUpload(appContext, networkRestored = true)
                }
            }
        }
    }

    /**
     * Собранные адреса приёмников статистики в порядке отправки. Пустой и
     * не-HTTPS адрес отбрасывается, повтор одного адреса дважды — тоже: тогда
     * один и тот же отчёт ушёл бы на один сервер два раза.
     */
    internal fun endpoints(primary: String, secondary: String): List<String> =
        listOf(primary, secondary)
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .distinct()

    internal fun deliveryComplete(results: List<Boolean>): Boolean =
        results.isNotEmpty() && results.all { it }

    internal fun payloadForAttempt(pendingPayload: String, freshPayload: String): String =
        pendingPayload.ifEmpty { freshPayload }

    private fun uploadIfDue(
        context: Context,
        endpoints: List<String>,
        force: Boolean,
        networkRestored: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val pendingPayload = prefs.getString(PREF_PENDING_REPORT_PAYLOAD, "").orEmpty()
        if (!shouldUpload(
                now = now,
                lastSuccessAt = prefs.getLong(PREF_LAST_SUCCESS_AT, 0L),
                lastAttemptAt = prefs.getLong(PREF_LAST_ATTEMPT_AT, 0L),
                force = force,
                networkRestored = networkRestored,
                pendingReport = pendingPayload.isNotEmpty()
            )
        ) return
        prefs.edit().putLong(PREF_LAST_ATTEMPT_AT, now).apply()

        val hadPendingPayload = pendingPayload.isNotEmpty()
        val payload = payloadForAttempt(
            pendingPayload = pendingPayload,
            freshPayload = if (hadPendingPayload) "" else buildPayload(context)
        )
        if (!hadPendingPayload) {
            // Persist before the first request: retrying a rebuilt body with the
            // same sequence would correctly be rejected as replay_conflict.
            if (!prefs.edit().putString(PREF_PENDING_REPORT_PAYLOAD, payload).commit()) return
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        // Один и тот же отчёт с одним report_sequence уходит на все адреса,
        // и остаётся сохранённым до подтверждения от каждого приёмника.
        val results = endpoints.map { endpoint -> post(endpoint, payloadBytes) }
        if (deliveryComplete(results)) {
            prefs.edit()
                .putLong(PREF_LAST_SUCCESS_AT, now)
                .putLong(PREF_REPORT_SEQUENCE, prefs.getLong(PREF_REPORT_SEQUENCE, 0L) + 1L)
                .remove(PREF_PENDING_REPORT_PAYLOAD)
                .apply()
            // Пока старый отчёт догонял второй сервер, состояние могло
            // измениться. Следом один раз отправляем уже актуальный снимок.
            if (hadPendingPayload) forcedUploadPending.set(true)
        }
    }

    /** Отправляет отчёт на один адрес. `true` — сервер его принял. */
    private fun post(endpoint: String, payloadBytes: ByteArray): Boolean {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(payloadBytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val response = if (status in 200..299) connection.inputStream else connection.errorStream
            response?.use { stream ->
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                stream.read(buffer)
            }
            return status in 200..299
        } catch (_: Exception) {
            // Недоступность одного приёмника не должна мешать остальным.
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildPayload(context: Context): String {
        val statsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appPrefs = context.getSharedPreferences("freefcc", Context.MODE_PRIVATE)
        val packageInfo = djiFlyPackageInfo(context)
        val selectedMode = AutoFccSelection.load(context)?.wireValue ?: "off"
        val aircraftIdentity = aircraftIdentitySnapshot(appPrefs)
        val payload = UsageStatisticsPayload(
            installationId = installationId(statsPrefs),
            reportSequence = statsPrefs.getLong(PREF_REPORT_SEQUENCE, 0L) + 1L,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            controllerSerial = statsPrefs.getString(PREF_CONTROLLER_SERIAL, "").orEmpty(),
            controllerSerialSource = statsPrefs
                .getString(PREF_CONTROLLER_SERIAL_SOURCE, "")
                .orEmpty(),
            controllerDevice = Build.DEVICE.orEmpty(),
            controllerModel = Build.MODEL.orEmpty(),
            djiFlyVersionName = packageInfo?.versionName.orEmpty(),
            djiFlyVersionCode = packageInfo?.longVersionCode,
            aircraftSerial = aircraftIdentity.serial,
            aircraftModelCode = aircraftIdentity.modelCode,
            aircraftModelName = aircraftIdentity.modelName,
            settings = linkedMapOf(
                "auto_fcc_mode" to selectedMode,
                "home_point_accessibility_enabled" to
                    FccKeepaliveService.isDjiFlyTextAccessEnabled(context),
                "lan_control_enabled" to appPrefs.getBoolean("lan_log_enabled", false)
            ),
            usageByAppVersion = usageCounters(statsPrefs.all)
        )
        return UsageStatisticsJson.encode(payload)
    }

    internal fun aircraftIdentitySnapshot(
        prefs: android.content.SharedPreferences
    ): AircraftStatisticsIdentity = AircraftStatisticsIdentity(
        serial = prefs.getString(AircraftSerialGuard.KEY_SERIAL, "").orEmpty(),
        modelCode = prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, "").orEmpty(),
        modelName = prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_NAME, "").orEmpty()
    )

    private fun installationId(prefs: android.content.SharedPreferences): String {
        prefs.getString(PREF_INSTALLATION_ID, "")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_INSTALLATION_ID, it).apply()
        }
    }

    private fun djiFlyPackageInfo(context: Context): PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(DJI_FLY_PACKAGE, 0) }.getOrNull()

    private fun captureAutomaticControllerSerial(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!shouldProbeControllerSerial(
                cachedSerial = prefs.getString(PREF_CONTROLLER_SERIAL, "").orEmpty(),
                probeDone = prefs.getBoolean(PREF_CONTROLLER_SERIAL_PROBE_DONE, false)
            )
        ) return false
        prefs.edit().putBoolean(PREF_CONTROLLER_SERIAL_PROBE_DONE, true).apply()
        val observation = AutomaticControllerSerialReader.read() ?: return false
        prefs.edit()
            .putString(PREF_CONTROLLER_SERIAL, observation.serial)
            .putString(PREF_CONTROLLER_SERIAL_SOURCE, observation.source)
            .apply()
        return true
    }

    internal fun shouldProbeControllerSerial(cachedSerial: String, probeDone: Boolean): Boolean =
        cachedSerial.isBlank() && !probeDone

    internal fun counterKey(appVersion: String, action: String): String =
        "$COUNTER_PREFIX$appVersion.$action"

    internal fun shouldUpload(
        now: Long,
        lastSuccessAt: Long,
        lastAttemptAt: Long,
        force: Boolean,
        networkRestored: Boolean = false,
        pendingReport: Boolean = false
    ): Boolean {
        if (force) return true
        // One report a day, unchanged. This is the cadence the app documents
        // and the only thing that bounds how often anything is sent.
        if (!pendingReport && isWithin(now, lastSuccessAt, UPLOAD_INTERVAL_MS)) return false
        // An attempt newer than the last success is an attempt that failed.
        // A link coming back is a new chance at it, and waiting out the hourly
        // backoff would waste that chance: the controller is powered for one
        // flight, and by the time the hour is up it is usually switched off.
        // This cannot make anything send more than once a day — the cadence
        // above is checked first.
        // A link that flaps must not turn into an attempt per flap: each one
        // costs a connect timeout against every endpoint. One a minute is
        // enough to catch a link that came back to stay.
        if (networkRestored && lastAttemptAt > lastSuccessAt) {
            return !isWithin(now, lastAttemptAt, NETWORK_RETRY_MIN_INTERVAL_MS)
        }
        if (isWithin(now, lastAttemptAt, RETRY_INTERVAL_MS)) return false
        return true
    }

    private fun isWithin(now: Long, timestamp: Long, interval: Long): Boolean =
        timestamp > 0L && now >= timestamp && now - timestamp < interval

    internal fun usageCounters(values: Map<String, *>): Map<String, Map<String, Long>> {
        val result = sortedMapOf<String, MutableMap<String, Long>>()
        values.forEach { (key, rawCount) ->
            if (!key.startsWith(COUNTER_PREFIX)) return@forEach
            val suffix = key.removePrefix(COUNTER_PREFIX)
            val separator = suffix.lastIndexOf('.')
            if (separator <= 0 || separator == suffix.lastIndex) return@forEach
            val version = suffix.substring(0, separator)
            val action = suffix.substring(separator + 1)
            val count = (rawCount as? Number)?.toLong()?.takeIf { it >= 0L } ?: return@forEach
            result.getOrPut(version) { sortedMapOf() }[action] = count
        }
        return result
    }
}
