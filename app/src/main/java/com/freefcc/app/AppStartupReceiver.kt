package com.freefcc.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object AppStartupPolicy {
    fun shouldStart(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
}

/** Starts the persistent app notification after boot and after an in-place update. */
class AppStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppStartupPolicy.shouldStart(intent.action)) {
            AppForegroundService.start(context)
        }
    }
}
