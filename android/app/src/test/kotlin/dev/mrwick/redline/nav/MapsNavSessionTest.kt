package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.SuzukiModel
import dev.mrwick.redline.protocol.SuzukiProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapsNavSessionTest {
    private val profile = SuzukiProfile(SuzukiModel.ACCESS, "SAS21")
    private fun parsed(title: String, text: String? = null, template: Boolean = true) =
        MapsNotificationContent(title, text, null, hasTemplate = template)
            .parse(profile, MapplsIdGuesser.fromTextOrNull(title))

    @Test fun roundaboutExitKeepsFreshOrdinalOnlyUntilAnotherManeuverOrReset() = runTest {
        val session = MapsNavSession(backgroundScope) { testScheduler.currentTime }
        val exit = parsed("Exit the roundabout onto Example Rd")
        for ((ordinal, word) in listOf(2 to "2nd", 3 to "3rd")) {
            session.update(parsed("150 m · At the roundabout, take the $word exit"))
            runCurrent()
            advanceTimeBy(1000)
            session.update(exit)
            assertEquals(ordinal, session.parsed.value?.roundaboutExit)
            val data = session.parsed.value!!.copy(traceId = "exit")
            val rendered = withNavigationText(data.toNavFrame(), data, true)!!
            assertEquals("EXIT$ordinal ", rendered.eta)
            assertEquals("NOW ", rendered.distNext)
            runCurrent()
            advanceTimeBy(1000)
            session.update(exit)
            assertEquals(ordinal, session.parsed.value?.roundaboutExit)
            session.update(parsed("Turn left"))
            session.update(exit)
            assertNull(session.parsed.value?.roundaboutExit)
        }
        for (reset in listOf("clear", "null", "reroute", "arrival", "stale")) {
            session.update(parsed("At the roundabout, take the 2nd exit"))
            runCurrent()
            when (reset) {
                "clear" -> session.clear()
                "null" -> session.update(null)
                "reroute" -> session.update(parsed("Rerouting..."))
                "arrival" -> session.update(parsed("You have arrived at your destination"))
                "stale" -> {
                    advanceTimeBy(MapsNavSource.STALE_AFTER_MS)
                    runCurrent()
                }
            }
            session.update(exit)
            assertNull(reset, session.parsed.value?.roundaboutExit)
        }
    }

    @Test fun arrivalRemovalKeepsFlagUntilOriginalThirtySecondDeadline() = runTest {
        val session = MapsNavSession(backgroundScope) { testScheduler.currentTime }
        session.update(parsed("Arriving", "at destination", false))
        runCurrent()
        advanceTimeBy(4600)
        session.clear()
        assertEquals(9, session.frame.value?.maneuverId)
        advanceTimeBy(25399)
        session.clear()
        assertNotNull(session.frame.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(session.frame.value)
        assertNull(session.parsed.value)
        assertFalse(session.freshness.value.isStale(testScheduler.currentTime))
        assertEquals(MapsNavigationState.ARRIVED, session.freshness.value.state)
    }

    @Test fun newRouteImmediatelyReplacesArrivalAndItsOldWatchdogCannotClearIt() = runTest {
        val session = MapsNavSession(backgroundScope) { testScheduler.currentTime }
        session.update(parsed("Arriving", "at destination", false))
        runCurrent()
        advanceTimeBy(5000)
        session.clear()
        session.update(parsed("450 m - Keep right"))
        runCurrent()
        advanceTimeBy(25000)
        runCurrent()
        assertEquals(28, session.frame.value?.maneuverId)
        session.clear()
        assertNull(session.frame.value)
    }

    @Test fun rapidManeuversRerouteAndStaleTimeoutNeverRestorePreviousArrow() = runTest {
        val session = MapsNavSession(backgroundScope) { testScheduler.currentTime }
        for (title in listOf("Slight left", "Keep right", "Rerouting...", "Sharp right")) {
            session.update(parsed(title))
            runCurrent()
            assertEquals(parsed(title).maneuverId, session.frame.value?.maneuverId)
            advanceTimeBy(100)
        }
        advanceTimeBy(29900)
        runCurrent()
        assertNull(session.frame.value)
        assertTrue(session.freshness.value.stale)
        session.update(parsed("Take the left ramp"))
        assertEquals(38, session.frame.value?.maneuverId)
        assertFalse(session.freshness.value.stale)
    }
}
