package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePointRetryPolicyTest {
    @Test
    fun onlyInitialConnectFailureGetsOneSlowRetry() {
        assertEquals(
            HomePointWaitDecision.RETRY_INITIAL_CONNECT,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.CONNECT_FAILED, 1, 2,
                sessionArmed = false,
                armedStreamReconnectUsed = false
            )
        )
        assertEquals(
            HomePointWaitDecision.FAIL_CLOSED,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.CONNECT_FAILED, 2, 2,
                sessionArmed = false,
                armedStreamReconnectUsed = false
            )
        )
        assertEquals(
            HomePointWaitDecision.FAIL_CLOSED,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.STREAM_DISCONNECTED, 1, 2,
                sessionArmed = false,
                armedStreamReconnectUsed = false
            )
        )
        assertEquals(
            HomePointWaitDecision.COOPERATIVE_STOP,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.STOPPED, 1, 2,
                sessionArmed = false,
                armedStreamReconnectUsed = false
            )
        )
    }

    @Test
    fun armedStreamGetsExactlyOneRecoveryAttempt() {
        assertEquals(
            HomePointWaitDecision.RETRY_ARMED_STREAM_ONCE,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.STREAM_DISCONNECTED, 0, 2,
                sessionArmed = true,
                armedStreamReconnectUsed = false
            )
        )
        assertEquals(
            HomePointWaitDecision.FAIL_CLOSED,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.STREAM_DISCONNECTED, 0, 2,
                sessionArmed = true,
                armedStreamReconnectUsed = true
            )
        )
        assertEquals(
            HomePointWaitDecision.FAIL_CLOSED,
            HomePointRetryPolicy.decide(
                HomePointWaitResult.CONNECT_FAILED, 0, 2,
                sessionArmed = true,
                armedStreamReconnectUsed = true
            )
        )
    }
}
