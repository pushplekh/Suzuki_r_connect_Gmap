package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.ClusterProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavDiagnosticsTest {
    private fun parsed() = ParsedNavData(
        maneuverId = 1, distNext = "0090", distNextUnit = "M",
        eta = "0215PM", distTotal = "01.2", distTotalUnit = "K", streetName = "Turn left",
    )

    @Test fun diagnosticProvenanceSurvivesParsingWithoutChangingWireBytes() {
        val original = parsed().toNavFrame()
        val traced = parsed().copy(traceId = "maps-123-1", sourceElapsedMs = 456).toNavFrame()
        assertEquals("maps-123-1", traced.traceId)
        assertEquals(456L, traced.sourceElapsedMs)
        assertArrayEquals(original.encode(), traced.encode())
        for (protocol in ClusterProtocol.entries) {
            assertArrayEquals(protocol.toWire(original.encode()), protocol.toWire(traced.encode()))
        }
    }

    @Test fun identicalNewNotificationsDoNotLoseNewTraceInStateFlowEquality() {
        val first = parsed().copy(traceId = "maps-1", sourceElapsedMs = 1).toNavFrame()
        val second = parsed().copy(traceId = "maps-2", sourceElapsedMs = 2).toNavFrame()
        assertNotEquals(first, second)
        assertArrayEquals(first.encode(), second.encode())
    }

    @Test fun diagnosticFieldsExposeExactNormalizedValues() {
        val text = NavDiagnostics.fields(parsed().copy(traceId = "maps-1").toNavFrame())
        for (field in listOf(
            "trace=maps-1", "arrow=1", "next=\"0090\"", "unit=\"M\"",
            "total=\"01.2\"", "totalUnit=\"K\"", "eta=\"0215PM\"", "status=1", "continue=1",
        )) assertTrue(field, text.contains(field))
    }

    @Test fun quotedTextPreservesWhitespaceEvidenceOnOneLogLine() {
        assertEquals("\"left\\n90\\u00a0m\\t\\\"road\\\"\\\\\"", NavDiagnostics.quoted("left\n90\u00a0m\t\"road\"\\"))
        assertEquals("null", NavDiagnostics.quoted(null))
    }

    @Test fun etaProvenanceDoesNotPretendPhoneClockFallbackCameFromMaps() {
        assertEquals("explicit-clock", etaInputSource("Arrive 2:15 PM"))
        assertEquals("duration-plus-phone-clock", etaInputSource("5 min · 1.2 km"))
        assertEquals("unavailable-NOT-MAPS-ETA", etaInputSource(null))
        assertEquals("unavailable-NOT-MAPS-ETA", etaInputSource("Unknown time"))
    }
}
