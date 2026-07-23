package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FccUiPresentationTest {
    @Test
    fun successfulWritePresentationKeepsPhysicalStateUnknown() {
        val presentation = fccUiPresentation(writeEvidence = true)

        assertEquals("FCC REQUEST SENT", presentation.badgeTitle)
        assertEquals("RF mode unknown — verify in DJI Fly", presentation.detail)
        assertEquals(PhysicalFccState.UNKNOWN, presentation.physicalState)
        assertFalse(presentation.badgeTitle.contains("ENABLED"))
    }

    @Test
    fun missingWriteEvidenceDoesNotClaimDefaultOrCeMode() {
        val presentation = fccUiPresentation(writeEvidence = false)

        assertEquals("RF MODE UNKNOWN", presentation.badgeTitle)
        assertEquals("No write evidence in this app session", presentation.detail)
        assertEquals(PhysicalFccState.UNKNOWN, presentation.physicalState)
        assertFalse(presentation.badgeTitle.contains("DEFAULT"))
        assertFalse(presentation.badgeTitle.contains("CE"))
    }
}
