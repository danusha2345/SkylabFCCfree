package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLedReadGateTest {
    @Test
    fun contentionAllowsOneRetryButActualReadCompletesGate() {
        val gate = StartupLedReadGate(maxAcquireAttempts = 2)

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.finish(wireAttempted = false))

        assertTrue(gate.tryBegin())
        assertFalse(gate.finish(wireAttempted = true))
        assertFalse(gate.tryBegin())
    }

    @Test
    fun twoAcquisitionFailuresExhaustGateWithoutPolling() {
        val gate = StartupLedReadGate(maxAcquireAttempts = 2)

        assertTrue(gate.tryBegin())
        assertTrue(gate.finish(wireAttempted = false))
        assertTrue(gate.tryBegin())
        assertFalse(gate.finish(wireAttempted = false))
        assertFalse(gate.tryBegin())
    }

    @Test
    fun disablingBeforeReadRejectsStartupProbe() {
        val gate = StartupLedReadGate()

        gate.disable()

        assertFalse(gate.tryBegin())
    }

    @Test
    fun disablingAnInflightReadPreventsItsDelayedRetry() {
        val gate = StartupLedReadGate()

        assertTrue(gate.tryBegin())
        gate.disable()

        assertFalse(gate.finish(wireAttempted = false))
        assertFalse(gate.tryBegin())
    }
}
