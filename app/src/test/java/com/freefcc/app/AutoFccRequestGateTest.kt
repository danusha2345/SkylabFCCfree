package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFccRequestGateTest {
    @Test
    fun reassertingActiveRequestKeepsSameGeneration() {
        val gate = AutoFccRequestGate()

        val first = gate.request()
        val second = gate.request()

        assertTrue(first.newlyRequested)
        assertFalse(second.newlyRequested)
        assertEquals(first.generation, second.generation)
        assertTrue(gate.isCurrent(first.generation))
    }

    @Test
    fun oldWorkerCannotCompleteRequestCreatedAfterStopStart() {
        val gate = AutoFccRequestGate()
        val oldRun = gate.request()

        gate.cancel()
        val newRun = gate.request()

        assertFalse(gate.complete(oldRun.generation))
        assertTrue(gate.isCurrent(newRun.generation))
        assertTrue(gate.complete(newRun.generation))
        assertNull(gate.currentGeneration())
    }

    @Test
    fun replacementRequestCannotRunBeforeItsStartCommandIsDelivered() {
        val gate = AutoFccRequestGate()
        val oldRun = gate.request()
        assertTrue(gate.markDelivered(oldRun.generation))
        assertEquals(oldRun.generation, gate.currentDeliveredGeneration())

        gate.cancel()
        val replacement = gate.request()

        assertNull(gate.currentDeliveredGeneration())
        assertFalse(gate.complete(oldRun.generation))
        assertTrue(gate.markDelivered(replacement.generation))
        assertEquals(replacement.generation, gate.currentDeliveredGeneration())
    }

    @Test
    fun staleStartCommandCannotDeliverReplacementGeneration() {
        val gate = AutoFccRequestGate()
        val stale = gate.request()
        gate.cancel()
        val replacement = gate.request()

        assertFalse(gate.markDelivered(stale.generation))
        assertNull(gate.currentDeliveredGeneration())
        assertTrue(gate.isCurrent(replacement.generation))
    }

    @Test
    fun freshProcessCanRestoreAndDeliverPersistentRequest() {
        val freshProcessGate = AutoFccRequestGate()

        val restored = freshProcessGate.restoreRequested()
        assertTrue(freshProcessGate.markDelivered(restored))

        assertEquals(restored, freshProcessGate.currentDeliveredGeneration())
        assertTrue(freshProcessGate.isCurrent(restored))
    }

    @Test
    fun failedNewStartCanRollbackWithoutClearingExistingRequest() {
        val gate = AutoFccRequestGate()
        val active = gate.request()
        val reassert = gate.request()

        gate.rollbackNewRequest(reassert)
        assertTrue(gate.isCurrent(active.generation))

        gate.cancel()
        val failedNewStart = gate.request()
        gate.rollbackNewRequest(failedNewStart)
        assertNull(gate.currentGeneration())
    }
}
