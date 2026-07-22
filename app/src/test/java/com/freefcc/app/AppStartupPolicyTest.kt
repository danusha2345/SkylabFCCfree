package com.freefcc.app

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupPolicyTest {

    @Test
    fun startsOnlyForBootAndPackageReplacement() {
        assertTrue(AppStartupPolicy.shouldStart(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(AppStartupPolicy.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(AppStartupPolicy.shouldStart(Intent.ACTION_SHUTDOWN))
        assertFalse(AppStartupPolicy.shouldStart(null))
    }
}
