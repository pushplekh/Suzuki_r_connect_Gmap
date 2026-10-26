package dev.mrwick.redline.ui.cluster

internal enum class ManeuverShape { TURN, SLIGHT, SHARP, KEEP, T_JUNCTION, RAMP, MERGE, FORK, U_TURN, STRAIGHT, ARRIVAL, HEADING, ROUNDABOUT, UNKNOWN }

internal data class ManeuverSymbol(
    val shape: ManeuverShape,
    val label: String,
    val left: Boolean = false,
    val rotation: Float = 0f,
)

/** Input is the OEM cluster byte, not a phone-side Mappls icon ID. */
internal fun maneuverSymbol(clusterByte: Int): ManeuverSymbol = when (clusterByte) {
    1 -> ManeuverSymbol(ManeuverShape.TURN, "Turn left", left = true)
    2 -> ManeuverSymbol(ManeuverShape.SHARP, "Sharp left", left = true)
    3 -> ManeuverSymbol(ManeuverShape.SLIGHT, "Slight left", left = true)
    4 -> ManeuverSymbol(ManeuverShape.TURN, "Turn right")
    5 -> ManeuverSymbol(ManeuverShape.SHARP, "Sharp right")
    6 -> ManeuverSymbol(ManeuverShape.SLIGHT, "Slight right")
    7, 34 -> ManeuverSymbol(ManeuverShape.U_TURN, "U-turn", left = true)
    39, 36 -> ManeuverSymbol(ManeuverShape.U_TURN, "U-turn right")
    8, 33 -> ManeuverSymbol(ManeuverShape.STRAIGHT, "Continue straight")
    9 -> ManeuverSymbol(ManeuverShape.ARRIVAL, "Destination reached")
    11, 12 -> ManeuverSymbol(ManeuverShape.UNKNOWN, "Unverified maneuver")
    13 -> ManeuverSymbol(ManeuverShape.T_JUNCTION, "Left at T-junction", left = true)
    14 -> ManeuverSymbol(ManeuverShape.T_JUNCTION, "Right at T-junction")
    35, 38 -> ManeuverSymbol(ManeuverShape.RAMP, "Left ramp / exit", left = true)
    10, 37 -> ManeuverSymbol(ManeuverShape.RAMP, "Right ramp / exit")
    29 -> ManeuverSymbol(ManeuverShape.SHARP, "Sharp left at fork", left = true)
    30 -> ManeuverSymbol(ManeuverShape.SHARP, "Sharp right at fork")
    27 -> ManeuverSymbol(ManeuverShape.KEEP, "Keep left at fork", left = true)
    28 -> ManeuverSymbol(ManeuverShape.KEEP, "Keep right at fork")
    31 -> ManeuverSymbol(ManeuverShape.MERGE, "Continue / merge left", left = true)
    32 -> ManeuverSymbol(ManeuverShape.MERGE, "Continue / merge right")
    40 -> ManeuverSymbol(ManeuverShape.HEADING, "Head north")
    41 -> ManeuverSymbol(ManeuverShape.HEADING, "Head northeast", rotation = 45f)
    42 -> ManeuverSymbol(ManeuverShape.HEADING, "Head east", rotation = 90f)
    15 -> ManeuverSymbol(ManeuverShape.HEADING, "Head southeast", rotation = 135f)
    16 -> ManeuverSymbol(ManeuverShape.HEADING, "Head south", rotation = 180f)
    17 -> ManeuverSymbol(ManeuverShape.HEADING, "Head southwest", rotation = 225f)
    18 -> ManeuverSymbol(ManeuverShape.HEADING, "Head west", rotation = 270f)
    19 -> ManeuverSymbol(ManeuverShape.HEADING, "Head northwest", rotation = 315f)
    44 -> ManeuverSymbol(ManeuverShape.UNKNOWN, "Model-dependent maneuver")
    in 20..26, 45, in 47..52 -> ManeuverSymbol(ManeuverShape.ROUNDABOUT, "Roundabout")
    else -> ManeuverSymbol(ManeuverShape.UNKNOWN, "Maneuver unavailable")
}
