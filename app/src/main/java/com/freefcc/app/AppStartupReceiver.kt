package com.freefcc.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object AppStartupPolicy {
    fun shouldStart(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
}

/** Restores the app runtime and the user's selected Auto FCC mode after startup. */
class AppStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppStartupPolicy.shouldStart(intent.action)) {
            AppForegroundService.start(context)
            FccKeepaliveService.startSelectedMode(context)
        }
    }
}
