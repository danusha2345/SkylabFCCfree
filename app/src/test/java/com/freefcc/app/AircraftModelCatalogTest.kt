package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftModelCatalogTest {

    @Test
    fun namesKnownProductCodes() {
        assertEquals("DJI Air 3S", AircraftModelCatalog.nameForCode("WA234"))
        assertEquals("DJI Avata 360", AircraftModelCatalog.nameForCode("WA530"))
        assertEquals("DJI Mavic 4 Pro", AircraftModelCatalog.nameForCode("WA341"))
        assertEquals("DJI Mavic 3 Classic", AircraftModelCatalog.nameForCode("WM2605"))
        assertEquals("DJI Mavic 3T", AircraftModelCatalog.nameForCode("wm265t"))
        assertEquals("", AircraftModelCatalog.nameForCode("WA999"))
    }

    @Test
    fun keepsTheNameTheScreenPrintsEvenWhenItIsUnknown() {
        val match = AircraftModelCatalog.findOnScreen(
            listOf("DJI Zephyr 9", "Battery 87%", "Home Point updated")
        )

        assertEquals("DJI Zephyr 9", match?.name)
        assertEquals("", match?.code)
    }

    @Test
    fun ignoresAFamilyNameWithoutItsModel() {
        // Seen live: these were read off a screen while a Mini 5 Pro was
        // connected, and replaced its name in statistics.
        assertNull(AircraftModelCatalog.findOnScreen(listOf("DJI Mavic", "Battery 87%")))
        assertNull(AircraftModelCatalog.findOnScreen(listOf("DJI Air", "Battery 87%")))
        assertNull(AircraftModelCatalog.findOnScreen(listOf("DJI Mini")))
    }

    @Test
    fun keepsTwoWordNamesThatAreRealModels() {
        assertEquals("DJI Neo", AircraftModelCatalog.findOnScreen(listOf("DJI Neo"))?.name)
        assertEquals("DJI Flip", AircraftModelCatalog.findOnScreen(listOf("DJI Flip"))?.name)
        assertEquals("DJI Avata", AircraftModelCatalog.findOnScreen(listOf("DJI Avata"))?.name)
        assertEquals(
            "DJI Mini 5 Pro",
            AircraftModelCatalog.findOnScreen(listOf("DJI Mini 5 Pro"))?.name
        )
    }

    @Test
    fun readsCodeAndNameOffTheSameScreen() {
        val match = AircraftModelCatalog.findOnScreen(
            listOf("DJI Avata 360", "WA530", "Battery 87%")
        )

        assertEquals("DJI Avata 360", match?.name)
        assertEquals("WA530", match?.code)
    }

    @Test
    fun dropsDisplayRevisionFromAircraftName() {
        val match = AircraftModelCatalog.findOnScreen(listOf("DJI Air 3S V01"))

        assertEquals("DJI Air 3S", match?.name)
        assertEquals("WA234", match?.code)
    }

    @Test
    fun readsANameThatSharedItsLabelWithTheCode() {
        val match = AircraftModelCatalog.findOnScreen(listOf("Aircraft: DJI Avata 360 (WA530)"))

        assertEquals("DJI Avata 360", match?.name)
        assertEquals("WA530", match?.code)
    }

    @Test
    fun infersTheCodeFromAnUnambiguousName() {
        val match = AircraftModelCatalog.findOnScreen(listOf("DJI Mini 4 Pro"))

        assertEquals("WA140", match?.code)
        assertEquals("DJI Mini 4 Pro", match?.name)
    }

    @Test
    fun prefersTheLongerNameOverItsPrefix() {
        assertEquals("WA234", AircraftModelCatalog.findOnScreen(listOf("DJI Air 3S"))?.code)
        assertEquals("WA233", AircraftModelCatalog.findOnScreen(listOf("DJI Air 3"))?.code)
        assertEquals(
            "DJI Mavic 3T",
            AircraftModelCatalog.findOnScreen(listOf("Connected to Mavic 3T"))?.name
        )
    }

    @Test
    fun refusesToGuessACodeForTheSharedMavic2Name() {
        val match = AircraftModelCatalog.findOnScreen(listOf("DJI Mavic 2"))

        assertEquals("", match?.code)
        assertEquals("DJI Mavic 2", match?.name)
    }

    @Test
    fun namesACodeThatAppearsWithoutItsCommercialName() {
        val match = AircraftModelCatalog.findOnScreen(listOf("Aircraft", "WA341", "linked"))

        assertEquals("WA341", match?.code)
        assertEquals("DJI Mavic 4 Pro", match?.name)
    }

    @Test
    fun ignoresDjiProductsThatAreNotTheAircraft() {
        listOf(
            "DJI Fly",
            "DJI Care Refresh",
            "DJI Store",
            "DJI Inc",
            "DJI Lito",
            "DJI Simulator",
            "DJI Global Creator Community",
            "DJI RC Pro 2",
            "DJI Goggles 3"
        ).forEach { label ->
            assertNull(label, AircraftModelCatalog.findOnScreen(listOf(label, "Battery 87%")))
        }
    }

    @Test
    fun ignoresSentenceFragmentsThatStartWithDji() {
        // Values that reached the statistics server as aircraft names.
        listOf(
            "DJI Aircraft Terms of Use",
            "DJI Terms of Use",
            "DJI Mavic3 Series",
            "DJI Aircraft",
            "Read the DJI Terms of Use"
        ).forEach { label ->
            assertNull(label, AircraftModelCatalog.findOnScreen(listOf(label, "Battery 87%")))
        }
    }

    @Test
    fun readsTheModelInFrontOfAForeignWord() {
        assertEquals(
            "DJI Lito X1",
            AircraftModelCatalog.findOnScreen(listOf("DJI Lito X1 donanım yazılımı"))?.name
        )
    }

    @Test
    fun readsAnUnknownNameOutOfASentence() {
        val match = AircraftModelCatalog.findOnScreen(
            listOf("Подключено: DJI Zephyr 9", "Battery 87%")
        )

        assertEquals("DJI Zephyr 9", match?.name)
        assertEquals("", match?.code)
    }

    @Test
    fun namesTheModelsReadOutOfTheDjiFlyResources() {
        // DJI Fly pairs the two namespaces in its font assets: `fly_uav165_wa151`
        // with `product_official_name_UAV165` = `DJI Lito X1`.
        assertEquals("DJI Lito X1", AircraftModelCatalog.nameForCode("WA151"))
        assertEquals("DJI Lito 1", AircraftModelCatalog.nameForCode("WA152"))
        assertEquals("WA151", AircraftModelCatalog.findOnScreen(listOf("DJI Lito X1"))?.code)
        assertEquals("DJI Neo 2", AircraftModelCatalog.nameForCode("WA020"))
        assertEquals("DJI Mini 2 SE", AircraftModelCatalog.nameForCode("WM1615"))
        assertEquals("DJI Mini 4K", AircraftModelCatalog.nameForCode("WM1617"))
        assertEquals("Mavic Mini", AircraftModelCatalog.nameForCode("WM160"))

        // A code ending in a digit must survive the screen parser whole.
        val match = AircraftModelCatalog.findOnScreen(listOf("WM1615", "Battery 87%"))
        assertEquals("WM1615", match?.code)
        assertEquals("DJI Mini 2 SE", match?.name)
    }

    @Test
    fun neverShortensAKnownNameThatContinuesWithANumber() {
        val match = AircraftModelCatalog.findOnScreen(listOf("Connected to DJI Avata 360"))

        assertEquals("DJI Avata 360", match?.name)
        assertEquals("WA530", match?.code)
    }

    @Test
    fun dropsAStoredCodeThatBelongsToAnotherAircraft() {
        // Avata 360 named on screen, WM169 (DJI Avata) left over from before.
        assertEquals(
            "",
            AircraftModelCatalog.codeFor("DJI Avata 360", "", "DJI Avata 360", "WM169")
        )
        assertEquals("", AircraftModelCatalog.codeFor("DJI Air 3S", "", "DJI Avata", "WM169"))
    }

    @Test
    fun keepsAStoredCodeThatMatchesTheName() {
        assertEquals(
            "WM169",
            AircraftModelCatalog.codeFor("DJI Avata", "", "DJI Avata", "WM169")
        )
        assertEquals(
            "WA530",
            AircraftModelCatalog.codeFor("DJI Avata 360", "", "DJI Avata 360", "WA530")
        )
        assertEquals(
            "WA234",
            AircraftModelCatalog.codeFor("DJI Air 3S", "WA234", "DJI Avata", "WM169")
        )
    }

    @Test
    fun ignoresScreensWithoutAnAircraftIdentity() {
        listOf(
            listOf("Home Point updated", "12.5 m/s", "120 m"),
            listOf("NEON", "FLIPPED", "AIRPLANE MODE"),
            listOf("RC520", "rm510", "Battery 64%"),
            listOf("")
        ).forEach { screen ->
            assertNull(screen.toString(), AircraftModelCatalog.findOnScreen(screen))
        }
    }

    @Test
    fun knownProductCodeOutranksAnUnrelatedDjiLabelOnTheSameScreen() {
        val match = AircraftModelCatalog.findOnScreen(listOf("DJI Lito", "WA530"))

        assertEquals("WA530", match?.code)
        assertEquals("DJI Avata 360", match?.name)
    }
}

class AircraftModelObservationGateTest {
    @Test
    fun aChangedNameNeedsTwoObservationsAtLeastASecondApart() {
        val gate = AircraftModelObservationGate(confirmationMs = 1_000L)

        assertFalse(gate.accepts(storedName = "DJI Air 3S", observedName = "DJI Zephyr 9", 0L))
        assertFalse(gate.accepts(storedName = "DJI Air 3S", observedName = "DJI Zephyr 9", 999L))
        assertTrue(gate.accepts(storedName = "DJI Air 3S", observedName = "DJI Zephyr 9", 1_000L))
    }

    @Test
    fun aDifferentCandidateRestartsConfirmationAndTheStoredNameNeedsNone() {
        val gate = AircraftModelObservationGate(confirmationMs = 1_000L)

        assertFalse(gate.accepts("DJI Air 3S", "DJI Zephyr 9", 0L))
        assertFalse(gate.accepts("DJI Air 3S", "DJI Borealis 2", 2_000L))
        assertTrue(gate.accepts("DJI Air 3S", "DJI Air 3S", 2_001L))
    }
}
