package com.freefcc.app

internal enum class PersistentAutoFccRecoveryAction {
    NONE,
    CLEAR_STALE,
    START_NEW,
    RESTORE
}

/** `auto_fcc` is user intent; `keepalive_running` is only an in-flight marker. */
internal object PersistentAutoFccRecoveryPolicy {
    fun resolve(autoEnabled: Boolean, inFlightMarker: Boolean): PersistentAutoFccRecoveryAction =
        when {
            autoEnabled && inFlightMarker -> PersistentAutoFccRecoveryAction.RESTORE
            autoEnabled -> PersistentAutoFccRecoveryAction.START_NEW
            inFlightMarker -> PersistentAutoFccRecoveryAction.CLEAR_STALE
            else -> PersistentAutoFccRecoveryAction.NONE
        }

    fun shouldRestoreService(autoEnabled: Boolean, inFlightMarker: Boolean): Boolean =
        resolve(autoEnabled, inFlightMarker) == PersistentAutoFccRecoveryAction.RESTORE
}
