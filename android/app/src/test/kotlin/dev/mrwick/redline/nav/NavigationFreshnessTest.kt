package dev.mrwick.redline.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationFreshnessTest {
    @Test fun waitingBeforeFirstUpdateHasNoInventedAge() {
        val status = NavigationFreshness()
        assertNull(status.ageSeconds(100_000))
        assertEquals("Waiting for Maps navigation", status.message(100_000))
        assertFalse(status.isStale(100_000))
        assertTrue(status.ageMessage(100_000).contains("none received"))
    }

    @Test fun timeoutBoundaryIsExactlyThirtySeconds() {
        val status = NavigationFreshness(10_000, MapsNavigationState.NAVIGATING)
        assertEquals(29L, status.ageSeconds(39_999))
        assertFalse(status.isStale(39_999))
        assertTrue(status.isStale(40_000))
        assertTrue(status.message(40_000).startsWith("Stale Maps guidance"))
        assertEquals("Last Maps update: 30 seconds ago", status.ageMessage(40_000))
    }

    @Test fun lifecycleStatesAndStaleHistoryRemainDistinct() {
        assertTrue(NavigationFreshness(0, MapsNavigationState.STARTING).message(1_000).startsWith("Waiting"))
        assertTrue(NavigationFreshness(0, MapsNavigationState.REROUTING).message(1_000).contains("rerouting"))
        assertTrue(NavigationFreshness(0, MapsNavigationState.ARRIVED).message(1_000).contains("arrival"))
        val stale = NavigationFreshness(0, MapsNavigationState.NAVIGATING, stale = true)
        assertTrue(stale.isStale(1_000))
        val removed = stale.copy(state = null, stale = false)
        assertFalse(removed.isStale(90_000))
        assertEquals("Waiting for Maps navigation", removed.message(90_000))
        val refreshed = NavigationFreshness(90_000, MapsNavigationState.NAVIGATING)
        assertFalse(refreshed.isStale(90_000))
        assertEquals(0L, refreshed.ageSeconds(90_000))
    }

    @Test fun ageDoesNotGoNegative() {
        assertEquals(0L, NavigationFreshness(100).ageSeconds(99))
    }
}
