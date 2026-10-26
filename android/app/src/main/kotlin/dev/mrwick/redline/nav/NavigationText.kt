package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.flowOf

/** Static six-character instructions; never reinterpret an exit ordinal as an angle. */
internal fun navigationText(parsed: ParsedNavData): String {
    val label = when (parsed.navigationState) {
        MapsNavigationState.STARTING -> "START"
        MapsNavigationState.REROUTING -> "REROUT"
        MapsNavigationState.ARRIVED -> "ARRIVE"
        MapsNavigationState.NAVIGATING -> when {
            parsed.roundaboutExit != null -> {
                val exit = "EXT${parsed.roundaboutExit}"
                if (exit.length <= 4) exit else "ROUND"
            }
            MapplsIdGuesser.isRoundaboutExit(parsed.streetName) -> "EXIT"
            else -> when (parsed.maneuverId) {
                1, 13 -> "LEFT"
                4, 14 -> "RIGHT"
                2, 29 -> "HARD L"
                5, 30 -> "HARD R"
                3 -> "SL-L"
                6 -> "SL-R"
                7, 34 -> {
                    val instruction = parsed.streetName.orEmpty().lowercase(Locale.ROOT)
                        .replace(Regex("[\u2010-\u2014]"), "-")
                    if (Regex("""\b(?:left u[- ]turn|u[- ]turn left)\b""").containsMatchIn(instruction)) {
                        "U-LEFT"
                    } else "U-TURN"
                }
                36, 39 -> "U-RGHT"
                11, 27 -> "KEEP L"
                12, 28 -> "KEEP R"
                31 -> "MERG L"
                32 -> "MERG R"
                38 -> "RAMP L"
                10 -> "RAMP R"
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
                        else -> "NO DIR"
                    }
                }
            }
        }
    }
    return label.padEnd(6, ' ')
}

internal fun withNavigationText(frame: NavFrame?, parsed: ParsedNavData?, enabled: Boolean): NavFrame? {
    if (!enabled || frame == null || parsed == null || frame.traceId == null ||
        frame.traceId != parsed.traceId
    ) return frame
    if (parsed.navigationState == MapsNavigationState.NAVIGATING &&
        parsed.roundaboutExit == null && parsed.maneuverId in setOf(1, 4, 8, 13, 14, 33)
    ) return frame
    val text = navigationText(parsed)
    if (text == "NO DIR") return frame
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
