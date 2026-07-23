package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundServicePolicyTest {
    @Test
    fun notificationActionsSelectExactlyOneModeOrTurnBothOff() {
        assertEquals(
            AutoFccMode.HOME_POINT_TEXT,
            AppNotificationActionPolicy.selectedMode(
                AppForegroundService.ACTION_SELECT_HOME_POINT
            )
        )
        assertEquals(
            AutoFccMode.PERIODIC_5S,
            AppNotificationActionPolicy.selectedMode(
                AppForegroundService.ACTION_SELECT_PERIODIC
            )
        )
        assertNull(
            AppNotificationActionPolicy.selectedMode(
                AppForegroundService.ACTION_SELECT_OFF
            )
        )
        assertTrue(
            AppNotificationActionPolicy.turnsOff(
                AppForegroundService.ACTION_SELECT_OFF
            )
        )
        assertFalse(
            AppNotificationActionPolicy.turnsOff(
                AppForegroundService.ACTION_SELECT_HOME_POINT
            )
        )
    }

    @Test
    fun notificationStatusDescribesPersistedSelection() {
        assertEquals(
            "Auto FCC: Off",
            AppNotificationActionPolicy.statusText(null, accessibilityEnabled = false)
        )
        assertEquals(
            "Auto FCC: Home Point",
            AppNotificationActionPolicy.statusText(
                AutoFccMode.HOME_POINT_TEXT,
                accessibilityEnabled = true
            )
        )
        assertEquals(
            "Auto FCC: Home Point · enable Accessibility",
            AppNotificationActionPolicy.statusText(
                AutoFccMode.HOME_POINT_TEXT,
                accessibilityEnabled = false
            )
        )
        assertEquals(
            "Auto FCC: every 5 seconds",
            AppNotificationActionPolicy.statusText(
                AutoFccMode.PERIODIC_5S,
                accessibilityEnabled = false
            )
        )
    }
}
