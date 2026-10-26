package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.ClusterProtocol
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.SuzukiModel
import dev.mrwick.redline.protocol.SuzukiProfile
import org.junit.Assert.*
import org.junit.Test

class MapsNotificationContentTest {
    private val profile = SuzukiProfile(SuzukiModel.ACCESS, "SAS21")
    private fun parse(content: MapsNotificationContent) =
        content.parse(profile, MapplsIdGuesser.fromTextOrNull(content.instruction))

    @Test fun capturedFiftyMetresToTurnDoesNotJumpToWholeRouteDistance() {
        val before = parse(MapsNotificationContent("50\u00a0m - Turn right", null, "Arrive 2:39\u202fpm", 8, 1081))
        val near = parse(MapsNotificationContent("Turn right", null, "Arrive 2:39\u202fpm", 19, 1081))
        assertEquals("0050", before.distNext)
        assertEquals("    ", near.distNext)
        assertFalse(near.nextDistanceKnown)
        assertEquals(4, near.maneuverId)
        assertEquals("01.1", near.distTotal)
        assertEquals("K", near.distTotalUnit)
        assertEquals(1062, near.routeRemainingMetres)
        assertEquals(19, near.routeTravelledMetres)
        assertEquals("0239PM", near.eta)
        val decoded = NavFrame.decode(near.toNavFrame().encode())
        assertEquals("    ", decoded.distNext)
        assertEquals("01.1", decoded.distTotal)
    }

    @Test fun capturedNearLeftTurnKeepsArrowWithoutInventing667Metres() {
        val near = parse(MapsNotificationContent("Turn left onto Road 9", null, "Arrive 2:40 pm", 45, 712))
        assertEquals(1, near.maneuverId)
        assertEquals("    ", near.distNext)
        assertEquals("0667", near.distTotal)
    }

    @Test fun routeProgressIsIndependentOfWhichTurnIsActive() {
        val first = parse(MapsNotificationContent("Turn right", null, "Arrive 2:45 pm", 480, 913))
        val next = parse(MapsNotificationContent("400 m - Turn right", null, "Arrive 2:45 pm", 485, 913))
        assertEquals("    ", first.distNext)
        assertEquals("0400", next.distNext)
        assertEquals(433, first.routeRemainingMetres)
        assertEquals(428, next.routeRemainingMetres)
        assertEquals(4, first.maneuverId)
        assertEquals(4, next.maneuverId)
    }

    @Test fun rerouteSuppressesOldArrowDistanceAndInventedEtaThenRecovers() {
        val reroute = parse(MapsNotificationContent("Rerouting...", null, "Arrive ", 0, 1081))
        assertEquals(MapsNavigationState.REROUTING, reroute.navigationState)
        assertEquals(ManeuverMap.NO_MANEUVER_BYTE, reroute.maneuverId)
        assertEquals("2", reroute.status)
        assertEquals("    ", reroute.distNext)
        assertEquals("    ", reroute.distTotal)
        assertEquals("      ", reroute.eta)
        assertNull(reroute.routeRemainingMetres)
        val resumed = parse(MapsNotificationContent("80 m - Turn left", null, "Arrive 2:40 pm", 6, 712))
        assertEquals(MapsNavigationState.NAVIGATING, resumed.navigationState)
        assertEquals(1, resumed.maneuverId)
        assertEquals(706, resumed.routeRemainingMetres)
        assertEquals("0080", resumed.distNext)
    }

    @Test fun bothRecordedTerminalArrivingNotificationsProduceArrivalWireFrame() {
        for (place in listOf("at destination one", "at destination two")) {
            val arrived = parse(MapsNotificationContent("Arriving", place, null, 0, 0, hasTemplate = false))
            assertEquals(MapsNavigationState.ARRIVED, arrived.navigationState)
            val wire = ClusterProtocol.LEGACY_ACCESS_BURGMAN.toWire(arrived.toNavFrame().encode())
            val decoded = ClusterProtocol.LEGACY_ACCESS_BURGMAN.decode(wire) as NavFrame
            assertEquals(9, decoded.maneuverId)
            assertEquals("5", decoded.status)
            assertEquals("0000", decoded.distNext)
            assertEquals("0000", decoded.distTotal)
        }
    }

    @Test fun approachingDestinationAndArrivalEtaAreNotTerminalArrival() {
        val approaching = parse(MapsNotificationContent("Arriving", "in 200 m", "Arrive 2:40 pm", 500, 712))
        assertEquals(MapsNavigationState.NAVIGATING, approaching.navigationState)
        assertEquals("1", approaching.status)
        assertNull(MapplsIdGuesser.fromTextOrNull("Arrive 2:40 pm"))
        assertNull(MapplsIdGuesser.fromTextOrNull("Turn toward Arrived Road"))
        assertEquals(40, MapplsIdGuesser.fromTextOrNull("You have arrived at your destination on the left"))
    }

    @Test fun startingAndUnknownValuesNeverUseWholeRouteAsNextTurnOrPhoneClockAsEta() {
        val starting = parse(MapsNotificationContent("Starting navigation...", null, null, 0, 0))
        assertEquals(MapsNavigationState.STARTING, starting.navigationState)
        assertEquals("2", starting.status)
        assertEquals("      ", starting.eta)
        assertEquals("      ", normalizeEta(null, true))
        assertEquals("      ", normalizeEta("Arrive ", true))
        assertEquals("      ", normalizeEta("unknown", false))
    }

    @Test fun invalidOrIndeterminateProgressDoesNotInventRouteDistances() {
        for ((progress, max) in listOf(-1 to 100, 120 to 100, 0 to 0)) {
            val parsed = parse(MapsNotificationContent("Turn left", null, null, progress, max))
            assertNull(parsed.routeRemainingMetres)
            assertEquals("    ", parsed.distTotal)
        }
        assertNull(parse(MapsNotificationContent("Turn left", null, null, 5, 100, indeterminate = true)).routeRemainingMetres)
    }

    @Test fun explicitTotalDistanceDoesNotMistakeMinutesForMetres() {
        val parsed = parse(MapsNotificationContent("200 m - Turn left", null, "5 min - 1.2 km - 2:40 pm"))
        assertEquals("01.2", parsed.distTotal)
        assertEquals("K", parsed.distTotalUnit)
        assertEquals("subText", parsed.totalDistanceSource)
        assertEquals("    ", parse(MapsNotificationContent("Turn left", null, "5 min")).distTotal)
    }

    @Test fun roadNumbersNeverBecomeTurnDistancesAndUnicodeSpacesWork() {
        assertFalse(parse(MapsNotificationContent("Turn onto Road 9 m", null, null)).nextDistanceKnown)
        assertEquals("0060", parse(MapsNotificationContent("60\u00a0m - Turn right", null, null)).distNext)
        assertEquals("01.2" to "K", normalizeDistance("1.2\u202fkm"))
        assertEquals("0085" to "M", normalizeDistance("85 meters"))
        assertEquals("1235" to "K", normalizeDistance("1234.5 km"))
        assertEquals("0050", parse(MapsNotificationContent("Turn right in 50 m", null, null)).distNext)
        assertEquals("      ", normalizeEta("Arrive 25:89", true))
    }

    @Test fun capturedStartupCompassHeadingsUseAbsoluteCompassNotCompetingTurn() {
        for (title in listOf(
            "Head southeast", "Head south on Road No. 47", "Head northwest",
            "Head toward Rd Number 9", "Proceed north-east", "Head west",
            "200 m - Head southeast",
        )) {
            val content = MapsNotificationContent(title, null, "Arrive 2:38 pm", 0, 887)
            assertTrue(title, content.isHeadingOnly)
            // Even a competing bitmap classification must not create a startup turn.
            val parsed = content.parse(profile, classifiedId = 3)
            val decoded = ClusterProtocol.LEGACY_ACCESS_BURGMAN.decode(
                ClusterProtocol.LEGACY_ACCESS_BURGMAN.toWire(parsed.toNavFrame().encode()),
            ) as NavFrame
            val id = MapplsIdGuesser.fromTextOrNull(title)?.takeIf { it in 50..57 }
            assertEquals(title, id?.let(profile::maneuverByte) ?: ManeuverMap.NO_MANEUVER_BYTE, decoded.maneuverId)
            assertEquals(title, "    ", decoded.distNext)
            assertFalse(parsed.nextDistanceKnown)
            assertEquals("0887", decoded.distTotal)
            assertEquals("0238PM", decoded.eta)
            assertEquals(title, parsed.streetName)
        }
    }

    @Test fun startingHeadingAndFirstTurnSequenceNeverInventsAnInitialTurn() {
        val starting = parse(MapsNotificationContent("Starting navigation...", null, null, 0, 0))
        assertEquals(ManeuverMap.NO_MANEUVER_BYTE, starting.maneuverId)
        assertEquals("    ", starting.distNext)
        assertEquals("    ", starting.distTotal)
        val heading = parse(MapsNotificationContent("Head southeast", null, "Arrive 2:38 pm", 0, 887))
        assertEquals(15, heading.maneuverId)
        assertEquals("    ", heading.distNext)
        val turn = parse(MapsNotificationContent("60 m - Turn right toward Main Rd", null, "Arrive 2:39 pm", 3, 1081))
        assertEquals(4, turn.maneuverId)
        assertEquals("0060", turn.distNext)
        assertTrue(turn.nextDistanceKnown)
        assertFalse(MapsNotificationContent("Turn left onto North Road", null, null).isHeadingOnly)
        assertEquals(1, parse(MapsNotificationContent("Turn left onto North Road", null, null)).maneuverId)
    }

    @Test fun allEightCompassDirectionsHaveDistinctVerifiedOemBytes() {
        for ((heading, byte) in listOf(
            "north" to 40, "northeast" to 41, "east" to 42, "southeast" to 15,
            "south" to 16, "southwest" to 17, "west" to 18, "northwest" to 19,
        )) {
            val parsed = parse(MapsNotificationContent("Head $heading", null, null))
            assertEquals(heading, byte, parsed.maneuverId)
            assertFalse(parsed.nextDistanceKnown)
        }
    }

    @Test fun scenarioTableSurvivesLegacyWireEncodingWithoutConflatingManeuvers() {
        for ((text, byte) in listOf(
            "Make a U-turn" to 39, "Make a right U-turn" to 39,
            "Make a left U-turn" to 7,
            "Slight left" to 3, "Slight right" to 6, "Sharp left" to 2, "Sharp right" to 5,
            "Keep left" to 27, "Keep right" to 28,
            "Merge left" to 31, "Merge right" to 32,
            "Turn left" to 1, "Turn right" to 4,
            "Take the left ramp" to 38, "Take the right ramp" to 10,
            "Turn left at the T-junction" to 13, "Turn right at the T-junction" to 14,
            "At the roundabout, take the 3rd exit" to 45, "Unknown instruction" to 46,
        )) {
            val parsed = parse(MapsNotificationContent("450 m · $text", null, null, 0, 12500))
            val protocol = ClusterProtocol.LEGACY_ACCESS_BURGMAN
            val decoded = protocol.decode(protocol.toWire(parsed.toNavFrame().encode())) as NavFrame
            assertEquals(text, byte, decoded.maneuverId)
            assertEquals("0450", decoded.distNext)
            assertEquals("12.5", decoded.distTotal)
            assertEquals("K", decoded.distTotalUnit)
            assertEquals(text, "450 m · $text", parsed.streetName)
        }
        val roundabout = parse(MapsNotificationContent("At the roundabout, take the 3rd exit", null, null))
        assertEquals(3, roundabout.roundaboutExit)
        assertEquals(45, roundabout.maneuverId)
        val empty = parse(MapsNotificationContent(null, null, null))
        assertEquals(46, empty.maneuverId)
        assertEquals("    ", empty.distNext)
    }

    @Test fun distancesBeyondWireCapacityAreExplicitlyUnavailableNotKnownZero() {
        val overflow = parse(MapsNotificationContent("10000000 m - Turn right", null, null))
        assertEquals(4, overflow.maneuverId)
        assertEquals("    ", overflow.distNext)
        assertFalse(overflow.nextDistanceKnown)
        assertEquals("unavailable", navigationDistanceLabel("0000", "?"))
    }

    @Test fun leftRampUsesTheVerifiedModelBranchAndIsNeutralForUnknownModel() {
        val content = MapsNotificationContent("Take the left ramp", null, null)
        val id = MapplsIdGuesser.fromTextOrNull(content.instruction)
        assertEquals(74, id)
        assertEquals(38, content.parse(profile, id).maneuverId)
        assertEquals(44, content.parse(SuzukiProfile(SuzukiModel.DEFAULT, null), id).maneuverId)
        assertEquals(46, content.parse(SuzukiProfile(SuzukiModel.AUTO, null), id).maneuverId)
    }
}
