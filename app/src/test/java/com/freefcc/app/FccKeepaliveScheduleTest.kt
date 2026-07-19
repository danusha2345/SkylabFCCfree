package com.freefcc.app

import org.junit.Assert.assertSame
import org.junit.Test

class FccKeepaliveScheduleTest {

    @Test
    fun failedBootstrapIsRetriedBeforeKeepalive() {
        val bootstrap = profile(0x01)
        val keepalive = profile(0x02)
        val schedule = FccKeepaliveSchedule(bootstrap, keepalive)

        assertSame(bootstrap, schedule.nextProfile())
        schedule.recordWrite(success = false)

        assertSame(bootstrap, schedule.nextProfile())
    }

    @Test
    fun successfulBootstrapSwitchesToLightweightKeepalive() {
        val bootstrap = profile(0x01)
        val keepalive = profile(0x02)
        val schedule = FccKeepaliveSchedule(bootstrap, keepalive)

        assertSame(bootstrap, schedule.nextProfile())
        schedule.recordWrite(success = true)

        assertSame(keepalive, schedule.nextProfile())
    }

    private fun profile(marker: Int) = Profiles.Profile(
        sender = 0,
        cmdType = 0,
        rounds = 1,
        interFrameDelay = 0,
        interRoundDelay = 0,
        readWindowMs = 0,
        needsResponse = false,
        port = DumlTransport.PORT,
        frames = listOf(byteArrayOf(marker.toByte()))
    )
}
