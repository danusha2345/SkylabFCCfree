package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedReadbackProtocolTest {
    @Test
    fun parsesOffAndOnValues() {
        assertEquals(
            LedReadback(LedState.OFF, 0x00),
            LedReadbackProtocol.parse(hex("00a259ceed00"))
        )
        assertEquals(
            LedReadback(LedState.ON, 0xEF),
            LedReadbackProtocol.parse(hex("00a259ceedef"))
        )
    }

    @Test
    fun preservesPartialMask() {
        assertEquals(
            LedReadback(LedState.PARTIAL, 0x04),
            LedReadbackProtocol.parse(hex("00a259ceed04"))
        )
    }

    @Test
    fun rejectsFailureWrongHashAndTrailingData() {
        assertNull(LedReadbackProtocol.parse(hex("01a259ceedef")))
        assertNull(LedReadbackProtocol.parse(hex("00a259ce00ef")))
        assertNull(LedReadbackProtocol.parse(hex("00a259ceedef00")))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
