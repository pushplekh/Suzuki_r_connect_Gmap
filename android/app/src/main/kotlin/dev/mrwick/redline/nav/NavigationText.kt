package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.flowOf

/**
 * Static instructions for the time field, drawn as five cells `c0 c1 | c2 c3 c4` with a
 * fixed bar; the sixth byte is not displayed. Labels are at most five characters, split
 * around the bar. Never reinterpret an exit ordinal as an angle.
 */
internal fun navigationText(parsed: ParsedNavData): String {
    val label = when (parsed.navigationState) {
        MapsNavigationState.STARTING -> "START"
        MapsNavigationState.REROUTING -> "RERTE"
        MapsNavigationState.ARRIVED -> "ARRVD"
        MapsNavigationState.NAVIGATING -> when {
            parsed.roundaboutExit != null -> {
                val exit = "EXIT${parsed.roundaboutExit}"
                if (exit.length <= MAX_LABEL) exit else "ROUND"
            }
            MapplsIdGuesser.isRoundaboutExit(parsed.streetName) -> "EXIT"
            else -> when (parsed.maneuverId) {
                1, 13 -> "LEFT"
                4, 14 -> "RIGHT"
                2, 29 -> "HDLFT"
                5, 30 -> "HDRGT"
                3 -> "SLLFT"
                6 -> "SLRGT"
                7, 34 -> {
                    val instruction = parsed.streetName.orEmpty().lowercase(Locale.ROOT)
                        .replace(Regex("[\u2010-\u2014]"), "-")
                    if (Regex("""\b(?:left u[- ]turn|u[- ]turn left)\b""").containsMatchIn(instruction)) {
                        "UTLFT"
                    } else "UTURN"
                }
                36, 39 -> "UTRGT"
                11, 27 -> "KPLFT"
                12, 28 -> "KPRGT"
                31 -> "MGLFT"
                32 -> "MGRGT"
                38 -> "RPLFT"
                10 -> "RPRGT"
                8, 33 -> "AHEAD"
                40 -> "NORTH"
                41 -> "NE"
                42 -> "EAST"
                15 -> "SE"
                16 -> "SOUTH"
                17 -> "SW"
                18 -> "WEST"
                19 -> "NW"
                in 20..26, 45, in 47..52 -> MapplsIdGuesser.roundaboutDirection(parsed.streetName) ?: "ROUND"
                else -> {
                    val text = parsed.streetName.orEmpty().lowercase(Locale.ROOT)
                        .replace('\u00a0', ' ').replace('\u202f', ' ')
                        .replace(Regex("""^(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft)\b\s*[-·:]?\s*"""), "")
                    when {
                        MapplsIdGuesser.isUndirectedJoin(parsed.streetName) -> "MERGE"
                        Regex("""^(?:head|proceed)\s+towards?\b""").containsMatchIn(text) -> "HEAD"
                        else -> NO_DIRECTION
                    }
                }
            }
        }
    }
    return label.take(MAX_LABEL).padEnd(6, ' ')
}

private const val MAX_LABEL = 5
private const val NO_DIRECTION = "NODIR"

internal fun withNavigationText(frame: NavFrame?, parsed: ParsedNavData?, enabled: Boolean): NavFrame? {
    if (!enabled || frame == null || parsed == null || frame.traceId == null ||
        frame.traceId != parsed.traceId
    ) return frame
    if (parsed.navigationState == MapsNavigationState.NAVIGATING &&
        parsed.roundaboutExit == null && parsed.maneuverId in setOf(1, 4, 8, 13, 14, 33)
    ) return frame
    val text = navigationText(parsed)
    if (text.trimEnd() == NO_DIRECTION) return frame
    val exitNow = parsed.navigationState == MapsNavigationState.NAVIGATING &&
        MapplsIdGuesser.isRoundaboutExit(parsed.streetName) &&
        !parsed.nextDistanceKnown && frame.distNext.isBlank()
    return frame.copy(
        eta = text,
        distNext = if (exitNow) "NOW " else frame.distNext,
        distNextUnit = if (exitNow) " " else frame.distNextUnit,
    )
}

internal fun navigationDisplayFlow(
    frames: Flow<NavFrame?>,
    parsed: Flow<ParsedNavData?>,
    enabled: Flow<Boolean>,
    freshness: Flow<NavigationFreshness> = flowOf(NavigationFreshness()),
): Flow<NavFrame?> = combine(frames, parsed, enabled, freshness) { frame, data, text, age ->
    DisplayUpdate(frame, data, text, age.stale)
}.transform { (frame, data, text, stale) ->
    if (stale && data == null) {
        emit(NavFrame(
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = "    ",
            distNextUnit = " ",
            eta = "STALE ",
            distTotal = "    ",
            distTotalUnit = " ",
            status = "1",
            continueFlag = "1",
        ))
        return@transform
    }
    // Independently delivered flows can briefly belong to different Maps updates.
    if (frame?.traceId != null && frame.traceId != data?.traceId) return@transform
    emit(withNavigationText(frame, data, text))
}

private data class DisplayUpdate(
    val frame: NavFrame?,
    val data: ParsedNavData?,
    val text: Boolean,
    val stale: Boolean,
)
