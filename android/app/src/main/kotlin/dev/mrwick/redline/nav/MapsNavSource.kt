package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide handoff: NotificationCaptureService writes when a Maps nav
 * notification arrives or is dismissed; BikeBridgeService.NavMux observes.
 *
 * STALE NAV PROTECTION: Maps doesn't always dismiss its persistent navigation
 * notification when the user exits the app — sometimes it stays posted with the
 * last instruction. Each [update] schedules a watchdog that clears the frame if
 * no fresh update arrives within [STALE_AFTER_MS]. Without this the cluster
 * keeps showing the stale arrow forever after navigation ends.
 */
object MapsNavSource : NavSource {
    /** Rider-selected timeout without a fresh Maps update; not divergence detection. */
    const val STALE_AFTER_MS: Long = 30_000
    private val session = MapsNavSession(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    override val frame get() = session.frame
    val parsed get() = session.parsed
    val freshness get() = session.freshness
    fun update(parsed: ParsedNavData?) = session.update(parsed)
    fun clear() = session.clear()
}

internal class MapsNavSession(
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : NavSource {
    private val TAG = "MapsNavSource"
    private val _frame = MutableStateFlow<NavFrame?>(null)
    override val frame: StateFlow<NavFrame?> = _frame.asStateFlow()
    private val _parsed = MutableStateFlow<ParsedNavData?>(null)
    val parsed: StateFlow<ParsedNavData?> = _parsed.asStateFlow()
    private val _freshness = MutableStateFlow(NavigationFreshness())
    val freshness: StateFlow<NavigationFreshness> = _freshness.asStateFlow()

    @Volatile private var staleJob: Job? = null

    @Synchronized
    fun update(parsed: ParsedNavData?) {
        val previousAge = _freshness.value.receivedElapsedMs?.let { elapsedMs() - it }
        if (parsed != null && previousAge != null && previousAge >= 10_000) {
            AppLog.i("NavTrace", "INPUT_RESUMED trace=${parsed.traceId} gapMs=$previousAge previousState=${_freshness.value.state}")
        }
        AppLog.i("NavTrace", "SOURCE update trace=${parsed?.traceId ?: "none"} previous=${_frame.value?.traceId ?: "none"}")
        val previous = _parsed.value
        val carryExit = parsed?.navigationState == MapsNavigationState.NAVIGATING &&
            parsed.roundaboutExit == null && MapplsIdGuesser.isRoundaboutExit(parsed.streetName) &&
            previous?.navigationState == MapsNavigationState.NAVIGATING &&
            previous.roundaboutExit != null && previousAge != null &&
            previousAge in 0 until MapsNavSource.STALE_AFTER_MS &&
            MapplsIdGuesser.fromTextOrNull(previous.streetName) == 72
        val current = if (carryExit) {
            parsed?.copy(roundaboutExit = previous?.roundaboutExit).also {
                AppLog.i("NavTrace", "ROUNDABOUT_CONTEXT trace=${parsed?.traceId} exit=${it?.roundaboutExit} source=previous-fresh-roundabout previous=${previous?.traceId}")
            }
        } else parsed
        _parsed.value = current
        _frame.value = current?.toNavFrame()
        _freshness.value = if (parsed != null) {
            NavigationFreshness(elapsedMs(), parsed.navigationState)
        } else {
            _freshness.value.copy(state = null, stale = false)
        }
        // Reset the stale watchdog every time a fresh update arrives.
        staleJob?.cancel()
        if (_frame.value != null) {
            val expected = _frame.value
            staleJob = scope.launch {
                delay(MapsNavSource.STALE_AFTER_MS)
                synchronized(this@MapsNavSession) {
                    if (_frame.value === expected) {
                        val arrived = _parsed.value?.navigationState == MapsNavigationState.ARRIVED
                        AppLog.i(TAG, "expiring nav trace=${_frame.value?.traceId} arrived=$arrived (no Maps update in ${MapsNavSource.STALE_AFTER_MS / 1000}s)")
                        _frame.value = null
                        _parsed.value = null
                        _freshness.value = _freshness.value.copy(stale = !arrived)
                    }
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        if (_parsed.value?.navigationState == MapsNavigationState.ARRIVED) {
            AppLog.i(TAG, "retaining arrival trace=${_frame.value?.traceId} until original 30s expiry (notification removed)")
            return
        }
        if (_frame.value != null) AppLog.i(TAG, "cleared trace=${_frame.value?.traceId} (notification removed)")
        _frame.value = null
        _parsed.value = null
        _freshness.value = _freshness.value.copy(state = null, stale = false)
        staleJob?.cancel()
    }
}
