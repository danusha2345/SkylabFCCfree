package com.freefcc.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object DjiFlyHomePointMatcher {
    private val whitespace = Regex("\\s+")

    fun normalize(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
            .trimEnd('.', '!', '?', '\u3002', '\uff01', '\uff1f')

    fun matches(value: CharSequence, phrases: Set<String>): Boolean =
        normalize(value) in phrases
}

private data class DjiFlyPhraseCatalog(
    val phrases: Set<String>,
    val localeCount: Int
)

internal object AircraftModelProbe {
    private const val CAPTURE_DURATION_MS = 1_500
    private const val CAPTURE_MAX_FRAMES = 128

    fun capture(preferredPort: Int? = null): AircraftModelIdentity? {
        val ports = buildList {
            preferredPort?.let(::add)
            addAll(DumlTransport.AIRCRAFT_IDENTITY_PORTS)
        }.distinct()

        var modelCode = ""
        var modelName = ""
        val transport = DumlTransport()
        for (port in ports) {
            var port40007Lease: Port40007Lock.Lease? = null
            var sessionLease: DumlPortSessionLock.Lease? = null
            try {
                if (port == DumlTransport.PORT_LED) {
                    port40007Lease = Port40007Lock.acquireForExternalBlocking(timeoutMs = 500)
                    if (port40007Lease == null) continue
                }
                sessionLease = DumlPortSessionLock.tryBegin(port) ?: continue
                val observed = DumlTransport.extractAircraftModelIdentity(
                    transport.captureFrames(
                        durationMs = CAPTURE_DURATION_MS,
                        maxFrames = CAPTURE_MAX_FRAMES,
                        port = port
                    )
                ) ?: continue
                if (observed.modelCode.isNotEmpty()) modelCode = observed.modelCode
                if (observed.modelName.isNotEmpty()) modelName = observed.modelName
                if (modelCode.isNotEmpty() && modelName.isNotEmpty()) break
            } finally {
                sessionLease?.close()
                port40007Lease?.let(Port40007Lock::releaseFromLed)
            }
        }

        return if (modelCode.isEmpty() && modelName.isEmpty()) {
            null
        } else {
            AircraftModelIdentity(modelCode, modelName)
        }
    }
}

/**
 * Reads text emitted by the original DJI Fly app. The accessibility service
 * opens port 40007 once per stable aircraft link to identify that aircraft.
 * Home Point and elapsed time never trigger another identity read.
 */
class DjiFlyAccessibilityService : AccessibilityService() {

    companion object {
        private const val DJI_FLY_PACKAGE = AutoFccPackagePolicy.STOCK_FLY_PACKAGE
        private const val VITYA_PACKAGE = AutoFccPackagePolicy.VITYA_PACKAGE
        private const val DJI_PILOT_2_PACKAGE = "com.dji.industry.pilot"
        private val MODEL_CAPTURE_PACKAGES = setOf(DJI_FLY_PACKAGE, DJI_PILOT_2_PACKAGE)
        private const val MODEL_UI_REWRITE_MS = 60_000L
        private val AIRCRAFT_MODEL_CODE_PATTERN = Regex("^W[AM][0-9]{3}[0-9A-Z]?$")
        private val HOME_POINT_RESOURCE_NAMES = listOf(
            "fpv_tips_smart_rth_homepoint_update",
            "fpv_setting_shortcut_update_return_point_succeed_toast",
            "fpv_setting_safe_return_point_update_window_current_beacon_location_note",
            "fpv_setting_safe_return_point_update_window_current_control_location_note",
            "fpv_setting_safe_return_point_update_window_current_drone_location_note",
            "fpv_tips_target_location_lost_rth"
        )
    }

    private var homePointPhrases: Set<String> = emptySet()
    private var lastLoggedSignature = ""
    private var lastLoggedAtMs = 0L
    private var lastUiSnapshot = ""
    private var lastUiScanAtMs = 0L
    private var lastUiHomePointMatch = ""
    private var lastUiHomePointMatchAtMs = 0L
    private val serialCaptureBusy = AtomicBoolean(false)
    private val linkSessionProbeGate = DjiFlyLinkSessionProbeGate()
    private val modelObservationGate = AircraftModelObservationGate()
    private var lastFlyPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val catalog = loadPhraseCatalog()
        homePointPhrases = catalog.phrases
        FccViewModel.logServiceEvent(
            "DJI FLY ACCESSIBILITY TEST: connected; " +
                "phrases=${catalog.phrases.size} locales=${catalog.localeCount}; " +
                "one identity probe per stable aircraft link; no timed or Home Point re-reads"
        )
        rootInActiveWindow?.packageName?.toString()?.let(::syncAutoFccOwner)
        AppForegroundService.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePackage = event?.packageName?.toString() ?: return
        syncAutoFccOwner(sourcePackage)
        if (sourcePackage == VITYA_PACKAGE) return
        if (sourcePackage !in MODEL_CAPTURE_PACKAGES) return

        val values = buildSet {
            event.text.filterNotNull().forEach { if (it.isNotBlank()) add(it.toString()) }
            event.contentDescription?.takeIf { it.isNotBlank() }?.let { add(it.toString()) }
        }

        // Home Point first, always. It only signals the FCC writer; identity
        // is triggered separately by a newly observed stable aircraft link.
        if (sourcePackage != DJI_FLY_PACKAGE) {
            // The DJI app prints both the product code and the commercial name,
            // so its screen is the only source used by this service.
            captureAircraftModelFromUi("event", values)
            return
        }
        // Handle an event-only Home Point before scanning the full snapshot.
        values.firstOrNull { DjiFlyHomePointMatcher.matches(it, homePointPhrases) }
            ?.let { handleHomePointMatch("event", it) }

        logVisibleUiSnapshot()

        values.forEach { value ->
            val normalized = DjiFlyHomePointMatcher.normalize(value)
            if (normalized.isEmpty()) return@forEach
            val matched = DjiFlyHomePointMatcher.matches(value, homePointPhrases)
            val signature = "${event.eventType}:$normalized:$matched"
            val now = System.currentTimeMillis()
            if (signature == lastLoggedSignature && now - lastLoggedAtMs < 1_000L) return@forEach
            lastLoggedSignature = signature
            lastLoggedAtMs = now

            val safeText = value.replace(Regex("\\s+"), " ").take(240)
            FccViewModel.logServiceEvent(
                "DJI FLY ACCESSIBILITY EVENT: " +
                    "type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "home_point_match=$matched text=$safeText"
            )
            // Already handled above, before anything could take the port.
        }
        captureAircraftModelFromUi("event", values)
    }

    private fun syncAutoFccOwner(sourcePackage: String) {
        if (sourcePackage != DJI_FLY_PACKAGE && sourcePackage != VITYA_PACKAGE) return
        val action = AutoFccPackagePolicy.action(lastFlyPackage, sourcePackage)
        lastFlyPackage = sourcePackage
        when (action) {
            AutoFccPackageAction.PAUSE_FOR_VITYA -> {
                if (FccKeepaliveService.isRunningFlagSet(this)) {
                    FccKeepaliveService.stop(this, clearSelection = false)
                }
                FccViewModel.logServiceEvent(
                    "AUTO FCC OWNER: paused standalone controller for Vitya (dji.go.v6)"
                )
            }
            AutoFccPackageAction.RESUME_FOR_STOCK_FLY -> {
                val started = FccKeepaliveService.startSelectedMode(this)
                FccViewModel.logServiceEvent(
                    "AUTO FCC OWNER: stock DJI Fly active; standalone controller armed=$started"
                )
            }
            AutoFccPackageAction.NONE -> Unit
        }
    }

    /** Reads the model straight off the DJI app screen without opening DUML. */
    private fun captureAircraftModelFromUi(source: String, texts: Collection<String>): Boolean {
        if (texts.isEmpty()) return false
        val match = AircraftModelCatalog.findOnScreen(texts) ?: return false

        val prefs = getSharedPreferences("freefcc", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedCode = prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, "").orEmpty()
        val storedName = prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_NAME, "").orEmpty()
        val code = AircraftModelCatalog.codeFor(match.name, match.code, storedName, storedCode)
        val name = match.name.ifEmpty { storedName.takeIf { code == storedCode }.orEmpty() }
        if (!modelObservationGate.accepts(storedName, name, now)) return true
        val unchanged = code == storedCode && name == storedName
        // A confirmed swap to another aircraft invalidates the stored S/N: it
        // belongs to the previous one and would otherwise be reported next to
        // the new model. One sighting is not enough — DJI Fly screens list
        // other aircraft too, and a name flickering past would drop a serial
        // that was read correctly. The observation gate requires one second.
        val nameChanged = storedName.isNotEmpty() && name.isNotEmpty() && name != storedName
        val aircraftSwapped = nameChanged
        val lastWriteAt = prefs.getLong(FccViewModel.PREF_AIRCRAFT_MODEL_AT, 0L)
        if (unchanged && now - lastWriteAt < MODEL_UI_REWRITE_MS) {
            return true
        }

        prefs.edit().apply {
            if (code.isEmpty()) {
                remove(FccViewModel.PREF_AIRCRAFT_MODEL_CODE)
            } else {
                putString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, code)
            }
            if (name.isEmpty()) {
                remove(FccViewModel.PREF_AIRCRAFT_MODEL_NAME)
            } else {
                putString(FccViewModel.PREF_AIRCRAFT_MODEL_NAME, name)
            }
            if (aircraftSwapped) {
                AircraftSerialGuard.rememberDropped(
                    this,
                    prefs.getString(AircraftSerialGuard.KEY_SERIAL, "").orEmpty(),
                    now
                )
            }
            putString(
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE,
                FccViewModel.AIRCRAFT_MODEL_SOURCE_UI
            )
            putLong(FccViewModel.PREF_AIRCRAFT_MODEL_AT, now)
        }.apply()
        if (aircraftSwapped) {
            // The screen can prove a swap before the new aircraft publishes
            // its serial. Do not keep GPS/LED addresses from the old aircraft.
            ParameterAddress.forgetAllConfirmed()
            // The next connected UI snapshot performs one bounded serial query
            // for the new aircraft. No timer or Home Point event can re-arm it.
            linkSessionProbeGate.rearmForConfirmedAircraftChange()
        }
        if (!unchanged) UsageStatistics.scheduleUpload(this, force = true)
        if (!unchanged) {
            FccViewModel.logServiceEvent(
                    "Aircraft model read from the DJI app screen: source=$source " +
                        "code=${code.ifEmpty { "unknown" }} " +
                        "name=${name.ifEmpty { "unknown" }}"
            )
        }
        return true
    }

    /** Reads identity once when DJI Fly reports a new, stable aircraft link. */
    private fun captureAircraftIdentityOnLink(): Boolean {
        val prefs = getSharedPreferences("freefcc", Context.MODE_PRIVATE)
        val stored = prefs.getString(AircraftSerialGuard.KEY_SERIAL, "").orEmpty()
        if (!serialCaptureBusy.compareAndSet(false, true)) return false

        thread(name = "FreeFCC-aircraft-serial", isDaemon = true) {
            var port40007Lease: Port40007Lock.Lease? = null
            var sessionLease: DumlPortSessionLock.Lease? = null
            try {
                if (HardwareLock.busy.value) return@thread
                port40007Lease = Port40007Lock.acquireForExternalBlocking(timeoutMs = 500)
                    ?: return@thread
                sessionLease = DumlPortSessionLock.tryBegin(DumlTransport.PORT_LED)
                    ?: return@thread
                val transport = DumlTransport()
                // Ask for the serial rather than wait for one to be broadcast.
                // Listening holds 40007 for the whole window and DJI Fly loses
                // the aircraft while it is held; the query returns as soon as
                // the aircraft answers. It also reaches aircraft that never put
                // the serial on the bus by themselves — Lito X1 only pushes it
                // while its Information screen is open, so a listening window
                // there burned a full minute for nothing.
                val queriedSerial = AircraftSerialQueryRunner.read(
                    transport,
                    attempts = AircraftSerialQueryRunner.LINK_SESSION_ATTEMPTS
                )
                // Model discovery stays screen-only. A passive 40007 listen
                // would add another 1.5 seconds after the two bounded requests.
                if (queriedSerial.isEmpty()) return@thread
                val serial = queriedSerial
                val observedAtMs = System.currentTimeMillis()
                val acceptedSerial = serial.takeIf {
                    it.isNotEmpty() &&
                        !AIRCRAFT_MODEL_CODE_PATTERN.matches(it) &&
                        it != stored &&
                        AircraftSerialGuard.accepts(prefs, it, observedAtMs)
                }
                val update = AircraftIdentityPreferences.updateFromDuml(
                    prefs = prefs,
                    observedModel = null,
                    observedSerial = acceptedSerial,
                    nowMs = observedAtMs
                )
                if (update.modelChanged) {
                    FccViewModel.logServiceEvent(
                        "Aircraft model read on link: " +
                            "code=${update.currentModel.modelCode.ifEmpty { "unknown" }} " +
                            "name=${update.currentModel.modelName.ifEmpty { "unknown" }} " +
                            "source=${FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML}"
                    )
                }
                if (update.serialChanged && update.currentSerial.isEmpty()) {
                    FccViewModel.logServiceEvent(
                        "Aircraft S/N cleared after model-code change: ${update.previousSerial}"
                    )
                }
                if (update.changed) {
                    UsageStatistics.scheduleUpload(this, force = true)
                }
                // A model code is not a serial. A miss waits for the next real
                // disconnect/reconnect rather than reopening port 40007.
                if (serial.isEmpty() || AIRCRAFT_MODEL_CODE_PATTERN.matches(serial)) return@thread
                if (acceptedSerial == null) {
                    return@thread
                }
                if (!update.serialChanged) return@thread
                FccViewModel.logServiceEvent(
                    if (update.previousSerial.isEmpty()) {
                        "Aircraft S/N read on link: ${update.currentSerial}"
                    } else {
                        "Aircraft S/N replaced from the bus: " +
                            "${update.previousSerial} -> ${update.currentSerial}"
                    }
                )
            } finally {
                sessionLease?.close()
                port40007Lease?.close()
                serialCaptureBusy.set(false)
            }
        }
        return true
    }

    private fun logVisibleUiSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastUiScanAtMs < 1_000L) return
        lastUiScanAtMs = now

        val root = rootInActiveWindow ?: return
        val labels = collectVisibleLabels(root)
        if (labels.isEmpty()) return
        val identityProbeDue = linkSessionProbeGate.onUiState(
            DjiFlyLinkUiClassifier.classify(labels),
            now
        )

        val homePointText = labels.firstOrNull { value ->
            DjiFlyHomePointMatcher.matches(value, homePointPhrases)
        }
        val snapshot = labels.joinToString(" | ").take(1_500)
        val snapshotChanged = snapshot != lastUiSnapshot
        if (snapshotChanged) {
            lastUiSnapshot = snapshot
            FccViewModel.logServiceEvent(
                "DJI FLY ACCESSIBILITY UI: home_point_match=${homePointText != null} text=$snapshot"
            )
        }
        // Home Point only signals the FCC writer. It never schedules identity
        // reads or opens port 40007.
        if (snapshotChanged && homePointText != null) {
            val normalized = DjiFlyHomePointMatcher.normalize(homePointText)
            if (normalized != lastUiHomePointMatch || now - lastUiHomePointMatchAtMs >= 10_000L) {
                lastUiHomePointMatch = normalized
                lastUiHomePointMatchAtMs = now
                handleHomePointMatch("visible_ui", homePointText)
            }
        }

        UsageStatistics.captureControllerSerialFromUi(this, labels)
        // Accessibility is screen-only. Port 40007 is reserved for explicit
        // link-session identity and manual diagnostics, never for a timer or
        // Home Point follow-up.
        captureAircraftModelFromUi("visible_ui", labels)
        // Model first: a confirmed swap clears the previous S/N, then an S/N
        // visible on this same screen can immediately populate the new one.
        val serialReadFromUi = UsageStatistics.captureAircraftSerialFromUi(this, labels)
        if (identityProbeDue && !serialReadFromUi) {
            FccViewModel.logServiceEvent(
                "Aircraft link connected: starting one identity probe on port 40007"
            )
            captureAircraftIdentityOnLink()
        }
    }

    private fun handleHomePointMatch(source: String, value: CharSequence) {
        val accepted = FccKeepaliveService.notifyHomePointDetected()
        FccViewModel.logServiceEvent(
            "DJI FLY ACCESSIBILITY TEST: HOME POINT MATCH source=$source " +
                "auto_fcc_trigger_accepted=$accepted " +
                "text=${value.toString().replace(Regex("\\s+"), " ").take(240)}"
        )
    }

    private fun collectVisibleLabels(root: AccessibilityNodeInfo): Set<String> {
        val labels = linkedSetOf<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 300 && labels.size < 80) {
            val node = pending.removeFirst()
            visited += 1
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return labels
    }

    override fun onInterrupt() {
        FccViewModel.logServiceEvent("DJI FLY ACCESSIBILITY TEST: interrupted")
    }

    @SuppressLint("AppBundleLocaleChanges", "DiscouragedApi")
    private fun loadPhraseCatalog(): DjiFlyPhraseCatalog {
        val packageContext = try {
            createPackageContext(DJI_FLY_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            FccViewModel.logServiceEvent(
                "DJI FLY ACCESSIBILITY TEST: DJI Fly resources unavailable: ${e.message}"
            )
            return DjiFlyPhraseCatalog(emptySet(), 0)
        }

        val baseResources = packageContext.resources
        val localeTags = buildSet {
            baseResources.assets.locales
                .mapNotNull { it.takeIf(String::isNotBlank) }
                .forEach(::add)
            baseResources.configuration.locales.let { locales ->
                for (index in 0 until locales.size()) add(locales[index].toLanguageTag())
            }
            add(Locale.ENGLISH.toLanguageTag())
        }

        val phrases = buildSet {
            localeTags.forEach { languageTag ->
                val locale = Locale.forLanguageTag(languageTag)
                val configuration = Configuration(baseResources.configuration).apply {
                    setLocale(locale)
                }
                val localizedResources = packageContext
                    .createConfigurationContext(configuration)
                    .resources
                HOME_POINT_RESOURCE_NAMES.forEach { name ->
                    val id = localizedResources.getIdentifier(name, "string", DJI_FLY_PACKAGE)
                    if (id != 0) {
                        runCatching { localizedResources.getText(id) }
                            .getOrNull()
                            ?.let(DjiFlyHomePointMatcher::normalize)
                            ?.takeIf(String::isNotEmpty)
                            ?.let(::add)
                    }
                }
            }
        }
        return DjiFlyPhraseCatalog(phrases, localeTags.size)
    }
}
