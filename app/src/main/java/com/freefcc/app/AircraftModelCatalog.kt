package com.freefcc.app

import java.util.Locale

/** A product code and the commercial name that belongs to it. */
internal data class AircraftModelMatch(
    val code: String,
    val name: String
)

/**
 * Known DJI aircraft product codes and the names DJI Fly prints for them.
 *
 * The catalog is read in both directions. DJI Fly shows the code and the
 * commercial name on screen, so the accessibility service can recognise both
 * without opening a DUML port; the passive DUML path uses the same table to
 * name a code that arrived without a `03:34` string.
 */
internal object AircraftModelCatalog {

    /**
     * WM240/245/246/247 are the Mavic 2 platform. The exact variant is not
     * distinguishable from the code alone here, so all four share one name and
     * the reverse lookup deliberately refuses to guess a code from it.
     *
     * Codes and names below come from DJI Fly's own resources — its font assets
     * pair the two namespaces (`fly_uav165_wa151`) and `product_official_name_*`
     * gives the wording. See `docs/DJI_FLY_APK_IDENTITY_MAP.md`. The Cine
     * variants WM260C and WM261C are deliberately absent: DJI Fly gives them the
     * same names as WM260 and WM261, which would make those names ambiguous and
     * cost the reverse lookup two of the most common models.
     */
    private val NAME_BY_CODE = mapOf(
        "WA020" to "DJI Neo 2",
        "WA140" to "DJI Mini 4 Pro",
        "WA141" to "DJI Flip",
        "WA150" to "DJI Mini 5 Pro",
        "WA151" to "DJI Lito X1",
        "WA152" to "DJI Lito 1",
        "WA233" to "DJI Air 3",
        "WA234" to "DJI Air 3S",
        "WA341" to "DJI Mavic 4 Pro",
        "WA520" to "DJI Avata 2",
        "WA521" to "DJI Neo",
        "WA530" to "DJI Avata 360",
        "WM160" to "Mavic Mini",
        "WM161" to "DJI Mini 2",
        "WM1615" to "DJI Mini 2 SE",
        "WM1617" to "DJI Mini 4K",
        "WM162" to "DJI Mini 3 Pro",
        "WM163" to "DJI Mini 3",
        "WM169" to "DJI Avata",
        "WM170" to "DJI FPV",
        "WM231" to "Mavic Air 2",
        "WM232" to "DJI Air 2S",
        "WM260" to "DJI Mavic 3",
        "WM2605" to "DJI Mavic 3 Classic",
        "WM261" to "DJI Mavic 3 Pro",
        "WM265E" to "DJI Mavic 3E",
        "WM265M" to "DJI Mavic 3M",
        "WM265T" to "DJI Mavic 3T",
        "WM240" to "DJI Mavic 2",
        "WM245" to "DJI Mavic 2",
        "WM246" to "DJI Mavic 2",
        "WM247" to "DJI Mavic 2"
    )

    private val CODE_REGEX = Regex("(?<![0-9A-Z])W[AM][0-9]{3}[0-9A-Z]?(?![0-9A-Z])")
    private val WHITESPACE = Regex("\\s+")
    private val DISPLAY_VERSION_SUFFIX = Regex("\\s+V\\d+(?:\\.\\d+)*$", RegexOption.IGNORE_CASE)

    /** Names that map to exactly one code; ambiguous ones are dropped. */
    private val CODE_BY_NAME: Map<String, String> = run {
        val byName = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        NAME_BY_CODE.forEach { (code, name) ->
            val key = normalize(name)
            if (byName.put(key, code) != null) ambiguous += key
        }
        ambiguous.forEach(byName::remove)
        byName
    }

    /**
     * Search strings ordered longest-first so `DJI Air 3S` wins over
     * `DJI Air 3`. The `DJI`-less form is only searched when it is long enough
     * to be unambiguous on a busy screen — `Neo` or `Flip` alone are not.
     */
    private val ALIASES: List<Pair<String, String>> = NAME_BY_CODE.values
        .distinct()
        .flatMap { name ->
            val full = normalize(name)
            val short = full.removePrefix("DJI ")
            buildList {
                add(full to name)
                if (short.length >= 6 && short != full) add(short to name)
            }
        }
        .sortedByDescending { it.first.length }

    /**
     * A `DJI …` product name of up to three words. The name is taken verbatim,
     * so an aircraft the catalog has never heard of still gets its real name.
     */
    private val PRODUCT_NAME_REGEX =
        Regex("DJI(?: [A-Za-z0-9][A-Za-z0-9+]{0,11}){1,3}", RegexOption.IGNORE_CASE)

    /**
     * First word after `DJI` for products that are not aircraft. Without this
     * the app's own name, the store banner or a paired accessory would be read
     * as the connected drone.
     */
    private val NON_AIRCRAFT_WORDS = setOf(
        "FLY", "GO", "GO4", "PILOT", "ASSISTANT", "MIMO", "STORE", "ACCOUNT",
        "CARE", "FORUM", "ACADEMY", "SUPPORT", "SERVICE", "VIRTUAL", "GOGGLES",
        "MOTION", "RC", "RM", "GL", "MIC", "OSMO", "POCKET", "ACTION", "POWER",
        "TERRA", "CELLULAR", "TRANSMISSION", "SDK", "INC", "LTD", "LIMITED",
        "COMPANY", "TECHNOLOGY", "SIMULATOR", "GLOBAL"
    )

    /** Generic DJI Fly captions that look like model names but are not aircraft. */
    private val NON_AIRCRAFT_NAMES = setOf("DJI LITO")

    /**
     * Families that never name an aircraft by themselves — every member
     * carries a number. Models that really are two words, such as `DJI Neo`,
     * `DJI Flip` and `DJI Avata`, are deliberately absent.
     */
    private val AIRCRAFT_FAMILY_WORDS = setOf(
        "MAVIC", "AIR", "MINI", "PHANTOM", "INSPIRE", "MATRICE"
    )

    /** Non-numeric words that DJI puts after a family, as in `Mini 4 Pro` or `Mavic 3 Classic`. */
    private val MODEL_WORDS = setOf(
        "PRO", "SE", "CLASSIC", "CINE", "PLUS", "MAX", "ENTERPRISE", "E", "T", "M", "S"
    )

    private val CATALOG_NAMES: Set<String> = NAME_BY_CODE.values.map(::normalize).toSet()

    fun nameForCode(code: String): String =
        NAME_BY_CODE[code.trim().uppercase(Locale.US)].orEmpty()

    /**
     * The code that may stay stored next to [name]. Name and code are one
     * identity, so a code read for another aircraft must not outlive it: an
     * Avata 360 named on screen keeps no WM169 from the previous session.
     * Returns an empty string when the stored code has to go.
     */
    fun codeFor(
        name: String,
        observedCode: String,
        storedName: String,
        storedCode: String
    ): String {
        if (observedCode.isNotEmpty()) return observedCode
        if (name.isEmpty() || storedCode.isEmpty()) return storedCode
        val catalogName = nameForCode(storedCode)
        val contradicted = catalogName.isNotEmpty() && !catalogName.equals(name, ignoreCase = true)
        return if (contradicted || !name.equals(storedName, ignoreCase = true)) "" else storedCode
    }

    /**
     * Finds the aircraft identity in the labels of a DJI app screen.
     *
     * The name printed on screen wins after removing a trailing DJI Fly display
     * revision such as `V01`. The catalog is only consulted when the screen
     * shows a code without a name, or to turn a known name back into its code.
     */
    fun findOnScreen(texts: Collection<CharSequence>): AircraftModelMatch? {
        val labels = texts.map(::flatten).filter(String::isNotEmpty)
        if (labels.isEmpty()) return null

        val haystack = labels.joinToString(" | ").uppercase(Locale.US)
        val code = CODE_REGEX.find(haystack)?.value.orEmpty()
        // A product code is a stronger identity than an unrelated generic DJI
        // caption elsewhere on the screen.
        val name = nameForCode(code).ifEmpty {
            screenName(labels)
                ?: ALIASES.firstOrNull { containsWord(haystack, it.first) }?.second
                ?: ""
        }

        val resolvedCode = code.ifEmpty { CODE_BY_NAME[normalize(name)].orEmpty() }
        return if (resolvedCode.isEmpty() && name.isEmpty()) {
            null
        } else {
            AircraftModelMatch(resolvedCode, name)
        }
    }

    /**
     * The name as the screen spells it. A label that is nothing but a product
     * name is the device caption; otherwise the name is only trusted when it
     * sits in the same label as a product code.
     */
    private fun screenName(labels: List<String>): String? {
        labels.forEach { label ->
            val name = withoutDisplayVersion(label)
            if (isAircraftName(name)) return name
        }
        labels.forEach { label ->
            if (!CODE_REGEX.containsMatchIn(label.uppercase(Locale.US))) return@forEach
            PRODUCT_NAME_REGEX.find(label)?.value?.let {
                val name = withoutDisplayVersion(it)
                if (isAircraftName(name)) return name
            }
        }
        // Last: a product name embedded in a sentence, e.g. "Connected to
        // DJI Avata 360". Still filtered by the non-aircraft word list.
        labels.forEach { label ->
            PRODUCT_NAME_REGEX.find(label)?.value?.let {
                val name = withoutDisplayVersion(it)
                if (isAircraftName(name)) return name
            }
        }
        return null
    }

    /** DJI Fly may append a display revision such as `V01`; it is not part of the model name. */
    private fun withoutDisplayVersion(value: String): String =
        flatten(value).replace(DISPLAY_VERSION_SUFFIX, "")

    private fun isAircraftName(value: String): Boolean {
        if (value.length > 28 || !PRODUCT_NAME_REGEX.matches(value)) return false
        if (normalize(value) in NON_AIRCRAFT_NAMES) return false
        val words = value.split(' ')
        if (words.size < 2) return false
        val family = words[1].uppercase(Locale.US)
        if (family in NON_AIRCRAFT_WORDS) return false
        // A family on its own is not an aircraft. Live logs showed `DJI Mavic`
        // and `DJI Air` picked off a screen while a Mini 5 Pro was connected;
        // read as a model they replace the real one and look like a swap.
        // Any other bare two-word caption must be a model the catalog knows:
        // `DJI Neo` is, `DJI Aircraft` or `DJI Terms` are not.
        if (words.size == 2) {
            return family !in AIRCRAFT_FAMILY_WORDS && normalize(value) in CATALOG_NAMES
        }
        // Every word after the family must look like a model designation.
        // Field reports carried sentence fragments cut to three words —
        // `DJI Aircraft Terms of`, `DJI Terms of Use`, `DJI Lito X1 donan`,
        // `DJI Mavic3 Series` — which a real model name never contains.
        return words.drop(2).all(::isModelWord)
    }

    private fun isModelWord(word: String): Boolean {
        val upper = word.uppercase(Locale.US)
        return upper in MODEL_WORDS || (upper.length <= 6 && upper.any(Char::isDigit))
    }

    /** Collapses screen whitespace but keeps the text exactly as printed. */
    private fun flatten(value: CharSequence): String =
        value.toString()
            .replace('\u00A0', ' ')
            .replace(WHITESPACE, " ")
            .trim()

    private fun normalize(value: CharSequence): String =
        flatten(value).uppercase(Locale.US)

    /**
     * Substring match that refuses to fire inside a longer alphanumeric run,
     * and also refuses when a number follows: `DJI Avata` must not be reported
     * for a screen that says `DJI Avata 360`.
     */
    private fun containsWord(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val end = at + needle.length
            val before = haystack.getOrNull(at - 1)
            val after = haystack.getOrNull(end)
            val extendedByNumber =
                after == ' ' && haystack.getOrNull(end + 1)?.isDigit() == true
            if (!isWordChar(before) && !isWordChar(after) && !extendedByNumber) return true
            from = at + 1
        }
    }

    private fun isWordChar(value: Char?): Boolean =
        value != null && (value.isLetterOrDigit())
}

/** Requires a changed screen model to remain visible before it replaces stored identity. */
internal class AircraftModelObservationGate(
    private val confirmationMs: Long = 1_000L
) {
    private var candidateName = ""
    private var candidateSinceMs = 0L

    @Synchronized
    fun accepts(storedName: String, observedName: String, nowMs: Long): Boolean {
        if (observedName.isEmpty()) return false
        if (observedName == storedName) {
            candidateName = ""
            return true
        }
        if (observedName != candidateName) {
            candidateName = observedName
            candidateSinceMs = nowMs
            return false
        }
        return nowMs - candidateSinceMs >= confirmationMs
    }
}
