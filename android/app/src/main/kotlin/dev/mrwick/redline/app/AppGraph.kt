package dev.mrwick.redline.app

import android.content.Context
import dev.mrwick.redline.ble.BikeInfo
import dev.mrwick.redline.ble.BleClient
import dev.mrwick.redline.ble.ConnectionState
import dev.mrwick.redline.ble.FrameStream
import dev.mrwick.redline.ble.FrameWriter
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.QuickDestinations
import dev.mrwick.redline.data.RideMetaStore
import dev.mrwick.redline.data.RideStore
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.location.LastParkedTracker
import dev.mrwick.redline.nav.NavMux
import dev.mrwick.redline.protocol.isWellFormed
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singletons accessed by Compose UI + the foreground service.
 * Populated by `BikeBridgeService.onCreate()`; until then the stubs serve UI work
 * (Inspector + Dashboard render fine with no data).
 */
object AppGraph {

    /** TX + RX stream — Inspector observes. Always present (real or stub). */
    val frameStream: FrameStream = FrameStream()

    /** Live BLE connection state. UI may show "Connecting / Ready" etc. */
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Wired by BikeBridgeService when it constructs the real client. */
    @Volatile var bleClient: BleClient? = null
    @Volatile var frameWriter: FrameWriter? = null
    @Volatile var navMux: NavMux? = null

    private val displayProbeWindow = dev.mrwick.redline.nav.DisplayProbeWindow()
    internal fun suppressAutomaticDisplay(): Boolean =
        displayProbeWindow.suppressAutomaticDisplay(
            System.nanoTime() / 1_000_000,
            dev.mrwick.redline.nav.MapsNavSource.parsed.value != null,
        )

    internal suspend fun testDisplay(
        probe: dev.mrwick.redline.nav.DisplayProbe,
        riderName: String,
        experimentalInstructions: Boolean,
    ): String {
        if (connectionState.value !is ConnectionState.Ready ||
            !displayProbeWindow.begin(
                System.nanoTime() / 1_000_000,
                dev.mrwick.redline.nav.MapsNavSource.parsed.value != null,
            )
        ) {
            AppLog.w("DisplayProbe", "Rejected $probe: requires Ready, no Maps navigation and no other test")
            return "Not started: connect the cluster, stop Maps navigation and wait for any other test to finish."
        }
        AppLog.i("DisplayProbe", "START probe=$probe holdAutomaticDisplayMs=8000 rendering=unverified")
        var result = "Test complete. Record what appeared; a successful write does not prove rendering."
        var restoreFailed = false
        try {
            if (!sendFrame(probe.frame(riderName), note = "display-probe $probe")) {
                result = "Write failed. Check Diagnostics."
            } else {
                for (second in 1..8) {
                    kotlinx.coroutines.delay(1_000)
                    if (dev.mrwick.redline.nav.MapsNavSource.parsed.value != null ||
                        connectionState.value !is ConnectionState.Ready
                    ) {
                        AppLog.w("DisplayProbe", "ABORT probe=$probe navigation started or link lost")
                        result = "Test ended because navigation started or the link was lost. Record what appeared."
                        break
                    }
                }
            }
        } finally {
            try {
                if (probe.testsIdentity) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        val currentInstruction = if (experimentalInstructions) {
                            dev.mrwick.redline.nav.clusterInstructionText(dev.mrwick.redline.nav.MapsNavSource.parsed.value)
                        } else null
                        val restored = sendFrame(
                            dev.mrwick.redline.protocol.IdentityFrame(currentInstruction ?: riderName, isFresh = false).encode(),
                        )
                        if (restored) {
                            AppLog.i("DisplayProbe", "RESTORE identity localWriteSuccess=true navigationText=${currentInstruction != null}")
                        } else {
                            restoreFailed = true
                            AppLog.w("DisplayProbe", "RESTORE identity failed; reconnect to restore the saved rider name")
                        }
                    }
                }
            } finally {
                displayProbeWindow.finish()
                AppLog.i("DisplayProbe", "END probe=$probe normalDisplayResumed=true")
            }
        }
        return if (restoreFailed) "$result Saved-name restoration failed; reconnect and check Diagnostics." else result
    }

    // Transient on-bike test toggles (NOT persisted). Set from DeveloperSettings,
    // read by HeartbeatLoop's a533 providers, so we can flip the SMS/Call
    // indicator bytes (a533 pos 14/15) live and observe which one drives the
    // white "i" LED + the N/Y on/off semantics (DISCOVERIES.md:367). Default
    // false → providers behave as before.
    @Volatile var debugSmsPending: Boolean = false
    @Volatile var debugCallPending: Boolean = false

    fun publishConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Set when a ride finishes with enough distance to be kept (i.e. RideLogger
     * did NOT discard it). The post-ride summary dialog in MainActivity reads
     * this and shows the Spotify-Wrapped-style summary. Cleared when the user
     * dismisses the dialog.
     */
    private val _lastFinishedRideId = MutableStateFlow<Long?>(null)
    val lastFinishedRideId: StateFlow<Long?> = _lastFinishedRideId.asStateFlow()

    /** Called by BikeBridgeService after a non-discarded ride ends. */
    fun publishLastFinishedRideId(rideId: Long) {
        _lastFinishedRideId.value = rideId
    }

    /** Called by the post-ride summary dialog on dismiss to clear the signal. */
    fun clearLastFinishedRide() {
        _lastFinishedRideId.value = null
    }

    /** Standard BLE Device Information Service snapshot — populated by BleClient on first connect. */
    private val _bikeInfo = MutableStateFlow<BikeInfo?>(null)
    val bikeInfo: StateFlow<BikeInfo?> = _bikeInfo.asStateFlow()

    fun publishBikeInfo(info: BikeInfo) {
        _bikeInfo.value = info
    }

    /**
     * Composer / dev tools call this. True means the GATT write completed, not just queued.
     */
    suspend fun sendFrame(bytes: ByteArray, note: String = "composer"): Boolean {
        if (!isWellFormed(bytes)) {
            AppLog.w("Composer", "Cannot send invalid Suzuki frame")
            return false
        }
        val client = bleClient
        if (client == null || client.state.value !is ConnectionState.Ready) {
            AppLog.w("Composer", "Cannot send: cluster is not Ready (${client?.state?.value})")
            return false
        }
        val writer = frameWriter
        return if (writer != null) {
            val completion = CompletableDeferred<Boolean>()
            if (!writer.enqueue(FrameWriter.Entry(
                    FrameWriter.Priority.URGENT, bytes, note = note, completion = completion,
                ))) return false
            withTimeoutOrNull(10_000) { completion.await() } ?: run {
                AppLog.w("Composer", "Timed out waiting for cluster write completion")
                false
            }
        } else {
            client.write(bytes, note = note)
        }
    }

    // PERF: process-wide singletons for DataStore/Room handles. Constructing a
    // fresh `Settings(ctx)` (or RideStore / QuickDestinations / LastParkedTracker)
    // inside every consuming Composable allocated a wrapper per call site and
    // duplicated the DataStore-handle bookkeeping. Centralising here keeps each
    // backing store referenced exactly once per process while preserving every
    // existing call shape — consumers just go through these accessors instead
    // of `remember { Settings(...) }`.
    @Volatile private var _settings: Settings? = null
    @Volatile private var _quickDestinations: QuickDestinations? = null
    @Volatile private var _lastParkedTracker: LastParkedTracker? = null
    @Volatile private var _rideStore: RideStore? = null
    @Volatile private var _rideMetaStore: RideMetaStore? = null

    /** Process-wide [Settings] handle. Safe to call from any thread / composable. */
    fun settings(context: Context): Settings {
        val cached = _settings
        if (cached != null) return cached
        return synchronized(this) {
            _settings ?: Settings(context.applicationContext).also { _settings = it }
        }
    }

    /** Process-wide [QuickDestinations] handle. */
    fun quickDestinations(context: Context): QuickDestinations {
        val cached = _quickDestinations
        if (cached != null) return cached
        return synchronized(this) {
            _quickDestinations ?: QuickDestinations(context.applicationContext)
                .also { _quickDestinations = it }
        }
    }

    /** Process-wide [LastParkedTracker] handle. */
    fun lastParkedTracker(context: Context): LastParkedTracker {
        val cached = _lastParkedTracker
        if (cached != null) return cached
        return synchronized(this) {
            _lastParkedTracker ?: LastParkedTracker(context.applicationContext)
                .also { _lastParkedTracker = it }
        }
    }

    /** Process-wide [RideStore] handle (Room DAO wrapper). */
    fun rideStore(context: Context): RideStore {
        val cached = _rideStore
        if (cached != null) return cached
        return synchronized(this) {
            _rideStore ?: RideStore(GixxerDatabase.get(context.applicationContext).rideDao())
                .also { _rideStore = it }
        }
    }

    /** Process-wide [RideMetaStore] handle (DataStore — lives outside Room). */
    fun rideMetaStore(context: Context): RideMetaStore {
        val cached = _rideMetaStore
        if (cached != null) return cached
        return synchronized(this) {
            _rideMetaStore ?: RideMetaStore(context.applicationContext)
                .also { _rideMetaStore = it }
        }
    }
}
