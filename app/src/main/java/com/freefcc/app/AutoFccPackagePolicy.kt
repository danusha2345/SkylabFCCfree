package com.freefcc.app

internal enum class AutoFccPackageAction {
    NONE,
    PAUSE_FOR_VITYA,
    RESUME_FOR_STOCK_FLY
}

/** Keeps standalone Auto FCC mutually exclusive with the embedded Vitya controller. */
internal object AutoFccPackagePolicy {
    const val STOCK_FLY_PACKAGE = "dji.go.v5"
    const val VITYA_PACKAGE = "dji.go.v6"

    fun action(previousPackage: String?, currentPackage: String): AutoFccPackageAction {
        if (previousPackage == currentPackage) return AutoFccPackageAction.NONE
        return when (currentPackage) {
            VITYA_PACKAGE -> AutoFccPackageAction.PAUSE_FOR_VITYA
            STOCK_FLY_PACKAGE -> AutoFccPackageAction.RESUME_FOR_STOCK_FLY
            else -> AutoFccPackageAction.NONE
        }
    }
}
