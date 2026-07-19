package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAutoFccRecoveryPolicyTest {
    @Test
    fun persistentRequestRestoresOnlyWhenAutoFccEnabled() {
        assertEquals(
            PersistentAutoFccRecoveryAction.CLEAR_STALE,
            PersistentAutoFccRecoveryPolicy.resolve(autoEnabled = false, inFlightMarker = true)
        )
        assertEquals(
            PersistentAutoFccRecoveryAction.NONE,
            PersistentAutoFccRecoveryPolicy.resolve(autoEnabled = false, inFlightMarker = false)
        )
        assertEquals(
            PersistentAutoFccRecoveryAction.START_NEW,
            PersistentAutoFccRecoveryPolicy.resolve(autoEnabled = true, inFlightMarker = false)
        )
        assertEquals(
            PersistentAutoFccRecoveryAction.RESTORE,
            PersistentAutoFccRecoveryPolicy.resolve(autoEnabled = true, inFlightMarker = true)
        )

        assertFalse(PersistentAutoFccRecoveryPolicy.shouldRestoreService(false, true))
        assertTrue(PersistentAutoFccRecoveryPolicy.shouldRestoreService(true, true))
    }
}
