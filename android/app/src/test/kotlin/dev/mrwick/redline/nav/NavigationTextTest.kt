package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.ClusterProtocol
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.SuzukiModel
import dev.mrwick.redline.protocol.SuzukiProfile
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTextTest {
    private fun parsed(text: String) = MapsNotificationContent(
        "450 m · $text", null, "Arrive 1:01 pm", 0, 12500,
    ).parse(SuzukiProfile(SuzukiModel.ACCESS, null), MapplsIdGuesser.fromTextOrNull(text))
        .copy(traceId = "maps-test", sourceElapsedMs = 100)

    @Test fun actionLabelsFitTestedFieldWithoutChangingDistancesArrowOrStatus() {
        for ((text, label) in listOf(
            "Make a U-turn" to "U-RGHT", "Slight left" to "SL-L", "Sharp right" to "HARD R",
            "Make a right U-turn" to "U-RGHT", "Make a U-turn right" to "U-RGHT",
            "Make a left U-turn" to "U-LEFT", "Make a U-turn left" to "U-LEFT",
            "Make a right U\u2011turn" to "U-RGHT",
            "Keep left" to "KEEP L", "Keep right" to "KEEP R",
            "Head northeast" to "NE", "Head southwest" to "SW",
            "Take the right ramp" to "RAMP R", "Merge left" to "MERG L",
            "Join with Example Rd" to "MERGE",
            "Join onto Example Rd" to "MERGE",
            "Join on the left onto Example Rd" to "MERG L",
            "Join right onto Example Rd" to "MERG R",
            "Head toward Example Rd" to "HEAD",
            "At the roundabout, turn left onto Example Rd" to "RB L",
            "At the roundabout, turn right onto Example Rd" to "RB R",
            "At the roundabout, continue straight onto Example Rd" to "RB FWD",
            "At the roundabout, take the 3rd exit" to "EXT3",
            "Exit the roundabout onto Example Rd" to "EXIT",
            "Exit the traffic circle onto Example Rd" to "EXIT",
        )) {
            val data = parsed(text)
            val original = data.toNavFrame()
            val frame = withNavigationText(original, data, true)!!
            assertEquals(text, label.padEnd(6), frame.eta)
            assertEquals(text, original.copy(eta = frame.eta), frame)
            assertEquals(6, frame.eta.length)
            assertTrue(frame.eta.all { it.code in 32..126 })
            val protocol = ClusterProtocol.LEGACY_ACCESS_BURGMAN
            val decoded = protocol.decode(protocol.toWire(frame.encode())) as NavFrame
            assertEquals(frame.eta, decoded.eta)
            assertEquals(original.distNext, decoded.distNext)
            assertEquals(original.distTotal, decoded.distTotal)
            assertEquals(original.maneuverId, decoded.maneuverId)
        }
    }

    @Test fun diagnostics8RoundaboutPhasesAndSideNeutralJoinUseOnlySuppliedDirections() {
        for ((instruction, arrow, text) in listOf(
            Triple("At the roundabout, take the 2nd exit and stay on Example Rd", 45, "EXT2  "),
            Triple("Exit the roundabout onto Example Rd", 45, "EXIT  "),
            Triple("At the roundabout, take the 4th exit and stay on Example Rd", 45, "EXT4  "),
            Triple("At Example Rotary, take the 2nd exit onto Example Rd", 45, "EXT2  "),
            Triple("At the roundabout, take the 3rd exit", 45, "EXT3  "),
            Triple("Join with Example Rd", ManeuverMap.NO_MANEUVER_BYTE, "MERGE "),
        )) {
            val data = parsed(instruction)
            assertEquals(instruction, arrow, data.maneuverId)
            val displayed = withNavigationText(data.toNavFrame(), data, true)!!
            assertEquals(instruction, text, displayed.eta)
            for (protocol in ClusterProtocol.entries) {
                val wire = protocol.decode(protocol.toWire(displayed.encode())) as NavFrame
                assertEquals(displayed.maneuverId, wire.maneuverId)
                assertEquals(text, wire.eta)
                assertEquals(displayed.distNext, wire.distNext)
                assertEquals(displayed.distTotal, wire.distTotal)
            }
        }
    }

    @Test fun recordedRoundaboutPausesShowStaleWithoutOldArrowOrDistancesAndRecover() = runTest {
        val session = MapsNavSession(backgroundScope) { testScheduler.currentTime }
        val idle = NavFrame(
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = "0002", eta = "0523PM", distTotal = "0024",
            distNextUnit = " ", distTotalUnit = "C", status = "1", continueFlag = "1",
        )
        val idleFlow = MutableStateFlow<NavFrame?>(idle)
        val frames = NavMux(session.frame, idleFlow).frame
        val enabled = MutableStateFlow(false)
        val received = mutableListOf<NavFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigationDisplayFlow(frames, session.parsed, enabled, session.freshness)
                .collect { received.add(it) }
        }
        assertEquals(idle, received.last())
        for ((index, gap) in listOf(71_905L, 46_754L).withIndex()) {
            session.update(parsed("At the roundabout, take the 2nd exit").copy(traceId = "pause-$index"))
            runCurrent()
            assertEquals(45, received.last()?.maneuverId)
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(45, received.last()?.maneuverId)
            advanceTimeBy(1)
            runCurrent()
            val stale = received.last()!!
            assertEquals("STALE ", stale.eta)
            assertEquals(ManeuverMap.NO_MANEUVER_BYTE, stale.maneuverId)
            assertEquals("    ", stale.distNext)
            assertEquals("    ", stale.distTotal)
            assertNull(stale.traceId)
            for (protocol in ClusterProtocol.entries) {
                val wire = protocol.decode(protocol.toWire(stale.encode())) as NavFrame
                assertEquals("STALE ", wire.eta)
                assertEquals(ManeuverMap.NO_MANEUVER_BYTE, wire.maneuverId)
                assertEquals("    ", wire.distNext)
                assertEquals("    ", wire.distTotal)
            }
            advanceTimeBy(gap - 30_000)
            runCurrent()
            assertEquals("STALE ", received.last()?.eta)
        }
        idleFlow.value = null
        runCurrent()
        assertEquals("STALE ", received.last()?.eta)
        idleFlow.value = idle
        enabled.value = true
        session.update(parsed("Exit the roundabout onto Example Rd").copy(traceId = "exit"))
        runCurrent()
        assertEquals("EXIT  ", received.last()?.eta)
        assertEquals(45, received.last()?.maneuverId)
        session.clear()
        runCurrent()
        assertEquals(idle, received.last())
        session.update(parsed("You have arrived at your destination"))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(idle, received.last())
        assertFalse(session.freshness.value.stale)
    }

    @Test fun unclassifiedDestinationKeepsEtaWithoutInventingArrivalOrAnArrow() {
        for (title in listOf("500 m · Ascendas IT Park", "Destination Name")) {
            val data = MapsNotificationContent(title, null, "Arrive 1:01 pm", 1059, 1559)
                .parse(SuzukiProfile(SuzukiModel.ACCESS, null), MapplsIdGuesser.fromTextOrNull(title))
                .copy(traceId = "destination-only", sourceElapsedMs = 100)
            val original = data.toNavFrame()
            assertEquals(MapsNavigationState.NAVIGATING, data.navigationState)
            assertEquals(ManeuverMap.NO_MANEUVER_BYTE, original.maneuverId)
            assertEquals("0101PM", withNavigationText(original, data, true)!!.eta)
            assertSame(original, withNavigationText(original, data, true))
            assertEquals("0500", original.distTotal)
            assertEquals("1", original.status)
        }
    }

    @Test fun roundaboutDirectionDoesNotGuessFromRoadNamesOrExitOrdinal() {
        assertNull(MapplsIdGuesser.roundaboutDirection("At the roundabout, take the 1st exit onto Left Road"))
        assertNull(MapplsIdGuesser.roundaboutDirection("At the roundabout, take the exit onto Turn Left Road"))
        assertNull(MapplsIdGuesser.roundaboutDirection("Turn left onto Roundabout Road"))
        assertEquals("EXT1  ", navigationText(parsed("At the roundabout, turn left and take the 1st exit")))
        assertEquals(45, parsed("At the roundabout, turn left onto Example Rd").maneuverId)
    }

    @Test fun interleavedSourceUpdatesNeverEmitUnrenderedPreviousEta() = runTest {
        val first = parsed("At the roundabout, take the 1st exit")
        val frames = MutableStateFlow<NavFrame?>(first.toNavFrame())
        val data = MutableStateFlow<ParsedNavData?>(first)
        val enabled = MutableStateFlow(true)
        val received = mutableListOf<NavFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigationDisplayFlow(frames, data, enabled).collect { received.add(it) }
        }
        assertEquals("EXT1  ", received.last()?.eta)
        val second = parsed("At the roundabout, turn left").copy(traceId = "next")
        data.value = second
        assertEquals(1, received.size)
        frames.value = second.toNavFrame()
        assertEquals(listOf("EXT1  ", "RB L  "), received.map { it?.eta })
        val third = parsed("Make a U-turn").copy(traceId = "third")
        frames.value = third.toNavFrame()
        assertEquals(2, received.size)
        data.value = third
        assertEquals("U-RGHT", received.last()?.eta)
        enabled.value = false
        assertEquals("0101PM", received.last()?.eta)
        frames.value = null
        data.value = null
        assertNull(received.last())
        val idle = first.toNavFrame().copy(traceId = null, eta = "0245PM")
        frames.value = idle
        assertSame(idle, received.last())
    }

    @Test fun normalLeftRightAndStraightKeepArrivalTimeIncludingAlternateBytes() {
        for (text in listOf("Turn left", "Turn right", "Continue straight")) {
            val data = parsed(text)
            for (maneuver in listOf(data.maneuverId, 1, 4, 8, 13, 14, 33)) {
                val guidance = data.copy(maneuverId = maneuver)
                val frame = guidance.toNavFrame()
                assertSame(text, frame, withNavigationText(frame, guidance, true))
                assertEquals("0101PM", frame.eta)
            }
        }
    }

    @Test fun detailedToNormalTransitionRestoresCurrentArrivalTimeWithoutLeakingText() {
        val detailed = parsed("Make a right U-turn")
        val detailedFrame = detailed.toNavFrame()
        assertEquals("U-RGHT", withNavigationText(detailedFrame, detailed, true)!!.eta)
        val normal = parsed("Turn right").copy(traceId = "next-input")
        val normalFrame = normal.toNavFrame().copy(eta = "0245PM")
        assertSame(normalFrame, withNavigationText(normalFrame, normal, true))
        assertEquals("0245PM", withNavigationText(normalFrame, normal, true)!!.eta)
        assertSame(detailedFrame, withNavigationText(detailedFrame, normal, true))
    }

    @Test fun disabledIdleAndSupersededFramesNeverReceiveInstructionText() {
        val data = parsed("Make a U-turn")
        val frame = data.toNavFrame()
        assertSame(frame, withNavigationText(frame, data, false))
        assertSame(frame, withNavigationText(frame, null, true))
        val idle = frame.copy(traceId = null)
        assertSame(idle, withNavigationText(idle, data, true))
        assertSame(frame, withNavigationText(frame, data.copy(traceId = "other"), true))
        assertNull(withNavigationText(null, data, true))
        assertEquals("0101PM", withNavigationText(frame, data, false)!!.eta)
    }

    @Test fun lifecycleLabelsAndLargeExitOrdinalsAreBounded() {
        val data = parsed("Turn left")
        for ((state, label) in listOf(
            MapsNavigationState.STARTING to "START ",
            MapsNavigationState.REROUTING to "REROUT",
            MapsNavigationState.ARRIVED to "ARRIVE",
        )) assertEquals(label, navigationText(data.copy(navigationState = state)))
        for (state in listOf(MapsNavigationState.STARTING, MapsNavigationState.REROUTING, MapsNavigationState.ARRIVED)) {
            val lifecycle = data.copy(navigationState = state)
            assertEquals(navigationText(lifecycle), withNavigationText(lifecycle.toNavFrame(), lifecycle, true)!!.eta)
        }
        assertEquals("ROUND ", navigationText(data.copy(roundaboutExit = 100)))
        val exitWithNormalArrow = data.copy(roundaboutExit = 3, maneuverId = 4)
        assertEquals("EXT3  ", withNavigationText(exitWithNormalArrow.toNavFrame(), exitWithNormalArrow, true)!!.eta)
    }

    @Test fun namedCircleRetainsOrdinalWithoutGuessingExitAngleOrAffectingNormalExits() {
        val data = parsed("At Example Cir, take the 3rd exit onto Main Rd")
        assertEquals(45, data.maneuverId)
        assertEquals(3, data.roundaboutExit)
        assertEquals("EXT3  ", navigationText(data))
        assertEquals(72, MapplsIdGuesser.fromTextOrNull("At Example Circle, take the second exit"))
        assertNull(MapplsIdGuesser.roundaboutExit("Take the 3rd exit onto Circle Road"))
        assertEquals(4, parsed("Turn right onto Circle Rd").maneuverId)
    }

    @Test fun exitNowUsesOnlyBlankNextFieldAndKeepsKnownExitInFirstFourTimeBytes() {
        val profile = SuzukiProfile(SuzukiModel.ACCESS, null)
        for (ordinal in listOf(2, 3)) {
            for (distance in listOf(null, "50 m", "0 m")) {
                val instruction = listOfNotNull(distance, "Exit the roundabout onto Example Rd").joinToString(" · ")
                val data = MapsNotificationContent(instruction, null, "Arrive 1:01 pm", 100, 1000)
                    .parse(profile, MapplsIdGuesser.fromTextOrNull(instruction))
                    .copy(traceId = "exit-$ordinal-$distance", roundaboutExit = ordinal)
                val original = data.toNavFrame()
                assertSame(original, withNavigationText(original, data, false))
                val rendered = withNavigationText(original, data, true)!!
                assertEquals("EXT$ordinal  ", rendered.eta)
                assertEquals(if (distance == null) "NOW " else original.distNext, rendered.distNext)
                assertEquals(if (distance == null) " " else original.distNextUnit, rendered.distNextUnit)
                assertEquals(original.distTotal, rendered.distTotal)
                assertEquals(original.maneuverId, rendered.maneuverId)
                for (protocol in ClusterProtocol.entries) {
                    val wire = protocol.toWire(rendered.encode())
                    val decoded = protocol.decode(wire) as NavFrame
                    assertEquals("EXT$ordinal", wire.copyOfRange(9, 13).toString(Charsets.US_ASCII))
                    assertEquals(rendered.eta, decoded.eta)
                    assertEquals(rendered.distNext, decoded.distNext)
                    assertEquals(rendered.distNextUnit, decoded.distNextUnit)
                    assertEquals(original.distTotal, decoded.distTotal)
                }
            }
        }
        for (instruction in listOf("Turn left", "Turn right", "At the roundabout, take the 2nd exit")) {
            val data = MapsNotificationContent(instruction, null, "Arrive 1:01 pm")
                .parse(profile, MapplsIdGuesser.fromTextOrNull(instruction)).copy(traceId = "other")
            assertEquals("    ", withNavigationText(data.toNavFrame(), data, true)!!.distNext)
        }
    }
}
