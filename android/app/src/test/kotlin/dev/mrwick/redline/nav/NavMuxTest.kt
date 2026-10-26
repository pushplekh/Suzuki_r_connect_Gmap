package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavMuxTest {

    @Test
    fun `Maps navigation takes priority and idle display resumes when cleared`() = runTest {
        val maps = MutableStateFlow<NavFrame?>(null)
        val idle = MutableStateFlow(frame(distTotal = "0025"))
        val mux = NavMux(maps, idle)
        val navigation = frame(distTotal = "01.2")

        assertEquals(idle.value, mux.frame.filterNotNull().first())

        maps.value = navigation
        assertEquals(navigation, mux.frame.first())

        maps.value = null
        assertEquals(idle.value, mux.frame.filterNotNull().first())
    }

    @Test
    fun `Maps is not blocked by an idle producer that never emits`() = runTest {
        val navigation = frame("01.2")
        val mux = NavMux(MutableStateFlow(navigation), emptyFlow())
        assertEquals(navigation, mux.frame.first())
    }

    @Test
    fun `continuous subscription handles disabled idle and navigation removal`() = runTest {
        val maps = MutableStateFlow<NavFrame?>(null)
        val idle = MutableStateFlow<NavFrame?>(frame("0025"))
        val mux = NavMux(maps, idle)
        val received = mutableListOf<NavFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mux.frame.collect { received.add(it) }
        }
        val navigation = frame("01.2")
        maps.value = navigation
        idle.value = null
        assertEquals(navigation, received.last())
        maps.value = navigation.copy(distTotal = "01.1")
        assertEquals("01.1", received.last()?.distTotal)
        maps.value = null
        assertEquals(null, received.last())
        idle.value = frame("0026")
        assertEquals(idle.value, received.last())
    }

    @Test
    fun `same wire instruction retains latest notification provenance through mux`() = runTest {
        val first = frame("01.2").copy(traceId = "maps-1", sourceElapsedMs = 100)
        val maps = MutableStateFlow<NavFrame?>(first)
        val mux = NavMux(maps, MutableStateFlow<NavFrame?>(null))
        val received = mutableListOf<NavFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mux.frame.collect { received.add(it) }
        }
        maps.value = first.copy(traceId = "maps-2", sourceElapsedMs = 200)
        assertEquals("maps-2", received.last()?.traceId)
        assertEquals(200L, received.last()?.sourceElapsedMs)
    }

    private fun frame(distTotal: String) = NavFrame(
        maneuverId = ManeuverMap.DEFAULT_CLUSTER_BYTE,
        distNext = "0200",
        distNextUnit = "M",
        eta = "0432PM",
        distTotal = distTotal,
        distTotalUnit = "K",
        status = "1",
        continueFlag = "1",
    )
}
