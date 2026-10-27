package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.weather.SuzukiWeather

/**
 * Rain alert during Maps navigation.
 *
 * Ride Connect 7.6 (`r0.onSuccess` / `L()`) alternates maneuver byte 57 with the
 * real maneuver for 10 one-second ticks when the forecast is rainy on Access-family
 * clusters. Redline's trigger cadence is a design choice: once per navigation
 * session, and again when the weather turns rainy mid-session.
 */
class RainAlertOverlay(
    private val durationTicks: Int = 10,
    private val sessionGapMs: Long = 5 * 60_000L,
) {
    private var inSession = false
    private var lastNavActiveMs = Long.MIN_VALUE
    private var wasRainy = false
    private var remaining = 0
    private var tick = 0

    fun apply(nav: NavFrame?, enabled: Boolean, weatherCode: Int?, nowMs: Long): NavFrame? {
        if (nav?.traceId == null) {
            if (inSession && nowMs - lastNavActiveMs > sessionGapMs) reset()
            return nav
        }
        lastNavActiveMs = nowMs
        val rainy = enabled && weatherCode != null && isRainy(weatherCode)
        if (rainy && (!inSession || !wasRainy)) {
            remaining = durationTicks
            tick = 0
        }
        if (!enabled) remaining = 0
        inSession = true
        wasRainy = rainy
        if (remaining <= 0) return nav
        val showIcon = tick % 2 == 0
        tick++
        remaining--
        return if (showIcon && nav.status in RENDERED_STATUSES) nav.copy(maneuverId = RAIN_ICON) else nav
    }

    private fun reset() {
        inSession = false
        wasRainy = false
        remaining = 0
        tick = 0
    }

    companion object {
        const val RAIN_ICON = 57

        /** B0.D writes the maneuver byte only for these statuses. */
        val RENDERED_STATUSES = setOf("1", "3", "5")

        /** OEM rain keywords map to these codes; OEM "cloudy" triggers are deliberately excluded. */
        fun isRainy(code: Int): Boolean = code in setOf(
            SuzukiWeather.LIGHT_RAIN,
            SuzukiWeather.THUNDER,
            SuzukiWeather.RAIN,
            SuzukiWeather.SLEET,
        )
    }
}
