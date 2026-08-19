package com.freefcc.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FccProfileAssetSafetyTest {
    private val profilesDir = sequenceOf(
        File("app/src/main/assets/profiles"),
        File("src/main/assets/profiles")
    ).first { it.isDirectory }

    @Test
    fun fccProfileIncludesBandSwitchingWrites() {
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

        // The 09:27 SDR writes only raise power; the aircraft switches to FCC
        // 5.8 only when the RADIO region (06:72 set + commit) and the WIFI
        // channel writes (07:30/07:18/07:19) are sent too. Trimming the profile
        // to the 09:27 power writes shipped a build that raised power but never
        // engaged 5.8, so guard the band-switching frames against re-trimming.
        assertTrue("09:27 SDR power write present", frames.any { (s, i) -> s == 9 && i == 0x27 })
        assertTrue("06:72 RADIO region set present", frames.any { (s, i, p) -> s == 6 && i == 0x72 && p == "00000000000100" })
        assertTrue("06:72 RADIO region commit present", frames.any { (s, i, p) -> s == 6 && i == 0x72 && p == "000000000001ff" })
        assertTrue("07:30 WIFI channel group present", frames.any { (s, i) -> s == 7 && i == 0x30 })
        assertTrue("07:18 WIFI channel map present", frames.any { (s, i) -> s == 7 && i == 0x18 })
        assertTrue("07:19 WIFI channel flag present", frames.any { (s, i) -> s == 7 && i == 0x19 })
    }

    @Test
    fun unsupportedLegacyProfilesAreNotPackaged() {
        assertFalse(File(profilesDir, "ce_restore.json").exists())
        assertFalse(File(profilesDir, "fcc_keepalive.json").exists())
    }
}
