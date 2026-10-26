package dev.mrwick.redline.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MapplsIdGuesserTest {
    @Test fun exitNowAndJoinDetectionIgnoreDirectionWordsInRoadNames() {
        assertTrue(MapplsIdGuesser.isRoundaboutExit("Exit the roundabout onto Example Rd"))
        assertTrue(MapplsIdGuesser.isRoundaboutExit("Exit the traffic circle onto Example Rd"))
        assertFalse(MapplsIdGuesser.isRoundaboutExit("Turn left onto Exit the Roundabout Rd"))
        assertFalse(MapplsIdGuesser.isRoundaboutExit("At the roundabout, take the 2nd exit"))
        for (text in listOf("Join with Example Rd", "Join onto Example Rd", "Join with Turn Right Rd", "Join with Roundabout Rd")) {
            assertTrue(MapplsIdGuesser.isUndirectedJoin(text))
            assertNull(MapplsIdGuesser.fromTextOrNull(text))
        }
        assertEquals(15, MapplsIdGuesser.fromTextOrNull("Join on the left onto Example Rd"))
        assertEquals(16, MapplsIdGuesser.fromTextOrNull("Join right onto Example Rd"))
        assertFalse(MapplsIdGuesser.isUndirectedJoin("Turn left onto Join with Rd"))
    }

    @Test
    fun `classifies left and right turn strengths distinctly`() {
        assertEquals(0, MapplsIdGuesser.fromTextOrNull("Turn left onto MG Road"))
        assertEquals(3, MapplsIdGuesser.fromTextOrNull("Turn right in 200 m"))
        assertEquals(2, MapplsIdGuesser.fromTextOrNull("Bear left at the fork"))
        assertEquals(5, MapplsIdGuesser.fromTextOrNull("Slight right"))
        assertEquals(1, MapplsIdGuesser.fromTextOrNull("Sharp left"))
        assertEquals(4, MapplsIdGuesser.fromTextOrNull("Sharp right"))
    }

    @Test
    fun `distinguishes keep lane from fork and merge instructions`() {
        assertEquals(19, MapplsIdGuesser.fromTextOrNull("Keep left to stay on the road"))
        assertEquals(20, MapplsIdGuesser.fromTextOrNull("Stay right"))
        assertEquals(19, MapplsIdGuesser.fromTextOrNull("Take the left branch"))
        assertEquals(20, MapplsIdGuesser.fromTextOrNull("Take the right fork"))
        assertEquals(15, MapplsIdGuesser.fromTextOrNull("Merge left"))
        assertEquals(16, MapplsIdGuesser.fromTextOrNull("Merge right"))
    }

    @Test
    fun `classifies directed exits, u-turn direction and generic roundabout`() {
        assertEquals(74, MapplsIdGuesser.fromTextOrNull("Take the exit on the left"))
        assertEquals(75, MapplsIdGuesser.fromTextOrNull("Take the right exit"))
        assertEquals(6, MapplsIdGuesser.fromTextOrNull("Make a left U-turn"))
        assertEquals(41, MapplsIdGuesser.fromTextOrNull("Make a U-turn right"))
        assertEquals(41, MapplsIdGuesser.fromTextOrNull("Make a U-turn"))
        assertEquals(72, MapplsIdGuesser.fromTextOrNull("At the roundabout, take the second exit"))
    }

    @Test
    fun `uses recognized field and refuses to guess unknown instructions`() {
        assertEquals(7, MapplsIdGuesser.fromTextOrNull("Continue straight"))
        assertEquals(36, MapplsIdGuesser.fromTextOrNull("Take the ferry"))
        assertEquals(37, MapplsIdGuesser.fromTextOrNull("Enter the tunnel"))
        assertEquals(40, MapplsIdGuesser.fromTextOrNull("You have arrived at your destination"))
        assertNull(MapplsIdGuesser.fromTextOrNull("In 500 meters"))
        assertNull(MapplsIdGuesser.fromTextOrNull(null))
    }

    @Test
    fun `unclassified instruction has no inferred arrow`() {
        val result = ManeuverClassifier.classifyDetailed(
            bitmap = null,
            instruction = "In 500 meters",
            selfTrainEnabled = false,
        )
        assertNull(result.id)
        assertEquals(ManeuverClassifier.Source.Unclassified, result.source)
    }

    @Test
    fun `common left right and lane instructions translate to Access cluster bytes`() {
        fun clusterByte(instruction: String): Int =
            ManeuverMap.mapplsIdToClusterByte(
                MapplsIdGuesser.fromTextOrNull(instruction)!!,
                vehicleModel = "Access",
            )!!

        assertEquals(1, clusterByte("Turn left"))
        assertEquals(4, clusterByte("Turn right"))
        assertEquals(3, clusterByte("Bear left"))
        assertEquals(6, clusterByte("Bear right"))
        assertEquals(2, clusterByte("Sharp left"))
        assertEquals(5, clusterByte("Sharp right"))
        assertEquals(27, clusterByte("Keep left"))
        assertEquals(28, clusterByte("Keep right"))
        assertEquals(27, clusterByte("Take the left fork"))
        assertEquals(28, clusterByte("Take the right fork"))
        assertEquals(31, clusterByte("Merge left"))
        assertEquals(32, clusterByte("Merge right"))
        assertEquals(38, clusterByte("Take the left ramp"))
        assertEquals(10, clusterByte("Take the right ramp"))
    }

    @Test
    fun `unclassified instructions do not fall back to a straight arrow`() {
        val unknown = ManeuverClassifier.classifyDetailed(null, "In 500 meters", false)
        assertNull(unknown.id?.let { ManeuverMap.mapplsIdToClusterByte(it, "Access") })

        val arrival = MapplsIdGuesser.fromTextOrNull("You have arrived at your destination")!!
        assertEquals(MapplsIdGuesser.DESTINATION_ARRIVED_ID, arrival)
    }

    @Test fun `explicit ramps and T-junctions do not become ordinary turns`() {
        for ((text, id) in listOf(
            "Take the left ramp" to 74, "Take the right ramp" to 75,
            "Take the ramp on the left" to 74, "Take the ramp on the right" to 75,
            "Take the slight left ramp" to 74, "Take the sharp right ramp" to 75,
            "Turn left at the T-junction" to 13, "At the T-junction, turn right" to 14,
            "Take the left slip road" to 74, "Turn right onto Flyover Rd" to 3,
            "Keep right to stay on Slight Left Road" to 20,
            "Keep right to continue on Sharp Left Road" to 20,
            "Head south on Sharp Left Road" to 54,
            "Turn right onto Roundabout Road" to 3,
            "Turn left toward U-turn Point" to 0,
            "Turn slightly left" to 2, "Turn slightly right" to 5,
            "Merge onto the left" to 15, "Turn onto the right" to 3,
        )) assertEquals(text, id, MapplsIdGuesser.fromTextOrNull(text))
    }

    @Test fun `roundabout ordinals are extracted without inferring angles`() {
        assertEquals(3, MapplsIdGuesser.roundaboutExit("At the roundabout, take the 3rd exit onto Example Rd"))
        assertEquals(2, MapplsIdGuesser.roundaboutExit("At the roundabout take the second exit"))
        assertEquals(72, MapplsIdGuesser.fromTextOrNull("Exit the roundabout onto Example Rd"))
        assertNull(MapplsIdGuesser.roundaboutExit("Take exit 3"))
        assertNull(MapplsIdGuesser.roundaboutExit(null))
        assertNull(MapplsIdGuesser.roundaboutExit("At the roundabout, turn left"))
        assertNull(MapplsIdGuesser.roundaboutExit("At the roundabout, turn left onto Third Exit Rd"))
    }

    @Test fun `reliable maneuver text wins over a conflicting icon hash`() {
        for ((textId, hashId) in listOf(2 to 1, 5 to 4, 19 to 0, 20 to 3, 3 to 51)) {
            val result = ManeuverClassifier.resolveCandidates(textId, hashId)
            assertEquals(textId, result.id)
            assertEquals(ManeuverClassifier.Source.TextFallback, result.source)
        }
        assertEquals(41, ManeuverClassifier.resolveCandidates(null, 41).id)
        assertNull(ManeuverClassifier.resolveCandidates(null, null).id)
    }
}
