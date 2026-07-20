package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFccStartupCoordinatorTest {
    @Test
    fun successfulLaunchDefersMonitorUntilActivityStops() {
        val coordinator = AutoFccStartupCoordinator()
        val events = mutableListOf<String>()

        val deferred = coordinator.launchAndDeferMonitor(
            isEnabled = { true },
            isAppForeground = { true },
            launchFlightApp = {
                events += "flight_app_launched"
                true
            },
            startMonitor = { events += "monitor_started" }
        )

        assertTrue(deferred)
        assertEquals(listOf("flight_app_launched"), events)

        val started = coordinator.onAppBackgrounded(
            isEnabled = { true },
            startMonitor = { events += "monitor_started" }
        )

        assertTrue(started)
        assertEquals(
            listOf("flight_app_launched", "monitor_started"),
            events
        )
    }

    @Test
    fun disablingBeforeActivityStopsPreventsLateMonitorStart() {
        val coordinator = AutoFccStartupCoordinator()
        var enabled = true
        val events = mutableListOf<String>()

        val deferred = coordinator.launchAndDeferMonitor(
            isEnabled = { enabled },
            isAppForeground = { true },
            launchFlightApp = {
                events += "flight_app_launched"
                true
            },
            startMonitor = { events += "monitor_started" }
        )
        enabled = false
        val started = coordinator.onAppBackgrounded(
            isEnabled = { enabled },
            startMonitor = { events += "monitor_started" }
        )

        assertTrue(deferred)
        assertFalse(started)
        assertEquals(listOf("flight_app_launched"), events)
    }

    @Test
    fun failedFlightAppLaunchUsesImmediateMonitorFallback() {
        val coordinator = AutoFccStartupCoordinator()
        val events = mutableListOf<String>()

        val deferred = coordinator.launchAndDeferMonitor(
            isEnabled = { true },
            isAppForeground = { true },
            launchFlightApp = {
                events += "flight_app_failed"
                false
            },
            startMonitor = { events += "monitor_started" }
        )

        assertFalse(deferred)
        assertEquals(listOf("flight_app_failed", "monitor_started"), events)
        assertFalse(
            coordinator.onAppBackgrounded(
                isEnabled = { true },
                startMonitor = { events += "late_monitor" }
            )
        )
    }

    @Test
    fun cancelClearsPendingHandoff() {
        val coordinator = AutoFccStartupCoordinator()
        val events = mutableListOf<String>()

        assertTrue(
            coordinator.launchAndDeferMonitor(
                isEnabled = { true },
                isAppForeground = { true },
                launchFlightApp = { true },
                startMonitor = { events += "monitor_started" }
            )
        )
        coordinator.cancel()

        val started = coordinator.onAppBackgrounded(
            isEnabled = { true },
            startMonitor = { events += "monitor_started" }
        )

        assertFalse(started)
        assertTrue(events.isEmpty())
    }

    @Test
    fun alreadyBackgroundedActivityStartsMonitorWithoutWaitingForAnotherStop() {
        val coordinator = AutoFccStartupCoordinator()
        val events = mutableListOf<String>()

        val deferred = coordinator.launchAndDeferMonitor(
            isEnabled = { true },
            isAppForeground = { false },
            launchFlightApp = {
                events += "flight_app_launched"
                true
            },
            startMonitor = { events += "monitor_started" }
        )

        assertFalse(deferred)
        assertEquals(listOf("flight_app_launched", "monitor_started"), events)
    }
}
