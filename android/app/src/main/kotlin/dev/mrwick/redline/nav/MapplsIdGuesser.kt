package dev.mrwick.redline.nav

import java.util.Locale

/**
 * Stage 1 of the maneuver pipeline: Google Maps instruction text → Mappls
 * maneuver ID (0..75).
 *
 * This is *our* heuristic — the Mappls SDK isn't on the phone, so we infer
 * what Mappls would have emitted from the human-readable text. Stage 2
 * ([ManeuverMap.mapplsIdToClusterByte]) then converts the Mappls ID to the
 * cluster byte the bike expects.
 *
 * IDs are checked against the original APK's instruction dictionaries; see
 * `docs/mappls-id-icons.md`. This
 * is still only an inference: Maps text is not a structured Mappls maneuver ID.
 */
object MapplsIdGuesser {

    /** Mappls ID for "straight / head / continue" — the safe default. */
    const val DEFAULT_MAPPLS_ID = 7
    /** Internal classification marker for explicit destination-arrival text. */
    const val DESTINATION_ARRIVED_ID = 40

    /** Best-effort instruction classification; null means no safe text match. */
    fun fromTextOrNull(instruction: String?): Int? {
        if (instruction.isNullOrBlank()) return null
        val s = instructionClause(instruction)

        fun containsAny(vararg phrases: String) = phrases.any(s::contains)

        return when {
            Regex("""^(?:(?:you have |you've )?arrived\b|destination (?:reached|arrived)\b|you have reached your destination\b)""")
                .containsMatchIn(s) -> DESTINATION_ARRIVED_ID
            isUndirectedJoin(instruction) -> null
            isRoundaboutInstruction(s) -> 72
            containsAny("u-turn right", "u turn right", "right u-turn", "right u turn", "make a u-turn right", "make a u turn right") -> 41
            containsAny("u-turn left", "u turn left", "left u-turn", "left u turn", "make a u-turn left", "make a u turn left") -> 6
            Regex("""\bu[- ]turn\b""").containsMatchIn(s) -> 41 // Rider-requested right default; side not proven.
            containsAny("ferry", "board the boat", "take the boat") -> 36
            containsAny("tunnel", "underpass") -> 37
            Regex("""\b(?:ramp|slip road)\b""").containsMatchIn(s) &&
                Regex("""\bleft\b""").containsMatchIn(s) -> 74
            Regex("""\b(?:ramp|slip road)\b""").containsMatchIn(s) &&
                Regex("""\bright\b""").containsMatchIn(s) -> 75
            containsAny("sharp right", "hard right") -> 4
            containsAny("sharp left", "hard left") -> 1
            containsAny("slight right", "slightly right", "bear right", "veer right") -> 5
            containsAny("slight left", "slightly left", "bear left", "veer left") -> 2
            containsAny("fork right", "right fork", "take the right branch") -> 20
            containsAny("fork left", "left fork", "take the left branch") -> 19
            containsAny("keep right", "stay right") -> 20
            containsAny("keep left", "stay left") -> 19
            Regex("""^join\s+(?:to |on )?(?:the )?right\b""").containsMatchIn(s) -> 16
            Regex("""^join\s+(?:to |on )?(?:the )?left\b""").containsMatchIn(s) -> 15
            // Legacy merge/continue variants share the OEM's left/right branch bytes.
            containsAny("merge right", "merge onto the right") -> 16
            containsAny("merge left", "merge onto the left") -> 15
            containsAny("t-junction", "t junction", "t-intersection", "t intersection") &&
                Regex("""\bleft\b""").containsMatchIn(s) -> 13
            containsAny("t-junction", "t junction", "t-intersection", "t intersection") &&
                Regex("""\bright\b""").containsMatchIn(s) -> 14
            isDirectedExit(s, "left") -> 74
            isDirectedExit(s, "right") -> 75
            containsAny("turn right", "turn onto the right", "turn onto right", "turn right onto") -> 3
            containsAny("turn left", "turn onto the left", "turn onto left", "turn left onto") -> 0
            containsAny("head north-east", "head northeast", "head north east", "proceed north-east", "proceed northeast", "proceed north east") -> 51
            containsAny("head north-west", "head northwest", "head north west", "proceed north-west", "proceed northwest", "proceed north west") -> 57
            containsAny("head south-east", "head southeast", "head south east", "proceed south-east", "proceed southeast", "proceed south east") -> 53
            containsAny("head south-west", "head southwest", "head south west", "proceed south-west", "proceed southwest", "proceed south west") -> 55
            containsAny("head north", "proceed north") -> 50
            containsAny("head south", "proceed south") -> 54
            containsAny("head east", "proceed east") -> 52
            containsAny("head west", "proceed west") -> 56
            containsAny("continue", "go straight", "straight ahead", "head toward", "head towards") -> DEFAULT_MAPPLS_ID
            else -> null
        }
    }

    /**
     * Compatibility helper for callers that need a fallback. Navigation
     * forwarding should use [fromTextOrNull] so unknown text is not mistaken
     * for a confirmed straight-ahead instruction.
     */
    fun fromText(instruction: String?): Int = fromTextOrNull(instruction) ?: DEFAULT_MAPPLS_ID

    fun roundaboutExit(instruction: String?): Int? {
        val text = instruction?.let(::instructionClause) ?: return null
        if (!isRoundaboutInstruction(text)) return null
        val match = Regex("""\b(?:take\s+(?:the\s+)?)?(\d+)(?:st|nd|rd|th)?\s+exit\b""").find(text)
        if (match != null) return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        return listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")
            .indexOfFirst { Regex("""\b$it\s+exit\b""").containsMatchIn(text) }
            .takeIf { it >= 0 }?.plus(1)
    }

    internal fun roundaboutDirection(instruction: String?): String? {
        val clause = instruction?.let(::instructionClause) ?: return null
        if (!isRoundaboutInstruction(clause)) return null
        return when {
            Regex("""\b(?:turn|bear|keep)\s+left\b""").containsMatchIn(clause) -> "RB L"
            Regex("""\b(?:turn|bear|keep)\s+right\b""").containsMatchIn(clause) -> "RB R"
            Regex("""\b(?:go straight|continue straight|straight ahead)\b""").containsMatchIn(clause) -> "RB FWD"
            else -> null
        }
    }

    internal fun isRoundaboutExit(instruction: String?): Boolean =
        instruction?.let(::instructionClause)?.let {
            Regex("""^exit\s+(?:the\s+)?(?:roundabout|traffic circle)\b""").containsMatchIn(it)
        } ?: false

    internal fun isUndirectedJoin(instruction: String?): Boolean =
        instruction?.let(::instructionClause)?.let {
            Regex("""^join\s+(?:with|onto)\b""").containsMatchIn(it) || it == "join"
        } ?: false

    private fun isRoundaboutInstruction(text: String): Boolean =
        text.contains("roundabout") || text.contains("traffic circle") ||
            Regex("""^at\s+[^,]+\s(?:cir\.?|circle|rotary),\s*take\s+(?:the\s+)?(?:\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth)\s+exit\b""")
                .containsMatchIn(text)

    private fun instructionClause(instruction: String): String {
        val normalized = instruction.lowercase(Locale.ROOT)
            .replace('\u00a0', ' ').replace('\u202f', ' ')
            .replace(Regex("[\u2010-\u2014]"), "-")
            .replace(Regex("\\s+"), " ").trim()
            .replace(
                Regex("""^(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft|meters?|kilometers?|miles?|feet)\b\s*[-·:]?\s*"""), "",
            )
        // A road name or landmark must not override the maneuver before it.
        return normalized.split(
            Regex("""\s+(?:(?:onto|on)\s+(?!(?:the\s+)?(?:left|right)\b)|(?:towards?|to (?:stay|continue|remain) on|after)\s+)"""), limit = 2,
        ).first()
    }

    private fun isDirectedExit(instruction: String, direction: String): Boolean {
        val patterns = when (direction) {
            "left" -> listOf(
                "exit left", "left exit", "take the left exit", "take exit left",
                "take the exit on the left", "take exit on the left", "take the ramp on the left",
            )
            else -> listOf(
                "exit right", "right exit", "take the right exit", "take exit right",
                "take the exit on the right", "take exit on the right", "take the ramp on the right",
            )
        }
        return patterns.any(instruction::contains)
    }
}
