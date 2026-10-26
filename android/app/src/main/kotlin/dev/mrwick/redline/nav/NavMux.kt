package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Priority mux: prefer the Google Maps frame when present; fall back to the
 * idle clock generator otherwise.
 *
 * Caller is the BikeBridgeService heartbeat loop, which collects [frame]
 * and writes each emission to the bike at the desired a531 cadence.
 */
class NavMux(
    private val mapsFlow: Flow<NavFrame?>,
    private val idleClockFlow: Flow<NavFrame?>,
) {
    /** Null means neither navigation nor an enabled idle display is available. */
    val frame: Flow<NavFrame?> = combine(mapsFlow, idleClockFlow.onStart { emit(null) }) { maps, idle ->
        maps ?: idle
    }.distinctUntilChanged()
}
