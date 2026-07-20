package com.freefcc.app

import java.util.concurrent.atomic.AtomicBoolean

/** Defers monitor start until the FreeFCC Activity has actually left foreground. */
internal class AutoFccStartupCoordinator {
    private val pendingHandoff = AtomicBoolean(false)

    fun launchAndDeferMonitor(
        isEnabled: () -> Boolean,
        isAppForeground: () -> Boolean,
        launchFlightApp: () -> Boolean,
        startMonitor: () -> Unit
    ): Boolean {
        if (!isEnabled()) return false
        pendingHandoff.set(true)
        if (launchFlightApp()) {
            if (!isAppForeground() && pendingHandoff.compareAndSet(true, false) && isEnabled()) {
                startMonitor()
                return false
            }
            return pendingHandoff.get()
        }

        if (pendingHandoff.compareAndSet(true, false) && isEnabled()) {
            startMonitor()
        }
        return false
    }

    fun onAppBackgrounded(
        isEnabled: () -> Boolean,
        startMonitor: () -> Unit
    ): Boolean {
        if (!pendingHandoff.compareAndSet(true, false) || !isEnabled()) return false
        startMonitor()
        return true
    }

    fun cancel() {
        pendingHandoff.set(false)
    }
}
