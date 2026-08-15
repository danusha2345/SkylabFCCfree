package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FccRegionSelectionTest {

    @Test
    fun `all supported FCC regions have the expected country codes`() {
        assertEquals(
            listOf("AU", "CN", "US", "BO", "RU", "NL", "MY"),
            FccRegion.entries.map(FccRegion::countryCode)
        )
    }

    @Test
    fun `missing or unknown persisted region falls back to Australia`() {
        assertEquals(FccRegion.AUSTRALIA, FccRegion.fromCountryCode(null))
        assertEquals(FccRegion.AUSTRALIA, FccRegion.fromCountryCode("ZZ"))
        assertEquals(FccRegion.RUSSIA, FccRegion.fromCountryCode("RU"))
    }
}
