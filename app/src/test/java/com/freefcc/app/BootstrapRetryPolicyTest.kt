package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapRetryPolicyTest {
    @Test
    fun retriesOnlyWhenNoProfileFrameCouldHaveBeenWritten() {
        assertTrue(
            BootstrapRetryPolicy.shouldRetry(BootstrapApplyResult.PRE_WRITE_CONNECT_FAILED)
        )
        assertFalse(BootstrapRetryPolicy.shouldRetry(BootstrapApplyResult.PARTIAL_FAILURE))
        assertFalse(BootstrapRetryPolicy.shouldRetry(BootstrapApplyResult.SUCCESS))
        assertFalse(BootstrapRetryPolicy.shouldRetry(BootstrapApplyResult.CANCELLED))
    }
}
