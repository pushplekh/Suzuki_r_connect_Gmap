package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.IdentityFrame
import dev.mrwick.redline.protocol.SuzukiModel
import dev.mrwick.redline.protocol.SuzukiProfile
import org.junit.Assert.*
import org.junit.Test

class ClusterInstructionTextTest {
    private fun parsed(title: String, text: String? = null, template: Boolean = true) =
        MapsNotificationContent(title, text, null, hasTemplate = template)
            .parse(SuzukiProfile(SuzukiModel.ACCESS, "SAS21"), MapplsIdGuesser.fromTextOrNull(title))

    @Test fun headingTextIsPreservedWithExplicitCompassSymbol() {
        val parsed = parsed("Head southeast")
        assertEquals("Head southeast", clusterInstructionText(parsed))
        assertEquals(15, parsed.maneuverId)
    }

    @Test fun roundaboutExitNumberSurvivesTwentyCharacterExperimentalTextLimit() {
        assertEquals("ROUNDABOUT EXIT 3", clusterInstructionText(
            parsed("At the roundabout, take the 3rd exit onto a long road name"),
        ))
    }

    @Test fun changingDistanceDoesNotChangeIdentityText() {
        assertEquals(
            clusterInstructionText(parsed("60\u00a0m · Turn left")),
            clusterInstructionText(parsed("50\u00a0m · Turn left")),
        )
        assertEquals("Turn left", clusterInstructionText(parsed("50 m · Turn left")))
    }

    @Test fun textFitsIdentityFieldAndContainsOnlyAscii() {
        val text = clusterInstructionText(parsed("Ahead take flyover toward a very long road"))!!
        assertEquals(20, text.length)
        assertEquals(text, IdentityFrame.decode(IdentityFrame(text, false).encode()).name)
        assertTrue(clusterInstructionText(parsed("Turn left \u2192 Main Rd"))!!.all { it.code in 32..126 })
    }

    @Test fun lifecycleTextAndRemovalAreExplicit() {
        assertNull(clusterInstructionText(null))
        assertEquals("STARTING NAVIGATION", clusterInstructionText(parsed("Starting navigation...")))
        assertEquals("REROUTING", clusterInstructionText(parsed("Rerouting...")))
        assertEquals("DESTINATION ARRIVED", clusterInstructionText(parsed("Arriving", "at destination", false)))
    }
}
