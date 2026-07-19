package com.freefcc.app

internal enum class PhysicalFccState {
    UNKNOWN
}

internal data class FccUiPresentation(
    val badgeTitle: String,
    val detail: String,
    val primaryActionLabel: String,
    val physicalState: PhysicalFccState
)

internal fun fccUiPresentation(writeEvidence: Boolean): FccUiPresentation =
    if (writeEvidence) {
        FccUiPresentation(
            badgeTitle = "FCC REQUEST SENT",
            detail = "RF mode unknown — verify in DJI Fly",
            primaryActionLabel = "Send CE Restore",
            physicalState = PhysicalFccState.UNKNOWN
        )
    } else {
        FccUiPresentation(
            badgeTitle = "RF MODE UNKNOWN",
            detail = "No write evidence in this app session",
            primaryActionLabel = "Send FCC Request",
            physicalState = PhysicalFccState.UNKNOWN
        )
    }
