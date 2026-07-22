package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatCaptureTest {

    @Test
    fun durationIsBoundedAndDefaultsToOneMinute() {
        assertEquals(60_000, LogcatCapture.durationMs(emptyMap()))
        assertEquals(1_000, LogcatCapture.durationMs(mapOf("duration_ms" to "1000")))
        assertEquals(120_000, LogcatCapture.durationMs(mapOf("duration_ms" to "120000")))

        listOf("999", "120001", "bad").forEach { value ->
            assertTrue(
                runCatching {
                    LogcatCapture.durationMs(mapOf("duration_ms" to value))
                }.exceptionOrNull() is IllegalArgumentException
            )
        }
    }

    @Test
    fun commandIsNarrowAndDoesNotOpenDjiTransports() {
        val command = LogcatCapture.command()

        assertEquals("/system/bin/logcat", command.first())
        assertTrue(command.contains("DUSS73:I"))
        assertTrue(command.contains("OpenFCC.FourG:V"))
        assertTrue(command.contains("OpenFCC.SessionCrypto:V"))
        assertEquals("*:S", command.last())
        assertTrue(command.none { it.contains("40007") || it.contains("/duss/") })
    }
}
