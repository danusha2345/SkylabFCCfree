package com.freefcc.app

import android.content.SharedPreferences
import java.util.Locale

internal data class AircraftIdentityPreferenceUpdate(
    val previousModel: AircraftModelIdentity,
    val currentModel: AircraftModelIdentity,
    val previousSerial: String,
    val currentSerial: String,
    val modelChanged: Boolean,
    val serialChanged: Boolean
) {
    val changed: Boolean
        get() = modelChanged || serialChanged
}

/** Applies one passive-link identity observation before statistics are scheduled. */
internal object AircraftIdentityPreferences {
    /**
     * DJI Fly prints editions the catalog does not carry, such as
     * `DJI Avata 360 Enhanced Transmission edition`. A screen name that adds
     * whole words to the observed one is that same aircraft spelled more
     * precisely. The added word must start a new word: model names differ by a
     * suffix — `DJI Air 3` and `DJI Air 3S`, `DJI Avata` and `DJI Avata 360` —
     * and reading those as one aircraft would hide a real swap.
     */
    private fun namesAgree(screenName: String, observedName: String): Boolean {
        val screen = screenName.uppercase(Locale.US)
        val observed = observedName.uppercase(Locale.US)
        return screen == observed || screen.startsWith("$observed ")
    }

    fun updateFromDuml(
        prefs: SharedPreferences,
        observedModel: AircraftModelIdentity?,
        observedSerial: String?,
        nowMs: Long
    ): AircraftIdentityPreferenceUpdate {
        val previousCode = prefs
            .getString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, "")
            .orEmpty()
        val previousName = prefs
            .getString(FccViewModel.PREF_AIRCRAFT_MODEL_NAME, "")
            .orEmpty()
        val previousSource = prefs
            .getString(FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE, "")
            .orEmpty()
        val previousSerial = prefs.getString(AircraftSerialGuard.KEY_SERIAL, "").orEmpty()

        val observedCode = observedModel?.modelCode
            .orEmpty()
            .trim()
            .uppercase(Locale.US)
        val observedName = observedModel?.modelName
            .orEmpty()
            .trim()
            .ifEmpty { AircraftModelCatalog.nameForCode(observedCode) }
        val hasModelObservation = observedCode.isNotEmpty() || observedName.isNotEmpty()

        // The screen name outranks the bus name only while it can still belong
        // to the aircraft the bus reports. With no code stored, whatever names
        // the observed code — the bus itself, or the catalog when the bus sent
        // only a code — settles it: `DJI Lito X1` is not what a WA530 is
        // called, so a name the previous aircraft left behind does not survive.
        // A code nobody can name contradicts nothing, which keeps the verbatim
        // screen name of an aircraft DJI never listed.
        val screenNameFitsObservedCode = when {
            observedCode.isEmpty() || observedCode == previousCode -> true
            previousCode.isNotEmpty() -> false
            else -> observedName.isEmpty() || namesAgree(previousName, observedName)
        }
        val screenNameIsCurrent =
            previousSource == FccViewModel.AIRCRAFT_MODEL_SOURCE_UI &&
                previousName.isNotEmpty() &&
                screenNameFitsObservedCode
        // A serial that names a different aircraft proves the swap by itself.
        // The serial can now be asked for, so it arrives even when nothing
        // named the model: keeping the previous model beside it would file the
        // new aircraft under the old one, and — because a stored model stops
        // the next window from listening — that pairing would then never be
        // corrected. Dropping the model leaves the identity incomplete, which
        // is what puts the model back on the discovery beat.
        val acceptedSerialForSwap = observedSerial.orEmpty().trim()
        val confirmedSerialSwap = acceptedSerialForSwap.isNotEmpty() &&
            previousSerial.isNotEmpty() &&
            !AircraftSerialForms.sameAircraft(acceptedSerialForSwap, previousSerial)
        // Across a proven swap nothing about the model is inherited. Filling a
        // missing half from the previous aircraft is how the mixed identities
        // appear: a name with no code keeps the old code, and a second
        // aircraft of the same model keeps the first one's screen name.
        val currentCode = when {
            confirmedSerialSwap -> observedCode
            else -> observedCode.ifEmpty { previousCode }
        }
        val currentName = when {
            confirmedSerialSwap -> observedName
            screenNameIsCurrent -> previousName
            observedName.isNotEmpty() -> observedName
            observedCode.isNotEmpty() && observedCode != previousCode -> ""
            else -> previousName
        }
        val modelChanged = (hasModelObservation || confirmedSerialSwap) &&
            (currentCode != previousCode || currentName != previousName)
        val confirmedModelSwap = observedCode.isNotEmpty() && when {
            previousCode.isNotEmpty() -> observedCode != previousCode
            // No code was ever stored, so the contradicted screen name is the
            // only evidence of the swap — and it is enough to drop the S/N the
            // previous aircraft left behind.
            else -> previousName.isNotEmpty() && !screenNameFitsObservedCode
        }

        val acceptedSerial = observedSerial.orEmpty()
        val serialIsPreviousAircraft =
            AircraftSerialForms.sameAircraft(acceptedSerial, previousSerial)
        val currentSerial = when {
            // A code that proves a swap, next to the number of the aircraft
            // that just left — in either spelling — is the bus still repeating
            // the previous drone. Neither spelling may survive the swap.
            confirmedModelSwap && (acceptedSerial.isEmpty() || serialIsPreviousAircraft) -> ""
            acceptedSerial.isEmpty() -> previousSerial
            // The same aircraft spelled two ways is not a new aircraft: keep
            // the fuller spelling and leave everything else alone.
            serialIsPreviousAircraft ->
                AircraftSerialForms.preferred(previousSerial, acceptedSerial)
            else -> acceptedSerial
        }
        val serialChanged = currentSerial != previousSerial

        if (confirmedSerialSwap || confirmedModelSwap) {
            // The parameter name this aircraft answers to is a fact about the
            // aircraft, so a model swap must clear it even before the new S/N
            // arrives. Keeping it would
            // send the first write of the new aircraft to a name it may not
            // have — a switch that reports success and does nothing.
            ParameterAddress.forgetAllConfirmed()
        }
        if (modelChanged || serialChanged) {
            prefs.edit().apply {
                if (modelChanged) {
                    if (currentCode.isEmpty()) {
                        remove(FccViewModel.PREF_AIRCRAFT_MODEL_CODE)
                    } else {
                        putString(FccViewModel.PREF_AIRCRAFT_MODEL_CODE, currentCode)
                    }
                    if (currentName.isEmpty()) {
                        remove(FccViewModel.PREF_AIRCRAFT_MODEL_NAME)
                    } else {
                        putString(FccViewModel.PREF_AIRCRAFT_MODEL_NAME, currentName)
                    }
                    putString(
                        FccViewModel.PREF_AIRCRAFT_MODEL_SOURCE,
                        FccViewModel.AIRCRAFT_MODEL_SOURCE_DUML
                    )
                    putLong(FccViewModel.PREF_AIRCRAFT_MODEL_AT, nowMs)
                }
                // A swap proven by the serial drops the previous one too, so
                // the frames the aircraft that just left keeps putting on the
                // bus cannot put it back.
                if ((confirmedModelSwap || confirmedSerialSwap) && previousSerial.isNotEmpty()) {
                    AircraftSerialGuard.rememberDropped(this, previousSerial, nowMs)
                }
                if (currentSerial.isNotEmpty()) {
                    putString(AircraftSerialGuard.KEY_SERIAL, currentSerial)
                }
            }.apply()
        }

        return AircraftIdentityPreferenceUpdate(
            previousModel = AircraftModelIdentity(previousCode, previousName),
            currentModel = AircraftModelIdentity(currentCode, currentName),
            previousSerial = previousSerial,
            currentSerial = currentSerial,
            modelChanged = modelChanged,
            serialChanged = serialChanged
        )
    }
}
