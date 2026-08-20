package com.freefcc.app

import org.junit.Assert.assertTrue
import org.junit.Test

class FourGResponseInterpreterTest {

    @Test
    fun knownResponseTripletsAreReportedHonestly() {
        assertTrue(FourGResponseInterpreter.describe(byteArrayOf(0, 0, 0)).contains("ACCEPTED"))
        assertTrue(FourGResponseInterpreter.describe(byteArrayOf(3, 3, 3)).contains("REFUSED"))
        assertTrue(FourGResponseInterpreter.describe(byteArrayOf(9, 9, 9)).contains("invalid"))
    }

    @Test
    fun missingAndUnknownResponsesAreNotReportedAsSuccess() {
        assertTrue(FourGResponseInterpreter.describe(null).contains("status unknown"))
        assertTrue(
            FourGResponseInterpreter.describe(byteArrayOf(5, 5, 5, 0x2A)).contains("05 05 05 2A")
        )
    }
}
