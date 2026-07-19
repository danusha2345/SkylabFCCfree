package com.freefcc.app

internal enum class HomePointWaitDecision {
    RECORDED,
    RETRY_INITIAL_CONNECT,
    FAIL_CLOSED,
    COOPERATIVE_STOP
}

internal object HomePointRetryPolicy {
    fun decide(
        result: HomePointWaitResult,
        initialConnectAttempts: Int,
        maxInitialConnectAttempts: Int
    ): HomePointWaitDecision = when (result) {
        HomePointWaitResult.RECORDED -> HomePointWaitDecision.RECORDED
        HomePointWaitResult.STOPPED -> HomePointWaitDecision.COOPERATIVE_STOP
        HomePointWaitResult.STREAM_DISCONNECTED -> HomePointWaitDecision.FAIL_CLOSED
        HomePointWaitResult.CONNECT_FAILED -> {
            if (initialConnectAttempts < maxInitialConnectAttempts) {
                HomePointWaitDecision.RETRY_INITIAL_CONNECT
            } else {
                HomePointWaitDecision.FAIL_CLOSED
            }
        }
    }
}
