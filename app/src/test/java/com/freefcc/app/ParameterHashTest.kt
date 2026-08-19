package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterHashTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun reproducesEveryHashSeenOnTheWire() {
        // Left column is what the aircraft was actually sent, byte for byte:
        // the LED and GPS hashes from led_on.json and GpsControlProtocol, and
        // the PM-hash writes. Only c1_regulatory_restriction remains in
        // fcc.json; the two sdr_lost_prevent_* vectors were dropped from the
        // profile but are kept here as known-good hashes of the algorithm.
        val known = mapOf(
            "g_config.misc_cfg.forearm_lamp_ctrl" to "a259ceed",
            "g_config.gps_cfg.gps_enable" to "829542c5",
            "c1_regulatory_restriction" to "d04aeffb",
            "sdr_lost_prevent_never_takeoff_en" to "236b8201",
            "sdr_lost_prevent_has_takeoff_en" to "8773e68a"
        )

        for ((name, wire) in known) {
            assertEquals(name, wire, hex(ParameterHash.of(name)))
        }
    }

    @Test
    fun shortNamesHashToTheLitoCandidates() {
        // Lito X1 reports these names without the g_config.* half, so these are
        // the hashes that firmware can answer. Computed, never confirmed live.
        assertEquals("4e9115f3", hex(ParameterHash.of("forearm_led_ctrl")))
        assertEquals("9d8a8881", hex(ParameterHash.of("gps_enable")))
    }

    @Test
    fun everyParameterOffersTheCanonicalNameFirst() {
        assertEquals("a259ceed", hex(ParameterAddress.FOREARM_LED.candidates.first()))
        assertEquals("4e9115f3", hex(ParameterAddress.FOREARM_LED.candidates[1]))
        assertEquals("829542c5", hex(ParameterAddress.GPS_ENABLE.candidates.first()))
        assertEquals("9d8a8881", hex(ParameterAddress.GPS_ENABLE.candidates[1]))
    }

    @Test
    fun matchesOnlyItsOwnCandidates() {
        val ledShort = ParameterHash.of("forearm_led_ctrl")

        assertTrue(ParameterAddress.FOREARM_LED.matches(ledShort))
        assertFalse(ParameterAddress.GPS_ENABLE.matches(ledShort))
        assertFalse(
            ParameterAddress.FOREARM_LED.matches(
                byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            )
        )
    }
}
