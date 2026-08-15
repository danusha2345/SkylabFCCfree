package com.freefcc.app

import android.content.Context

enum class FccRegion(
    val countryCode: String,
    val displayName: String
) {
    AUSTRALIA("AU", "Australia"),
    CHINA("CN", "China"),
    UNITED_STATES("US", "United States"),
    BOLIVIA("BO", "Bolivia"),
    RUSSIA("RU", "Russia"),
    NETHERLANDS("NL", "Netherlands"),
    MALAYSIA("MY", "Malaysia");

    val displayLabel: String
        get() = "$displayName ($countryCode)"

    companion object {
        val DEFAULT = AUSTRALIA

        fun fromCountryCode(value: String?): FccRegion =
            entries.firstOrNull { it.countryCode == value } ?: DEFAULT
    }
}

/** Durable country code applied immediately before every FCC profile write. */
internal object FccRegionSelection {
    private const val PREFS_NAME = "freefcc"
    internal const val PREF_REGION = "fcc_region"

    fun load(context: Context): FccRegion =
        FccRegion.fromCountryCode(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_REGION, null)
        )

    fun save(context: Context, region: FccRegion) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_REGION, region.countryCode)
            .apply()
    }
}
