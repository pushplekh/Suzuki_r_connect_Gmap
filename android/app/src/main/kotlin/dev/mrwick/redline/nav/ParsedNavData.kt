package dev.mrwick.redline.nav

import androidx.compose.runtime.Immutable
import dev.mrwick.redline.protocol.NavFrame
import java.time.LocalTime
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Normalized Google Maps notification content, ready to build a [NavFrame] from.
 *
 * All string fields are already padded to the on-the-wire widths expected by
 * the a531 frame layout (see [NavFrame] KDoc). Numeric values are encoded as
 * ASCII; unit fields are single-char ('K' or 'M'). [streetName] is retained for
 * diagnostics and optional compact text; it is not a dedicated wire text field.
 */
@Immutable
data class ParsedNavData(
    val maneuverId: Int,            // Suzuki cluster byte (1..52). 8 = straight/forward.
                                    // Translated from Mappls ID by GoogleMapsParser
                                    // via ManeuverMap.mapplsIdToClusterByte.
    val distNext: String,           // 4-char ASCII, e.g. "0220" or "01.2"
    val distNextUnit: String,       // "M" or "K"
    val eta: String,                // 6-char ASCII, e.g. "0530PM" or "173000"
    val distTotal: String,          // 4-char ASCII
    val distTotalUnit: String,      // "M" or "K"
    val streetName: String?,        // full instruction for diagnostics and optional compact text
    val status: String = "1",       // '5' when Maps explicitly reports destination arrival
    val traceId: String? = null,
    val sourceElapsedMs: Long? = null,
    val navigationState: MapsNavigationState = MapsNavigationState.NAVIGATING,
    val nextDistanceKnown: Boolean = true,
    val routeRemainingMetres: Int? = null,
    val routeTravelledMetres: Int? = null,
    val totalDistanceSource: String = "unspecified",
    val roundaboutExit: Int? = null,
)

/**
 * Convert this normalized parse into an a531 [NavFrame] ready for the BLE
 * write path. [status] carries the normal or destination-arrival state;
 * [NavFrame.continueFlag] remains "1" (not in teardown).
 */
fun ParsedNavData.toNavFrame(): NavFrame =
    NavFrame(
        maneuverId = maneuverId,
        distNext = distNext,
        distNextUnit = distNextUnit,
        eta = eta,
        distTotal = distTotal,
        distTotalUnit = distTotalUnit,
        status = status,
        continueFlag = "1",  // not in teardown
        traceId = traceId,
        sourceElapsedMs = sourceElapsedMs,
    )

// ----------------------------------------------------------------------------
// Distance / ETA normalization helpers (internal to the nav package).
// ----------------------------------------------------------------------------

/**
 * Matches a number (possibly with `.` or `,` decimal) followed by an English unit
 * (`m`, `km`, `mi`, `ft`). The number is captured in group 1 and the unit in group 2.
 * Case-insensitive with optional whitespace between number and unit.
 * Callers extract a distance field before normalization.
 */
private val DISTANCE_REGEX = Regex(
    """(\d+(?:[.,]\d+)?)\s*(kilometers?|meters?|miles?|feet|km|mi|ft|m)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Turn "220 m" into `("0220","M")`, "1.2 km" into `("01.2","K")`, "85 m"
 * into `("0085","M")`. Returns a blank field on missing/unparseable input.
 *
 * - Imperial distances are converted numerically to metres/kilometres before
 *   encoding with the cluster's 'M'/'K' labels.
 * - Distances below 1 km use metres; longer distances use kilometres with one
 *   decimal below 100 km, then whole kilometres to fit four ASCII characters.
 *   Unrepresentable values are blank, never silently clamped to 9999 metres.
 */
internal fun normalizeDistance(input: String?): Pair<String, String> {
    if (input.isNullOrBlank()) return "    " to "M"
    val match = DISTANCE_REGEX.matchEntire(input.replace('\u00a0', ' ').replace('\u202f', ' ').trim())
    if (match == null) {
        dev.mrwick.redline.util.AppLog.w("NavDistance", "Unrecognized distance: $input")
        return "    " to "M"
    }
    val number = match.groupValues[1].replace(',', '.').toBigDecimal()
    val unitRaw = match.groupValues[2].lowercase(Locale.ROOT)
    val metres = number.multiply(when (unitRaw) {
        "km", "kilometer", "kilometers" -> BigDecimal("1000")
        "mi", "mile", "miles" -> BigDecimal("1609.344")
        "ft", "feet" -> BigDecimal("0.3048")
        else -> BigDecimal.ONE
    })
    return formatDistanceMetres(metres)
}

internal fun formatDistanceMetres(metres: BigDecimal): Pair<String, String> {
    require(metres.signum() >= 0) { "Distance must not be negative" }
    val roundedMetres = metres.setScale(0, RoundingMode.HALF_UP)
    if (roundedMetres < BigDecimal("1000")) {
        return roundedMetres.toPlainString().padStart(4, '0') to "M"
    }
    val km = metres.movePointLeft(3)
    val decimal = km.setScale(1, RoundingMode.HALF_UP)
    val formatted = if (decimal < BigDecimal("100")) decimal.toPlainString()
        else km.setScale(0, RoundingMode.HALF_UP).toPlainString()
    if (formatted.length > 4) {
        dev.mrwick.redline.util.AppLog.w("NavDistance", "Distance exceeds four-character km field: metres=$metres")
        return "    " to "K"
    }
    return formatted.padStart(4, '0') to "K"
}

fun navigationDistanceLabel(value: String, unit: String): String {
    val numeric = value.trim().toBigDecimalOrNull() ?: return "unavailable"
    val suffix = when (unit.trim().uppercase(Locale.ROOT)) {
        "K" -> "km"
        "M" -> "m"
        else -> {
            dev.mrwick.redline.util.AppLog.w("NavDistance", "Unknown distance unit: $unit")
            return "unavailable"
        }
    }
    return "${numeric.stripTrailingZeros().toPlainString()} $suffix"
}

/**
 * Matches a clock time. Captures hour, minute, and (optional) AM/PM marker.
 * Hour can be 1 or 2 digits; minute always 2 digits.
 */
private val CLOCK_REGEX = Regex(
    """\b(\d{1,2}):(\d{2})\s*(am|pm)?\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Matches a "5 min" or "23 mins" segment for fallback ETA computation when
 * no absolute clock time is present in the input.
 */
private val MINUTES_REGEX = Regex("""\b(\d+)\s*mins?\b""", RegexOption.IGNORE_CASE)

internal fun etaInputSource(text: String?): String = when {
    text != null && CLOCK_REGEX.containsMatchIn(text) -> "explicit-clock"
    text != null && MINUTES_REGEX.containsMatchIn(text) -> "duration-plus-phone-clock"
    else -> "unavailable-NOT-MAPS-ETA"
}

/**
 * From a Google Maps nav_time string like `"5 min · 1.2 km · 4:32 PM"`,
 * extract the arrival clock as the 6-char ASCII expected by a531's ETA field.
 *
 * - When [twelveHour] is true: returns "HHMMAM" or "HHMMPM" (e.g. "0432PM").
 * - When [twelveHour] is false: returns "HHMM00" (e.g. "163200") — the
 *   trailing "00" mirrors what Suzuki Connect itself emits in 24h mode
 *   (NOTES.md "ETA padded to 6").
 * - Fallback: if no clock present but a "N min" segment is, current
 *   wall-clock + N minutes is used. With neither value, return six spaces,
 *   not the current clock disguised as an arrival time.
 *
 * ASSUMED: Maps' nav_time text is always in the device locale; for non-en
 * locales the clock part may use a different separator or numerals, in
 * which case the regex misses and the arrival time remains unavailable. Empirical
 * tuning per locale TBD.
 */
internal fun normalizeEta(remainingText: String?, twelveHour: Boolean): String {
    val normalized = remainingText?.replace('\u00a0', ' ')?.replace('\u202f', ' ')
    val clockMatch = normalized?.let { CLOCK_REGEX.find(it) }
    val (hour24, minute) = if (clockMatch != null) {
        val rawHour = clockMatch.groupValues[1].toInt()
        val minutes = clockMatch.groupValues[2].toInt()
        val ampm = clockMatch.groupValues[3].lowercase(Locale.ROOT)
        if (minutes !in 0..59 || (ampm.isNotEmpty() && rawHour !in 1..12) ||
            (ampm.isEmpty() && rawHour !in 0..23)
        ) return "      "
        val h24 = when (ampm) {
            "am" -> if (rawHour == 12) 0 else rawHour
            "pm" -> if (rawHour == 12) 12 else rawHour + 12
            else -> rawHour
        }
        h24 to minutes
    } else {
        // Try "N min" -> now + N min
        val minMatch = normalized?.let { MINUTES_REGEX.find(it) } ?: return "      "
        val addMin = minMatch.groupValues[1].toIntOrNull() ?: return "      "
        val now = LocalTime.now().plusMinutes(addMin.toLong())
        now.hour to now.minute
    }

    return if (twelveHour) {
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        "%02d%02d%s".format(Locale.ROOT, hour12, minute, ampm)
    } else {
        "%02d%02d00".format(Locale.ROOT, hour24, minute)
    }
}
