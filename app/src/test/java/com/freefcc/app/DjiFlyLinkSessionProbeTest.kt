package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiFlyLinkUiClassifierTest {
    @Test
    fun russianFlightModeMeansConnected() {
        assertEquals(
            DjiFlyLinkUiState.CONNECTED,
            DjiFlyLinkUiClassifier.classify(listOf("FPV Back", "Режим N", "Top Bar Rc Signal"))
        )
    }

    @Test
    fun explicitDisconnectOutranksAStaleFlightMode() {
        assertEquals(
            DjiFlyLinkUiState.DISCONNECTED,
            DjiFlyLinkUiClassifier.classify(
                listOf("Режим N", "Пульт не подключен к мобильному устройству")
            )
        )
    }

    @Test
    fun cameraNaDoesNotOverrideAConnectedFlightMode() {
        assertEquals(
            DjiFlyLinkUiState.CONNECTED,
            DjiFlyLinkUiClassifier.classify(listOf("Режим N", "Camera Mode Switch", "N/A"))
        )
    }

    @Test
    fun naMeansTheDjiFlyApplicationLinkIsDisconnected() {
        assertEquals(
            DjiFlyLinkUiState.DISCONNECTED,
            DjiFlyLinkUiClassifier.classify(listOf("N/A"))
        )
    }
}

class DjiFlyLinkSessionProbeGateTest {
    @Test
    fun anUnstartedProbeCanRetryOnceThePortIsFree() {
        val gate = DjiFlyLinkSessionProbeGate()
        val token = gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L)!!
        gate.releaseUnstartedProbe(token)
        val retry = gate.onUiState(DjiFlyLinkUiState.CONNECTED, 2_000L)!!
        assertTrue(gate.isCurrentProbe(retry))
        assertFalse(gate.isCurrentProbe(token))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 3_000L))
    }

    @Test
    fun anOldWorkerCannotReleaseTheNewAircraftProbe() {
        val gate = DjiFlyLinkSessionProbeGate()
        val old = gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L)!!
        gate.rearmForConfirmedAircraftChange()
        val current = gate.onUiState(DjiFlyLinkUiState.CONNECTED, 2_000L)!!
        gate.releaseUnstartedProbe(old)
        assertTrue(gate.isCurrentProbe(current))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 3_000L))
    }

    @Test
    fun probesOnlyOnceWhileTheAircraftStaysConnected() {
        val gate = DjiFlyLinkSessionProbeGate()

        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 121_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 601_000L))
    }

    @Test
    fun aBriefProbeInducedDropDoesNotRearm() {
        val gate = DjiFlyLinkSessionProbeGate(stableDisconnectMs = 10_000L)

        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.DISCONNECTED, 2_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 3_000L))
    }

    @Test
    fun naMustLastTenSecondsBeforeItRearmsTheProbe() {
        val gate = DjiFlyLinkSessionProbeGate(stableDisconnectMs = 10_000L)

        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.DISCONNECTED, 2_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 11_999L))

        assertNull(gate.onUiState(DjiFlyLinkUiState.DISCONNECTED, 20_000L))
        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 30_000L))
    }

    @Test
    fun aRealDisconnectAllowsOneProbeOnTheNextConnection() {
        val gate = DjiFlyLinkSessionProbeGate(stableDisconnectMs = 10_000L)

        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.DISCONNECTED, 5_000L))
        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 20_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 200_000L))
    }

    @Test
    fun aConfirmedAircraftChangeRearmsExactlyOneProbe() {
        val gate = DjiFlyLinkSessionProbeGate()

        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 1_000L))
        gate.rearmForConfirmedAircraftChange()
        assertNotNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 2_000L))
        assertNull(gate.onUiState(DjiFlyLinkUiState.CONNECTED, 3_000L))
    }
}
