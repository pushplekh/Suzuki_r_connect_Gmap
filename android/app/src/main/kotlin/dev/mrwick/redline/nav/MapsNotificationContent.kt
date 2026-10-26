package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.SuzukiProfile
import java.util.Locale

enum class MapsNavigationState { NAVIGATING, STARTING, REROUTING, ARRIVED }

/** Pure interpretation of the notification fields, including captured ProgressStyle updates. */
internal data class MapsNotificationContent(
    val title: String?,
    val text: String?,
    val subText: String?,
    val progress: Int = -1,
    val progressMax: Int = -1,
    val indeterminate: Boolean = false,
    val hasTemplate: Boolean = true,
) {
    private val cleanTitle = title.orEmpty().trim().lowercase(Locale.ROOT)
    val state: MapsNavigationState
        get() = when {
            cleanTitle.startsWith("rerouting") || cleanTitle.startsWith("recalculating") ->
                MapsNavigationState.REROUTING
            cleanTitle.startsWith("starting navigation") -> MapsNavigationState.STARTING
            // Both recorded trips ended with this non-ProgressStyle notification.
            cleanTitle == "arriving" && text.orEmpty().trim().startsWith("at ", ignoreCase = true) &&
                !hasTemplate && progressMax <= 0 -> MapsNavigationState.ARRIVED
            listOfNotNull(title, text).any {
                MapplsIdGuesser.fromTextOrNull(it) == MapplsIdGuesser.DESTINATION_ARRIVED_ID
            } -> MapsNavigationState.ARRIVED
            else -> MapsNavigationState.NAVIGATING
        }

    val instruction: String?
        get() = listOfNotNull(title, text)
            .firstOrNull { MapplsIdGuesser.fromTextOrNull(it) != null } ?: title ?: text

    val isHeadingOnly: Boolean
        get() {
            val clean = instruction.orEmpty().trim()
                .replace('\u00a0', ' ').replace('\u202f', ' ')
                .replace(Regex("""(?i)^(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft)\b\s*[-·:]?\s*"""), "")
            return Regex("""(?i)^(?:head|proceed)\s+(?:(?:north|south)(?:[- ]?(?:east|west))?|east|west|towards?)\b""")
                .containsMatchIn(clean)
        }

    private val nextRaw: String?
        get() = listOfNotNull(title, text).firstNotNullOfOrNull {
            // Only an instruction distance prefix, never a number in a road name.
            val clean = it.replace('\u00a0', ' ').replace('\u202f', ' ')
            val distance = """\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft|meters?|kilometers?|miles?|feet)\b"""
            Regex("""(?i)^\s*(?:in\s+)?$distance""").find(clean)?.value
                ?: Regex("""(?i)^(?:turn|keep|bear|continue|head)\b.*?\bin\s+($distance)""")
                    .find(clean)?.groupValues?.get(1)
        }

    val hasRouteProgress: Boolean
        get() = !indeterminate && progressMax > 0 && progress in 0..progressMax

    fun parse(profile: SuzukiProfile, classifiedId: Int?): ParsedNavData {
        val active = state == MapsNavigationState.NAVIGATING
        val arrived = state == MapsNavigationState.ARRIVED
        // Only explicit compass bearings use compass glyphs, never an inferred
        // direction from a road name or the phone's orientation.
        val headingOnly = isHeadingOnly
        val explicitHeading = MapplsIdGuesser.fromTextOrNull(instruction)?.takeIf { it in 50..57 }
        val next = if (arrived) "0000" to "M"
            else if (headingOnly) "    " to "M"
            else nextRaw?.let(::normalizeDistance) ?: ("    " to "M")
        val totalRaw = subText?.let {
            Regex("""(?i)\d+(?:[.,]\d+)?\s*(?:km|mi|ft|m)\b""")
                .find(it.replace('\u00a0', ' ').replace('\u202f', ' '))?.value
        }
        val total = when {
            arrived -> "0000" to "M"
            !active -> "    " to "M"
            totalRaw != null -> normalizeDistance(totalRaw)
            hasRouteProgress -> normalizeDistance("${progressMax - progress} m")
            else -> "    " to "M"
        }
        return ParsedNavData(
            maneuverId = when {
                arrived -> 9
                !active -> ManeuverMap.NO_MANEUVER_BYTE
                headingOnly -> explicitHeading?.let(profile::maneuverByte) ?: ManeuverMap.NO_MANEUVER_BYTE
                else -> classifiedId?.let(profile::maneuverByte) ?: ManeuverMap.NO_MANEUVER_BYTE
            },
            distNext = if (active || arrived) next.first else "    ",
            distNextUnit = next.second,
            eta = if (active) normalizeEta(subText, twelveHour = true) else "      ",
            distTotal = total.first,
            distTotalUnit = total.second,
            streetName = instruction,
            status = when {
                arrived -> "5"
                !active -> "2"
                else -> "1"
            },
            navigationState = state,
            nextDistanceKnown = active && !headingOnly && next.first.isNotBlank(),
            routeRemainingMetres = if (arrived) 0 else if (active && hasRouteProgress) progressMax - progress else null,
            routeTravelledMetres = if (active && hasRouteProgress) progress else null,
            totalDistanceSource = when {
                arrived -> "arrival"
                !active -> "unavailable-during-${state.name.lowercase()}"
                totalRaw != null -> "subText"
                hasRouteProgress -> "route-progressMax-minus-progress"
                else -> "unavailable"
            },
            roundaboutExit = MapplsIdGuesser.roundaboutExit(instruction),
        )
    }
}
