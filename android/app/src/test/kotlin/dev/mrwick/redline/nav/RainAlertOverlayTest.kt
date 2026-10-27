package dev.mrwick.redline.nav

import dev.mrwick.redline.ble.HeartbeatLoop
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.system.VoiceAlertPolicy
import dev.mrwick.redline.weather.SuzukiWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RainAlertOverlayTest {
    private fun nav(trace: String? = "t1", status: String = "1") =
        NavFrame(12, "0200", "M", "1030AM", "0050", "K", status, "1", traceId = trace)

    @Test
    fun alternatesRainIconForTenTicksThenStops() {
        val o = RainAlertOverlay()
        val ids = (0 until 12).map { o.apply(nav(), true, SuzukiWeather.RAIN, it * 1_000L)!!.maneuverId }
        assertEquals(listOf(57, 12, 57, 12, 57, 12, 57, 12, 57, 12, 12, 12), ids)
    }

    @Test
    fun noOverlayWhenDisabledDryOrIdle() {
        assertEquals(12, RainAlertOverlay().apply(nav(), false, SuzukiWeather.RAIN, 0)!!.maneuverId)
        assertEquals(12, RainAlertOverlay().apply(nav(), true, SuzukiWeather.CLOUDY, 0)!!.maneuverId)
        assertEquals(12, RainAlertOverlay().apply(nav(trace = null), true, SuzukiWeather.RAIN, 0)!!.maneuverId)
    }

    @Test
    fun degradedStatusKeepsFrameButCountsTick() {
        val o = RainAlertOverlay()
        assertEquals(12, o.apply(nav(status = "2"), true, SuzukiWeather.THUNDER, 0)!!.maneuverId)
    }

    @Test
    fun retriggersWhenWeatherTurnsRainyOrNewSession() {
        val o = RainAlertOverlay()
        repeat(10) { o.apply(nav(), true, SuzukiWeather.SUNNY, it * 1_000L) }
        assertEquals(57, o.apply(nav(), true, SuzukiWeather.LIGHT_RAIN, 10_000)!!.maneuverId)
        repeat(9) { o.apply(nav(), true, SuzukiWeather.LIGHT_RAIN, 11_000L + it * 1_000L) }
        assertEquals(12, o.apply(nav(), true, SuzukiWeather.LIGHT_RAIN, 21_000)!!.maneuverId)
        o.apply(null, true, SuzukiWeather.LIGHT_RAIN, 30_000)
        assertEquals(12, o.apply(nav(), true, SuzukiWeather.LIGHT_RAIN, 60_000)!!.maneuverId)
        o.apply(null, true, SuzukiWeather.LIGHT_RAIN, 70_000)
        o.apply(null, true, SuzukiWeather.LIGHT_RAIN, 70_000 + 5 * 60_000L + 1)
        assertEquals(57, o.apply(nav(), true, SuzukiWeather.LIGHT_RAIN, 400_000)!!.maneuverId)
    }

    @Test
    fun rainCodesFollowOemKeywords() {
        assertEquals(
            setOf(4, 5, 6, 8),
            (0..11).filter { RainAlertOverlay.isRainy(it) }.toSet(),
        )
    }
}

class SpeedLimitHeartbeatTest {
    private fun loop(limit: Int) = HeartbeatLoop({ 100 to false }, { 3 }, { false }, { false }, { 1 to 0 }, { limit })

    @Test
    fun enabledLimitEncodesThreeDigitsInBytes4To6() {
        val bytes = loop(60).buildFrame().encode()
        assertEquals("060", String(bytes, 4, 3, Charsets.US_ASCII))
        assertEquals("120", String(loop(120).buildFrame().encode(), 4, 3, Charsets.US_ASCII))
    }

    @Test
    fun disabledLimitEncodesFfFfFf() {
        val bytes = loop(0).buildFrame().encode()
        for (i in 4..6) assertEquals(0xFF, bytes[i].toInt() and 0xFF)
    }
}

class VoiceAlertPolicyTest {
    @Test
    fun connectAndDisconnectSpokenOncePerTransition() {
        val p = VoiceAlertPolicy()
        assertNull(p.onDisconnected())
        assertEquals("Connected", p.onReady())
        assertNull(p.onReady())
        assertEquals("Disconnected", p.onDisconnected())
        assertNull(p.onDisconnected())
    }

    @Test
    fun lowFuelOncePerConnection() {
        val p = VoiceAlertPolicy()
        assertNull(p.onTelemetryByte24(0x30, false))
        p.onReady()
        assertNull(p.onTelemetryByte24(0x31, false))
        assertEquals("Low Fuel Level", p.onTelemetryByte24(0x30, false))
        assertNull(p.onTelemetryByte24(0x30, false))
        p.onDisconnected()
        p.onReady()
        assertEquals("Low Fuel Level", p.onTelemetryByte24(0xFF, false))
    }

    @Test
    fun eAccessUsesBatteryThreshold() {
        val p = VoiceAlertPolicy()
        p.onReady()
        assertNull(p.onTelemetryByte24(64 + 26, true))
        assertEquals("Low Battery Level", p.onTelemetryByte24(64 + 25, true))
    }
}
