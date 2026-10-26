package dev.mrwick.redline.ui.cluster

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.ui.components.GixxerIcons
import dev.mrwick.redline.nav.MapplsIdGuesser
import dev.mrwick.redline.nav.MapsNotificationContent
import dev.mrwick.redline.nav.toNavFrame
import dev.mrwick.redline.protocol.ClusterProtocol
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.SuzukiModel
import dev.mrwick.redline.protocol.SuzukiProfile
import org.junit.Assert.*
import org.junit.Test

class ManeuverSymbolTest {
    @Test fun instructionCharactersAreNotReformattedAsClockDigits() {
        assertEquals("LEFT", formatEta("LEFT  "))
        assertEquals("U-TURN", formatEta("U-TURN"))
        assertEquals("EXIT 3", formatEta("EXIT 3"))
        assertEquals("01:01 PM", formatEta("0101PM"))
    }
    @Test fun capturedHeadingsReachWireAndPreviewWithoutOldSuppression() {
        for ((direction, byte, rotation) in listOf(
            Triple("north", 40, 0f), Triple("northeast", 41, 45f),
            Triple("east", 42, 90f), Triple("southeast", 15, 135f),
            Triple("south", 16, 180f), Triple("southwest", 17, 225f),
            Triple("west", 18, 270f), Triple("northwest", 19, 315f),
        )) {
            val content = MapsNotificationContent("Head $direction on Example Rd", null, "Arrive 1:01 pm", 0, 294)
            val parsed = content.parse(
                SuzukiProfile(SuzukiModel.ACCESS, null),
                MapplsIdGuesser.fromTextOrNull(content.instruction),
            )
            val protocol = ClusterProtocol.LEGACY_ACCESS_BURGMAN
            val wire = protocol.toWire(parsed.toNavFrame().encode())
            val decoded = protocol.decode(wire) as NavFrame
            assertEquals(direction, byte, decoded.maneuverId)
            assertNotEquals(46, decoded.maneuverId)
            assertEquals(direction, "    ", decoded.distNext)
            assertEquals("Head $direction", maneuverSymbol(decoded.maneuverId).label)
            assertEquals(rotation, maneuverSymbol(decoded.maneuverId).rotation)
        }
    }
    @Test fun missingVariantsFollowExistingVectorSizeStrokeAndAlignmentConvention() {
        for (icon in listOf(
            GixxerIcons.ManeuverSlight, GixxerIcons.ManeuverSharp, GixxerIcons.ManeuverKeep,
            GixxerIcons.ManeuverTJunction, GixxerIcons.ManeuverRamp, GixxerIcons.ManeuverMerge,
            GixxerIcons.ManeuverRoundabout, GixxerIcons.ManeuverArrival, GixxerIcons.ManeuverHeading,
        )) {
            assertEquals(24.dp, icon.defaultWidth)
            assertEquals(24.dp, icon.defaultHeight)
            assertEquals(24f, icon.viewportWidth)
            assertEquals(24f, icon.viewportHeight)
            val path = icon.root[0] as VectorPath
            assertTrue(icon.name, path.pathData.isNotEmpty())
            assertEquals(2f, path.strokeLineWidth)
            assertEquals(StrokeCap.Round, path.strokeLineCap)
        }
    }

    @Test fun transmittedClusterBytesSelectDistinctSemanticSymbols() {
        for ((byte, shape) in listOf(
            1 to ManeuverShape.TURN, 4 to ManeuverShape.TURN,
            3 to ManeuverShape.SLIGHT, 6 to ManeuverShape.SLIGHT,
            2 to ManeuverShape.SHARP, 5 to ManeuverShape.SHARP,
            7 to ManeuverShape.U_TURN, 39 to ManeuverShape.U_TURN,
            9 to ManeuverShape.ARRIVAL,
            11 to ManeuverShape.UNKNOWN, 12 to ManeuverShape.UNKNOWN,
            13 to ManeuverShape.T_JUNCTION, 14 to ManeuverShape.T_JUNCTION,
            27 to ManeuverShape.KEEP, 28 to ManeuverShape.KEEP,
            29 to ManeuverShape.SHARP, 30 to ManeuverShape.SHARP,
            31 to ManeuverShape.MERGE, 32 to ManeuverShape.MERGE,
            38 to ManeuverShape.RAMP, 10 to ManeuverShape.RAMP,
            45 to ManeuverShape.ROUNDABOUT, 41 to ManeuverShape.HEADING,
            46 to ManeuverShape.UNKNOWN, 255 to ManeuverShape.UNKNOWN,
        )) assertEquals("cluster byte $byte", shape, maneuverSymbol(byte).shape)
        assertEquals("Slight left", maneuverSymbol(3).label)
        assertEquals("Sharp left", maneuverSymbol(2).label)
        assertEquals(45f, maneuverSymbol(41).rotation)
        assertNotEquals(maneuverSymbol(27), maneuverSymbol(28))
        assertNotEquals(maneuverSymbol(1), maneuverSymbol(13))
    }
}
