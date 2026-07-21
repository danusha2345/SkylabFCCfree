package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsControlProtocolTest {

    @Test
    fun readRequestUsesVerifiedGpsHash() {
        val request = GpsControlProtocol.buildReadRequest()

        assertEquals(0x03, request[9].toInt() and 0xFF)
        assertEquals(0xF8, request[10].toInt() and 0xFF)
        assertTrue(request.copyOfRange(11, 15).contentEquals(GPS_HASH))
    }

    @Test
    fun writeRequestAppendsExplicitBooleanValue() {
        val enabled = GpsControlProtocol.buildWriteRequest(true)
        val disabled = GpsControlProtocol.buildWriteRequest(false)

        assertEquals(0xF9, enabled[10].toInt() and 0xFF)
        assertTrue(enabled.copyOfRange(11, 15).contentEquals(GPS_HASH))
        assertEquals(1, enabled[15].toInt() and 0xFF)
        assertEquals(0, disabled[15].toInt() and 0xFF)
    }

    @Test
    fun parsesOnOffAndUnexpectedValues() {
        assertEquals(GpsReadback(GpsState.ON, 1), GpsControlProtocol.parse(response(1)))
        assertEquals(GpsReadback(GpsState.OFF, 0), GpsControlProtocol.parse(response(0)))
        assertEquals(GpsReadback(GpsState.UNEXPECTED, 2), GpsControlProtocol.parse(response(2)))
    }

    @Test
    fun rejectsFailedOrWrongHashResponses() {
        assertNull(GpsControlProtocol.parse(byteArrayOf(1) + GPS_HASH + byteArrayOf(1)))
        assertNull(GpsControlProtocol.parse(byteArrayOf(0, 0, 0, 0, 0, 1)))
        assertNull(GpsControlProtocol.parse(byteArrayOf(0) + GPS_HASH))
    }

    private fun response(value: Int): ByteArray = byteArrayOf(0) + GPS_HASH + value.toByte()

    private companion object {
        val GPS_HASH = byteArrayOf(0x82.toByte(), 0x95.toByte(), 0x42, 0xC5.toByte())
    }
}
