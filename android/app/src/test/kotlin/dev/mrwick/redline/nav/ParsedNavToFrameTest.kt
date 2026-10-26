package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [ParsedNavData.toNavFrame] and the helper normalizers. */
class ParsedNavToFrameTest {

    @Test
    fun `toNavFrame copies fields and sets status and continue`() {
        val parsed = ParsedNavData(
            maneuverId = 3,
            distNext = "0220",
            distNextUnit = "M",
            eta = "0432PM",
            distTotal = "01.2",
            distTotalUnit = "K",
            streetName = "MG Road",
        )
        val frame = parsed.toNavFrame()
        assertEquals(3, frame.maneuverId)
        assertEquals("0220", frame.distNext)
        assertEquals("M", frame.distNextUnit)
        assertEquals("0432PM", frame.eta)
        assertEquals("01.2", frame.distTotal)
        assertEquals("K", frame.distTotalUnit)
        assertEquals("1", frame.status)
        assertEquals("1", frame.continueFlag)
    }

    @Test
    fun `toNavFrame round-trips through encode-decode`() {
        val parsed = ParsedNavData(
            maneuverId = 3,
            distNext = "0220",
            distNextUnit = "M",
            eta = "0432PM",
            distTotal = "01.2",
            distTotalUnit = "K",
            streetName = "MG Road",
        )
        val bytes = parsed.toNavFrame().encode()
        val decoded = NavFrame.decode(bytes)
        assertEquals(3, decoded.maneuverId)
        assertEquals("0220", decoded.distNext)
        assertEquals("M", decoded.distNextUnit)
        assertEquals("0432PM", decoded.eta)
        assertEquals("01.2", decoded.distTotal)
        assertEquals("K", decoded.distTotalUnit)
        assertEquals("1", decoded.status)
        assertEquals("1", decoded.continueFlag)
    }

    @Test
    fun `destination arrival status survives frame encoding`() {
        val frame = ParsedNavData(
            maneuverId = 9,
            distNext = "0000",
            distNextUnit = "M",
            eta = "0432PM",
            distTotal = "0000",
            distTotalUnit = "M",
            streetName = "You have arrived at your destination",
            status = "5",
        ).toNavFrame()

        val decoded = NavFrame.decode(frame.encode())
        assertEquals(9, decoded.maneuverId)
        assertEquals("0000", decoded.distNext)
        assertEquals("0000", decoded.distTotal)
        assertEquals("5", decoded.status)
    }

    // ----- normalizeDistance ------------------------------------------------

    @Test
    fun `normalizeDistance handles meters whole numbers`() {
        assertEquals("0220" to "M", normalizeDistance("220 m"))
        assertEquals("0085" to "M", normalizeDistance("85 m"))
        assertEquals("0001" to "M", normalizeDistance("1 m"))
    }

    @Test
    fun `normalizeDistance handles km with one decimal`() {
        assertEquals("01.2" to "K", normalizeDistance("1.2 km"))
        assertEquals("12.0" to "K", normalizeDistance("12.0 km"))
    }

    @Test
    fun `normalizeDistance handles comma decimal (european locale)`() {
        assertEquals("01.5" to "K", normalizeDistance("1,5 km"))
    }

    @Test
    fun `normalizeDistance leaves unknown distance blank instead of inventing zero`() {
        for (input in listOf(null, "", "not a distance", "-1 m", "NaN km", "Infinity km")) {
            assertEquals(input, "    " to "M", normalizeDistance(input))
        }
    }

    @Test
    fun `normalizeDistance converts miles and feet to metric`() {
        assertEquals("01.9" to "K", normalizeDistance("1.2 mi"))
        assertEquals("0152" to "M", normalizeDistance("500 ft"))
    }

    @Test
    fun `normalizeDistance handles km whole numbers up to 4 digits`() {
        assertEquals("1234" to "K", normalizeDistance("1234 km"))
    }

    @Test fun `metric conversion uses actual value and fits four ASCII characters`() {
        for ((input, expected) in listOf(
            "450 m" to ("0450" to "M"),
            "999 m" to ("0999" to "M"),
            "1000 m" to ("01.0" to "K"),
            "1200 m" to ("01.2" to "K"),
            "9999 m" to ("10.0" to "K"),
            "10000 m" to ("10.0" to "K"),
            "12500 m" to ("12.5" to "K"),
            "99999 m" to ("0100" to "K"),
            "100000 m" to ("0100" to "K"),
            "9999000 m" to ("9999" to "K"),
            "10000000 m" to ("    " to "K"),
        )) {
            val normalized = normalizeDistance(input)
            assertEquals(input, expected, normalized)
            assertEquals(input, 4, normalized.first.length)
            assertEquals(input, normalized, normalizeDistance(input.replace(" m", " meters")))
        }
        assertEquals("450 m", navigationDistanceLabel("0450", "M"))
        assertEquals("1.2 km", navigationDistanceLabel("01.2", "K"))
        assertEquals("10 km", navigationDistanceLabel("10.0", "K"))
        assertEquals("12.5 km", navigationDistanceLabel("12.5", "K"))
        assertEquals("unavailable", navigationDistanceLabel("    ", "K"))
    }

    // ----- normalizeEta -----------------------------------------------------

    @Test
    fun `normalizeEta extracts clock from full nav_time string in 12h`() {
        val input = "5 min · 1.2 km · 4:32 PM"
        assertEquals("0432PM", normalizeEta(input, twelveHour = true))
    }

    @Test
    fun `normalizeEta extracts clock from full nav_time string in 24h`() {
        val input = "5 min · 1.2 km · 16:32"
        assertEquals("163200", normalizeEta(input, twelveHour = false))
    }

    @Test
    fun `normalizeEta handles AM noon and midnight boundaries`() {
        // 12:00 AM = midnight = 00:00 in 24h, 12:00AM in 12h
        assertEquals("1200AM", normalizeEta("ETA 12:00 AM", twelveHour = true))
        // 12:00 PM = noon = 12:00 in 24h, 12:00PM in 12h
        assertEquals("1200PM", normalizeEta("ETA 12:00 PM", twelveHour = true))
        // 1:30 PM = 13:30 in 24h
        assertEquals("133000", normalizeEta("ETA 1:30 PM", twelveHour = false))
    }
}
