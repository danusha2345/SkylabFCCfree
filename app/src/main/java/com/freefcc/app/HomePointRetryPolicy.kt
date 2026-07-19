package com.freefcc.app

internal enum class HomePointWaitDecision {
    RECORDED,
    RETRY_INITIAL_CONNECT,
    RETRY_ARMED_STREAM_ONCE,
    FAIL_CLOSED,
    COOPERATIVE_STOP
}

internal object HomePointRetryPolicy {
    fun decide(
        result: HomePointWaitResult,
        initialConnectAttempts: Int,
        maxInitialConnectAttempts: Int,
        sessionArmed: Boolean,
        armedStreamReconnectUsed: Boolean
    ): HomePointWaitDecision = when (result) {
        HomePointWaitResult.RECORDED -> HomePointWaitDecision.RECORDED
        HomePointWaitResult.STOPPED -> HomePointWaitDecision.COOPERATIVE_STOP
        HomePointWaitResult.STREAM_DISCONNECTED -> {
            if (sessionArmed && !armedStreamReconnectUsed) {
                HomePointWaitDecision.RETRY_ARMED_STREAM_ONCE
            } else {
                HomePointWaitDecision.FAIL_CLOSED
            }
        }
        HomePointWaitResult.CONNECT_FAILED -> {
            if (!armedStreamReconnectUsed && initialConnectAttempts < maxInitialConnectAttempts) {
                HomePointWaitDecision.RETRY_INITIAL_CONNECT
            } else {
                HomePointWaitDecision.FAIL_CLOSED
            }
        }
    }
}
