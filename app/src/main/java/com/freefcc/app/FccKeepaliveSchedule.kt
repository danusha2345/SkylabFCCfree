package com.freefcc.app

/** Selects one full FCC bootstrap before the lightweight repeating profile. */
internal class FccKeepaliveSchedule(
    private val bootstrapProfile: Profiles.Profile,
    private val keepaliveProfile: Profiles.Profile
) {
    private var bootstrapPending = true

    fun nextProfile(): Profiles.Profile =
        if (bootstrapPending) bootstrapProfile else keepaliveProfile

    fun recordWrite(success: Boolean) {
        if (bootstrapPending && success) bootstrapPending = false
    }
}
