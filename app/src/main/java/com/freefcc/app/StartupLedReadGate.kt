package com.freefcc.app

/** Allows one actual startup LED read, with one extra lock-acquisition attempt. */
internal class StartupLedReadGate(
    private val maxAcquireAttempts: Int = 2
) {
    private var attempts = 0
    private var inFlightOrCompleted = false
    private var disabled = false

    @Synchronized
    fun disable() {
        disabled = true
    }

    @Synchronized
    fun tryBegin(): Boolean {
        if (disabled || attempts >= maxAcquireAttempts || inFlightOrCompleted) return false
        inFlightOrCompleted = true
        attempts++
        return true
    }

    /** Returns true when a single delayed acquisition retry should be scheduled. */
    @Synchronized
    fun finish(wireAttempted: Boolean): Boolean {
        if (wireAttempted || disabled) return false
        inFlightOrCompleted = false
        return attempts < maxAcquireAttempts
    }
}
