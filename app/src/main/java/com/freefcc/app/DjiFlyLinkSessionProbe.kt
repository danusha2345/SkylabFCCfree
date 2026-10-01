package com.freefcc.app

import java.text.Normalizer
import java.util.Locale

internal enum class DjiFlyLinkUiState {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN
}

/** Classifies only explicit DJI Fly link markers; unrelated screen text stays unknown. */
internal object DjiFlyLinkUiClassifier {
    private val whitespace = Regex("\\s+")
    private val flightMode = Regex(
        "^(?:режим\\s*[npsc]|mode\\s*[npsc]|[npsc]\\s*mode|模式\\s*[npsc])$",
        RegexOption.IGNORE_CASE
    )
    private val disconnectedMarkers = listOf(
        "пульт не подключен",
        "remote controller not connected",
        "controller disconnected",
        "aircraft disconnected",
        "drone disconnected",
        "遥控器未连接",
        "飞行器未连接"
    )

    fun classify(labels: Collection<String>): DjiFlyLinkUiState {
        val normalized = labels.map(::normalize).filter(String::isNotEmpty)
        if (normalized.any { value -> disconnectedMarkers.any(value::contains) }) {
            return DjiFlyLinkUiState.DISCONNECTED
        }
        if (normalized.any(flightMode::matches)) return DjiFlyLinkUiState.CONNECTED
        // `N/A` means DJI Fly has lost its application-level aircraft link.
        // DUML traffic may still continue underneath. The gate below requires
        // this state to last for ten seconds before it can re-arm a probe.
        if (normalized.any { it == "n/a" }) return DjiFlyLinkUiState.DISCONNECTED
        return DjiFlyLinkUiState.UNKNOWN
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
}

/** Allows one port-40007 identity probe per real aircraft link session. */
internal class DjiFlyLinkSessionProbeGate(
    private val stableDisconnectMs: Long = 10_000L
) {
    private var generation = 0L
    private var probeToken: Long? = null
    private var disconnectedAtMs: Long? = null

    @Synchronized
    fun onUiState(state: DjiFlyLinkUiState, nowMs: Long): Long? = when (state) {
        DjiFlyLinkUiState.UNKNOWN -> null
        DjiFlyLinkUiState.DISCONNECTED -> {
            if (disconnectedAtMs == null) disconnectedAtMs = nowMs
            null
        }
        DjiFlyLinkUiState.CONNECTED -> {
            val disconnectedAt = disconnectedAtMs
            if (disconnectedAt != null && nowMs - disconnectedAt >= stableDisconnectMs) {
                probeToken = null
            }
            disconnectedAtMs = null
            if (probeToken != null) null else (++generation).also { probeToken = it }
        }
    }

    /** Занятый порт не расходует попытку; старый worker не отменяет новый token. */
    @Synchronized
    fun releaseUnstartedProbe(token: Long) {
        if (probeToken == token) probeToken = null
    }

    @Synchronized
    fun isCurrentProbe(token: Long): Boolean = probeToken == token

    /** A confirmed screen-model change is a new aircraft even without another UI disconnect. */
    @Synchronized
    fun rearmForConfirmedAircraftChange() {
        probeToken = null
        disconnectedAtMs = null
    }
}
