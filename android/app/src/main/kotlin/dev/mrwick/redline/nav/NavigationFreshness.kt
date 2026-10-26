package dev.mrwick.redline.nav

data class NavigationFreshness(
    val receivedElapsedMs: Long? = null,
    val state: MapsNavigationState? = null,
    val stale: Boolean = false,
) {
    fun ageSeconds(nowElapsedMs: Long): Long? =
        receivedElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0) / 1_000 }

    fun isStale(nowElapsedMs: Long): Boolean =
        stale || (state != null && state != MapsNavigationState.ARRIVED && receivedElapsedMs != null &&
            nowElapsedMs - receivedElapsedMs >= MapsNavSource.STALE_AFTER_MS)

    fun message(nowElapsedMs: Long): String = when {
        isStale(nowElapsedMs) ->
            "Stale Maps guidance - waiting for a fresh update"
        state == MapsNavigationState.REROUTING -> "Maps is rerouting - waiting for a new route"
        state == MapsNavigationState.STARTING -> "Waiting for Maps route instructions"
        state == MapsNavigationState.ARRIVED -> "Destination arrival reported by Maps"
        state == MapsNavigationState.NAVIGATING -> "Google Maps navigation"
        else -> "Waiting for Maps navigation"
    }

    fun ageMessage(nowElapsedMs: Long): String =
        ageSeconds(nowElapsedMs)?.let { "Last Maps update: $it seconds ago" }
            ?: "Last Maps update: none received this session"
}
