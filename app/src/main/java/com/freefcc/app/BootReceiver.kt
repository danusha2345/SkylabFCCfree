package com.freefcc.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the one-shot Home Point monitor after reboot when Auto-FCC is enabled.
 *
 * The auto-FCC preference is stored in SharedPreferences and survives a reboot,
 * but Android kills all app processes on reboot — so the foreground monitor
 * service must be re-started explicitly. This receiver does that by reading
 * the auto_fcc flag and starting [FccKeepaliveService] if it's on.
 *
 * RECEIVE_BOOT_COMPLETED is a normal (install-time) permission, so no runtime
 * request is needed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("freefcc", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_fcc", false)) {
            FccKeepaliveService.start(context)
        }
    }
}
