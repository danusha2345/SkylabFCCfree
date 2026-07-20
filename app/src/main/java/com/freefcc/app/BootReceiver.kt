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

        // Some controller builds can reject a background foreground-service
        // start. Preserve auto_fcc so opening the app can retry, but never let
        // an OS-delivered BOOT_COMPLETED crash the process. The preference
        // check and service request share the same lock as stop().
        runCatching { FccKeepaliveService.startAutoIfEnabled(context) }
    }
}
