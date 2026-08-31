package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoFccPackagePolicyTest {
    @Test
    fun vityaPausesStandaloneOnlyOnPackageTransition() {
        assertEquals(
            AutoFccPackageAction.PAUSE_FOR_VITYA,
            AutoFccPackagePolicy.action(null, AutoFccPackagePolicy.VITYA_PACKAGE)
        )
        assertEquals(
            AutoFccPackageAction.NONE,
            AutoFccPackagePolicy.action(
                AutoFccPackagePolicy.VITYA_PACKAGE,
                AutoFccPackagePolicy.VITYA_PACKAGE
            )
        )
    }

    @Test
    fun stockFlyResumesStandaloneAfterVitya() {
        assertEquals(
            AutoFccPackageAction.RESUME_FOR_STOCK_FLY,
            AutoFccPackagePolicy.action(
                AutoFccPackagePolicy.VITYA_PACKAGE,
                AutoFccPackagePolicy.STOCK_FLY_PACKAGE
            )
        )
    }

    @Test
    fun unrelatedPackagesDoNotChangeAutoFcc() {
        assertEquals(
            AutoFccPackageAction.NONE,
            AutoFccPackagePolicy.action(AutoFccPackagePolicy.VITYA_PACKAGE, "launcher")
        )
    }
}
