package com.freefcc.app

import android.content.Context

/**
 * Durable user choice for automatic FCC behaviour.
 *
 * A missing value intentionally means that both UI switches are off.
 */
internal object AutoFccSelection {
    private const val PREFS_NAME = "freefcc"
    private const val PREF_MODE = "auto_fcc_mode"

    fun load(context: Context): AutoFccMode? =
        AutoFccMode.fromPersistedValue(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_MODE, null)
        )

    fun save(context: Context, mode: AutoFccMode?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (mode == null) remove(PREF_MODE) else putString(PREF_MODE, mode.wireValue)
            }
            .apply()
    }

    fun updatedMode(
        currentMode: AutoFccMode?,
        changedMode: AutoFccMode,
        enabled: Boolean
    ): AutoFccMode? = when {
        enabled -> changedMode
        currentMode == changedMode -> null
        else -> currentMode
    }
}
