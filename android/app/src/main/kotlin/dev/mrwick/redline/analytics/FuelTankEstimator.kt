package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.FuelFillEntity

/**
 * Estimated tank state: litres remaining, fill fraction, and resulting range.
 *
 * [isRough] is true when derived from the bike's coarse 6-bar gauge rather than
 * the fill ledger. Neither source measures litres directly.
 */
data class FuelEstimate(
    val litresLeft: Double,
    val percent: Double, // 0.0..1.0
    val rangeKm: Double,
    val kmPerLUsed: Double,
    val isRough: Boolean,
)

/**
 * Estimates litres remaining + range, preferring the cluster's current gauge.
 * Bar-to-litre conversion is a linear approximation, not a calibrated sensor.
 *
 * Without a valid gauge reading, assume the rider fills to full: the latest fill re-anchors the
 * tank to [capacityL]. We subtract litres consumed since that fill, where
 * consumed = (current odo - fill odo) / km-per-L. The km/L used is the measured
 * fill-to-fill average ([MileageAnalytics.averageKmPerL]); callers pass bike-live
 * and fixed-default fallbacks for the window before 2 fills exist.
 *
 * Pure JVM, deterministic - tested in FuelTankEstimatorTest.
 */
object FuelTankEstimator {

    // ASSUMED: ~45 km/L for the Gixxer SF 150 - unverified; only used for the
    // pre-first-fill bootstrap when the bike isn't live. Tune once measured.
    const val DEFAULT_FALLBACK_KM_PER_L: Double = 45.0

    private const val FUEL_BARS_MAX = 6.0

    fun estimate(
        fills: List<FuelFillEntity>,
        currentOdometerKm: Int?,
        avgKmPerL: Double?,
        bikeLiveKmPerL: Double?,
        bikeFuelBars: Int?,
        capacityL: Double,
        fallbackKmPerL: Double = DEFAULT_FALLBACK_KM_PER_L,
    ): FuelEstimate? {
        if (capacityL <= 0.0) return null
        val kmPerL = listOfNotNull(avgKmPerL, bikeLiveKmPerL).firstOrNull { it > 0.0 } ?: fallbackKmPerL
        if (kmPerL <= 0.0 || kmPerL.isNaN()) return null

        val bars = bikeFuelBars?.takeIf { it in 0..6 }
        if (bars != null) {
            val litres = bars / FUEL_BARS_MAX * capacityL
            return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = true)
        }

        val anchor = fills.maxByOrNull { it.tMillis }
        if (anchor != null && currentOdometerKm != null) {
            val kmSince = (currentOdometerKm - anchor.odometerKm).coerceAtLeast(0)
            val litres = (capacityL - kmSince / kmPerL).coerceIn(0.0, capacityL)
            return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = false)
        }

        return null
    }
}
