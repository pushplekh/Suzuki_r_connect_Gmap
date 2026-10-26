package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.IdentityFrame
import dev.mrwick.redline.protocol.isWellFormed
import dev.mrwick.redline.protocol.ClusterProtocol
import org.junit.Assert.*
import org.junit.Test

class DisplayProbeTest {
    @Test fun arrivalTimeTestsPreserveBothDistancesAcrossWireProtocols() {
        for ((probe, eta) in listOf(DisplayProbe.ETA_AM to "0945AM", DisplayProbe.ETA_PM to "0630PM")) {
            assertTrue(probe.testsArrivalTime)
            assertFalse(probe.testsIdentity)
            for (protocol in ClusterProtocol.entries) {
                val frame = protocol.decode(protocol.toWire(probe.frame())) as NavFrame
                assertEquals(eta, frame.eta)
                assertEquals("0123", frame.distNext)
                assertEquals("M", frame.distNextUnit)
                assertEquals("0045", frame.distTotal)
                assertEquals("K", frame.distTotalUnit)
                assertEquals(ManeuverMap.NO_MANEUVER_BYTE, frame.maneuverId)
                assertEquals("1", frame.status)
            }
        }
    }

    @Test fun savedNameTestsDifferOnlyInIdentityFlagAndChecksum() {
        val reconnect = DisplayProbe.IDENTITY_SAVED.frame("ARJUN")
        val fresh = DisplayProbe.IDENTITY_FRESH.frame("ARJUN")
        assertEquals(listOf(27, 28), reconnect.indices.filter { reconnect[it] != fresh[it] })
        for (probe in listOf(DisplayProbe.IDENTITY_SAVED, DisplayProbe.IDENTITY_FRESH)) {
            assertTrue(probe.testsIdentity)
            assertFalse(probe.testsArrivalTime)
            for (protocol in ClusterProtocol.entries) {
                val frame = protocol.decode(protocol.toWire(probe.frame("ARJUN"))) as IdentityFrame
                assertEquals("ARJUN", frame.name)
                assertEquals(probe == DisplayProbe.IDENTITY_FRESH, frame.isFresh)
            }
        }
        assertEquals("TEXT TEST", IdentityFrame.decode(DisplayProbe.IDENTITY.frame("ARJUN")).name)
    }

    @Test fun etaLabelCandidateChangesOnlyUnassignedBytesAndChecksum() {
        val baseline = DisplayProbe.ETA_LABEL_BASELINE.frame()
        val candidate = DisplayProbe.ETA_LABEL_CANDIDATE.frame()
        assertTrue(isWellFormed(baseline))
        assertTrue(isWellFormed(candidate))
        assertEquals("1234PM", NavFrame.decode(candidate).eta)
        assertEquals("ABC", candidate.copyOfRange(15, 18).toString(Charsets.US_ASCII))
        assertEquals(listOf(15, 16, 17, 28), baseline.indices.filter { baseline[it] != candidate[it] })
        for (i in 15..17) assertEquals(0xFF, baseline[i].toInt() and 0xFF)
        for (protocol in ClusterProtocol.entries) {
            val wire = protocol.toWire(candidate)
            assertArrayEquals(candidate.copyOfRange(0, 28), wire.copyOfRange(0, 28))
            val decoded = protocol.decode(wire) as NavFrame
            assertEquals("1234PM", decoded.eta)
            assertArrayEquals(wire, decoded.raw)
        }
        assertEquals(
            listOf(DisplayProbe.ETA_LABEL_BASELINE, DisplayProbe.ETA_LABEL_CANDIDATE),
            DisplayProbe.entries.filter { it.investigatesEtaLabel },
        )
    }

    @Test fun holdIsBoundedAndNeverSuppressesNavigation() {
        val window = DisplayProbeWindow()
        assertFalse(window.begin(100, navigationActive = true))
        assertTrue(window.begin(100, navigationActive = false))
        assertFalse(window.begin(101, navigationActive = false))
        assertTrue(window.suppressAutomaticDisplay(8099, navigationActive = false))
        assertFalse(window.suppressAutomaticDisplay(8100, navigationActive = false))
        window.finish()
        assertTrue(window.begin(9000, navigationActive = false))
        assertFalse(window.suppressAutomaticDisplay(9001, navigationActive = true))
        assertFalse(window.suppressAutomaticDisplay(9002, navigationActive = false))
    }

    @Test fun endingTestAlwaysReleasesHoldAndAllowsNextTest() {
        val window = DisplayProbeWindow()
        assertTrue(window.begin(10, false))
        window.finish()
        assertFalse(window.suppressAutomaticDisplay(11, false))
        assertTrue(window.begin(12, false))
    }

    @Test fun eachProbeChangesOnlyItsIntendedTextField() {
        for (probe in listOf(DisplayProbe.ETA, DisplayProbe.NEXT, DisplayProbe.TOTAL)) {
            val nav = NavFrame.decode(probe.frame())
            assertEquals(ManeuverMap.NO_MANEUVER_BYTE, nav.maneuverId)
            assertEquals(if (probe == DisplayProbe.ETA) "ABCDEF" else "      ", nav.eta)
            assertEquals(if (probe == DisplayProbe.NEXT) "ABCD" else "    ", nav.distNext)
            assertEquals(if (probe == DisplayProbe.TOTAL) "WXYZ" else "    ", nav.distTotal)
        }
        val identity = DisplayProbe.IDENTITY.frame()
        assertEquals(0x36, identity[1].toInt())
        assertEquals("TEXT TEST", identity.copyOfRange(2, 11).toString(Charsets.US_ASCII))
        assertEquals('R'.code, identity[27].toInt())
    }
}
