package com.freefcc.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FccProfileAssetSafetyTest {
    private val profilesDir = sequenceOf(
        File("app/src/main/assets/profiles"),
        File("src/main/assets/profiles")
    ).first { it.isDirectory }

    @Test
    fun fccCoreExcludesKnownNonFccWrites() {
        val profile = File(profilesDir, "fcc.json").readText()
        val frames = Regex("""\{\s*"s":\s*(\d+),\s*"i":\s*(\d+),.*?"p":\s*"([0-9a-fA-F]*)"""")
            .findAll(profile)
            .map { match ->
                Triple(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].lowercase()
                )
            }
            .toList()

        assertEquals(3, frames.size)
        assertFalse(frames.any { (cmdSet, cmdId) -> cmdSet == 6 && cmdId == 0x72 })
        assertFalse(
            frames.any { (cmdSet, cmdId, payload) ->
                cmdSet == 3 && cmdId == 0xf9 && payload == "8a237103f401"
            }
        )
        assertTrue(frames.any { (cmdSet, cmdId) -> cmdSet == 9 && cmdId == 0x27 })
    }

    @Test
    fun unsupportedLegacyProfilesAreNotPackaged() {
        assertFalse(File(profilesDir, "ce_restore.json").exists())
        assertFalse(File(profilesDir, "fcc_keepalive.json").exists())
    }
}
