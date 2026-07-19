package com.freefcc.app

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Port40007LockTest {
    @Test
    fun leaseIsExclusiveAndCloseIsIdempotent() {
        val first = Port40007Lock.tryBegin()
        assertNotNull(first)
        assertNull(Port40007Lock.tryBegin())

        first!!.close()
        first.close()

        val next = Port40007Lock.tryBegin()
        assertNotNull(next)
        next!!.close()
    }

    @Test
    fun ledAccessRequestsYieldAndAcquiresAfterListenerCloses() = runBlocking {
        val listener = Port40007Lock.tryBegin()
        assertNotNull(listener)
        val release = launch {
            delay(50)
            assertTrue(Port40007Lock.shouldYieldToLed())
            listener!!.close()
        }

        val led = Port40007Lock.acquireForLed(500)

        release.join()
        assertNotNull(led)
        assertTrue(Port40007Lock.shouldYieldToLed())
        Port40007Lock.releaseFromLed(led!!)
        assertFalse(Port40007Lock.shouldYieldToLed())
    }

    @Test
    fun oneTimedOutWaiterDoesNotClearAnotherWaitersYieldRequest() = runBlocking {
        val listener = Port40007Lock.tryBegin()
        assertNotNull(listener)

        val longWaiter = async { Port40007Lock.acquireForLed(500) }
        delay(25)
        val shortWaiter = async { Port40007Lock.acquireForLed(50) }

        assertNull(shortWaiter.await())
        assertTrue(Port40007Lock.shouldYieldToLed())

        listener!!.close()
        val acquired = longWaiter.await()
        assertNotNull(acquired)
        assertTrue(Port40007Lock.shouldYieldToLed())
        Port40007Lock.releaseFromLed(acquired!!)
        assertFalse(Port40007Lock.shouldYieldToLed())
    }
}
