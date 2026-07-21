package com.freefcc.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Prevents concurrent GPS, LED, diagnostics and Home Point sessions on port 40007. */
internal object Port40007Lock {
    private val held = AtomicBoolean(false)
    private val externalRequestCount = AtomicInteger(0)

    fun tryBegin(): Lease? =
        tryBegin(releaseExternalRequest = false)

    fun shouldYieldToLed(): Boolean = externalRequestCount.get() > 0

    suspend fun acquireForLed(timeoutMs: Long = 3_000): Lease? {
        externalRequestCount.incrementAndGet()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        try {
            while (System.nanoTime() < deadline) {
                tryBegin(releaseExternalRequest = true)?.let { return it }
                delay(25)
            }
        } catch (e: CancellationException) {
            externalRequestCount.decrementAndGet()
            throw e
        }
        externalRequestCount.decrementAndGet()
        return null
    }

    fun acquireForExternalBlocking(timeoutMs: Long = 3_000): Lease? {
        externalRequestCount.incrementAndGet()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            tryBegin(releaseExternalRequest = true)?.let { return it }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                externalRequestCount.decrementAndGet()
                Thread.currentThread().interrupt()
                return null
            }
        }
        externalRequestCount.decrementAndGet()
        return null
    }

    fun releaseFromLed(lease: Lease) {
        lease.close()
    }

    private fun tryBegin(releaseExternalRequest: Boolean): Lease? =
        if (held.compareAndSet(false, true)) Lease(releaseExternalRequest) else null

    class Lease internal constructor(
        private val releaseExternalRequest: Boolean
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                if (releaseExternalRequest) {
                    Port40007Lock.externalRequestCount.decrementAndGet()
                }
                Port40007Lock.held.set(false)
            }
        }
    }
}
