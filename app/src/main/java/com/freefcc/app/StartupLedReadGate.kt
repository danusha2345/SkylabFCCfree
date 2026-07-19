package com.freefcc.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Allows one actual startup LED read, with one extra lock-acquisition attempt. */
internal class StartupLedReadGate(
    private val maxAcquireAttempts: Int = 2
) {
    private val attempts = AtomicInteger(0)
    private val inFlightOrCompleted = AtomicBoolean(false)

    fun tryBegin(): Boolean {
        if (attempts.get() >= maxAcquireAttempts) return false
        if (!inFlightOrCompleted.compareAndSet(false, true)) return false
        if (attempts.incrementAndGet() <= maxAcquireAttempts) return true
        inFlightOrCompleted.set(false)
        return false
    }

    /** Returns true when a single delayed acquisition retry should be scheduled. */
    fun finish(wireAttempted: Boolean): Boolean {
        if (wireAttempted) return false
        inFlightOrCompleted.set(false)
        return attempts.get() < maxAcquireAttempts
    }
}
