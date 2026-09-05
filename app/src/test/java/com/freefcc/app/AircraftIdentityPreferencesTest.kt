package com.freefcc.app

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftIdentityPreferencesTest {
    @After
    fun clearParameterConfirmations() {
        ParameterAddress.forgetAllConfirmed()
    }

    @Test
    fun modelSwapBeforeSerialArrivesForgetsBothParameterAddresses() {
        for (previousSerial in listOf("", "1581F9DEC25AQ02998T5")) {
            val prefs = inMemoryPreferences(
                mapOf(
                    FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA341",
                    FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Mini 5 Pro",
                    AircraftSerialGuard.KEY_SERIAL to previousSerial
                )
            )
            val addresses = listOf(ParameterAddress.GPS_ENABLE, ParameterAddress.FOREARM_LED)
            addresses.forEach { it.confirm(it.candidates.last()) }

            AircraftIdentityPreferences.updateFromDuml(
                prefs, AircraftModelIdentity("WA530", "DJI Avata 360"), null, 10_000L
            )

            addresses.forEach {
                assertFalse(it.isConfirmed)
                assertEquals(it.candidates.size, it.spellingsToTry().size)
            }
        }
    }

    @Test
    fun unchangedAircraftKeepsBothParameterAddresses() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA530",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Avata 360"
            )
        )
        val addresses = listOf(ParameterAddress.GPS_ENABLE, ParameterAddress.FOREARM_LED)
        addresses.forEach { it.confirm(it.candidates.last()) }

        AircraftIdentityPreferences.updateFromDuml(
            prefs, AircraftModelIdentity("WA530", "DJI Avata 360"), null, 10_000L
        )

        addresses.forEach {
            assertTrue(it.isConfirmed)
            assertTrue(it.preferred().contentEquals(it.candidates.last()))
        }
    }

    @Test
    fun freshDumlCodeReplacesThePreviousAircraftScreenName() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA341",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Mini 5 Pro",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581F9DEC25AQ02998T5"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", "DJI Avata 360"),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertTrue(update.modelChanged)
        assertTrue(update.serialChanged)
        assertEquals(AircraftModelIdentity("WA530", "DJI Avata 360"), update.currentModel)
        assertEquals("", update.currentSerial)
        assertEquals(
            FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
            prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE, "")
        )
    }

    @Test
    fun currentDjiFlyScreenNameOutranksTheBusNameForTheSameCode() {
        val screenName = "DJI Avata 360 Enhanced Transmission edition"
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA530",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to screenName,
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", "DJI Avata 360"),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertFalse(update.modelChanged)
        assertEquals(screenName, update.currentModel.modelName)
        assertEquals(
            FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
            prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE, "")
        )
    }

    @Test
    fun busCodeReplacesACodelessScreenNameLeftByThePreviousAircraft() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581F9DEC25AQ02998T5"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", ""),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertTrue(update.modelChanged)
        assertEquals(AircraftModelIdentity("WA530", "DJI Avata 360"), update.currentModel)
        assertEquals("", update.currentSerial)
        assertEquals("", prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun busNameForAnUnknownCodeStillDisplacesThePreviousScreenName() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581F9DEC25AQ02998T5"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("AG410", "DJI Agras T50"),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertEquals(AircraftModelIdentity("AG410", "DJI Agras T50"), update.currentModel)
        assertEquals("", update.currentSerial)
        assertEquals("", prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun aCodelessScreenEditionSurvivesTheCatalogNameOfItsOwnCode() {
        val edition = "DJI Avata 360 Enhanced Transmission edition"
        val serial = "1581F9DEC25AQ02998T5"
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to edition,
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to serial
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", ""),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertEquals(AircraftModelIdentity("WA530", edition), update.currentModel)
        assertFalse(update.serialChanged)
        assertEquals(serial, prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun aSuffixModelIsNotMistakenForTheAircraftItsNameExtends() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Air 3",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581F9DEC25AQ02998T5"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA234", ""),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertEquals(AircraftModelIdentity("WA234", "DJI Air 3S"), update.currentModel)
        assertEquals("", update.currentSerial)
        assertEquals("", prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun unknownBusCodeKeepsTheVerbatimScreenNameAndTheStoredSerial() {
        val serial = "1581F9DEC25AQ02998T5"
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to serial
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA999", ""),
            observedSerial = null,
            nowMs = 10_000L
        )

        assertEquals(AircraftModelIdentity("WA999", "DJI Lito X1"), update.currentModel)
        assertFalse(update.serialChanged)
        assertEquals(serial, prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun theShortSpellingOfTheStoredSerialIsNotASwap() {
        val full = "1581FA8JC264600B31QZ"
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA530",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Avata 360",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to full
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = null,
            observedSerial = "FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        assertFalse(update.serialChanged)
        assertEquals(full, update.currentSerial)
        assertEquals(full, prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun aProvenSwapDropsThePreviousSerialInEitherSpelling() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA341",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Mavic 4 Pro",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to "1581FA8JC264600B31QZ"
            )
        )

        // The bus repeats the aircraft that just left, this time as the tail.
        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", "DJI Avata 360"),
            observedSerial = "FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        assertEquals("", update.currentSerial)
        assertEquals("", prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
        assertEquals(AircraftModelIdentity("WA530", "DJI Avata 360"), update.currentModel)
    }

    @Test
    fun theFullSpellingReplacesAStoredTail() {
        val full = "1581FA8JC264600B31QZ"
        val prefs = inMemoryPreferences(
            mapOf(AircraftSerialGuard.KEY_SERIAL to "FA8JC264600B31QZ")
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = null,
            observedSerial = full,
            nowMs = 10_000L
        )

        assertTrue(update.serialChanged)
        assertEquals(full, prefs.getString(AircraftSerialGuard.KEY_SERIAL, ""))
    }

    @Test
    fun scheduledStatisticsSnapshotSeesThePersistedDumlIdentity() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA341",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Mini 5 Pro",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581F9DEC25AQ02998T5"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", "DJI Avata 360"),
            observedSerial = "1581FAKE000000000001",
            nowMs = 10_000L
        )
        // captureAircraftSerialFromDuml calls scheduleUpload only after this
        // synchronous in-memory preference update returns.
        val scheduledSnapshot = UsageStatistics.aircraftIdentitySnapshot(prefs)

        assertTrue(update.changed)
        assertEquals("1581FAKE000000000001", scheduledSnapshot.serial)
        assertEquals("WA530", scheduledSnapshot.modelCode)
        assertEquals("DJI Avata 360", scheduledSnapshot.modelName)
    }

    private fun inMemoryPreferences(initial: Map<String, Any?>): SharedPreferences {
        val values = initial.toMutableMap()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                    values[arguments[0] as String] = arguments[1]
                    proxy
                }
                "remove" -> {
                    values.remove(arguments[0] as String)
                    proxy
                }
                "clear" -> {
                    values.clear()
                    proxy
                }
                "commit" -> true
                "apply" -> null
                "toString" -> "InMemorySharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error("Unsupported editor method: ${method.name}")
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { proxy, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[arguments[0] as String] as? String ?: arguments[1]
                "getStringSet" -> values[arguments[0] as String] ?: arguments[1]
                "getInt" -> values[arguments[0] as String] as? Int ?: arguments[1]
                "getLong" -> values[arguments[0] as String] as? Long ?: arguments[1]
                "getFloat" -> values[arguments[0] as String] as? Float ?: arguments[1]
                "getBoolean" -> values[arguments[0] as String] as? Boolean ?: arguments[1]
                "contains" -> values.containsKey(arguments[0] as String)
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "InMemorySharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error("Unsupported preferences method: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test
    fun aSerialThatNamesAnotherAircraftDropsTheModelItLeftBehind() {
        // The serial is asked for now, so it arrives even when nothing named
        // the model. Keeping the old model beside it filed the new aircraft
        // under the old one — and a stored model stops the next window from
        // listening, so the pairing then never got corrected.
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA151",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to "1581FB34C25CF0032AAG"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = null,
            observedSerial = "1581FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        assertTrue(update.serialChanged)
        assertEquals("1581FA8JC264600B31QZ", update.currentSerial)
        // Model gone, so the identity is incomplete and the discovery beat
        // will listen for the new aircraft's model.
        assertTrue(update.modelChanged)
        assertEquals("", update.currentModel.modelCode)
        assertEquals("", update.currentModel.modelName)
        assertEquals("", prefs.getString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, ""))
    }

    @Test
    fun aModelNamedForTheNewAircraftIsKeptWithIt() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA151",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to "1581FB34C25CF0032AAG"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", "DJI Avata 360"),
            observedSerial = "1581FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        assertEquals("1581FA8JC264600B31QZ", update.currentSerial)
        assertEquals("WA530", update.currentModel.modelCode)
    }

    @Test
    fun theSameAircraftSpelledTwoWaysKeepsItsModel() {
        val full = "1581FA8JC264600B31QZ"
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA530",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Avata 360",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to full
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = null,
            observedSerial = full.removePrefix("1581"),
            nowMs = 10_000L
        )

        assertFalse(update.modelChanged)
        assertEquals("WA530", update.currentModel.modelCode)
    }

    @Test
    fun aSwapDoesNotInheritACodeForAModelThatOnlyGaveItsName() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA151",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to "DJI Lito X1",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML,
                AircraftSerialGuard.KEY_SERIAL to "1581FB34C25CF0032AAG"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("", "DJI Avata 360"),
            observedSerial = "1581FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        // The new aircraft named itself but gave no code; keeping WA151 would
        // pair the new name with the previous aircraft's code.
        assertEquals("", update.currentModel.modelCode)
        assertEquals("DJI Avata 360", update.currentModel.modelName)
    }

    @Test
    fun aSwapBetweenTwoOfTheSameModelDropsTheScreenNameOfTheFirst() {
        val prefs = inMemoryPreferences(
            mapOf(
                FccViewModel.PREF_AIRCRAFT_MODEL_CODE to "WA530",
                FccViewModel.PREF_AIRCRAFT_MODEL_NAME to
                    "DJI Avata 360 Enhanced Transmission edition",
                FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE to
                    FccViewModel.AIRCRAFT_MODEL_SOURCE_UI,
                AircraftSerialGuard.KEY_SERIAL to "1581FB34C25CF0032AAG"
            )
        )

        val update = AircraftIdentityPreferences.updateFromDuml(
            prefs = prefs,
            observedModel = AircraftModelIdentity("WA530", ""),
            observedSerial = "1581FA8JC264600B31QZ",
            nowMs = 10_000L
        )

        // Same model, different aircraft: the edition name belonged to the one
        // that left.
        assertEquals("WA530", update.currentModel.modelCode)
        assertEquals("DJI Avata 360", update.currentModel.modelName)
    }
}
