package dev.mrwick.redline.ble

import android.util.Log
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Queue of pending BLE writes with priority + drop policy.
 * Priorities (highest first):
 *   - URGENT:    a532/a534/a535 + a536 (event-driven; must reach the bike fast)
 *   - HEARTBEAT: a533 (1 Hz; missing one is fine, but they should land before nav)
 *   - NAV:       a531 (3-5 Hz; dropping identical-content frames is fine)
 *
 * Owner: BikeBridgeService. Consumer: a single drain coroutine that calls BleClient.write
 * in order. Drains URGENT > HEARTBEAT > NAV.
 */
class FrameWriter {

    enum class Priority { URGENT, HEARTBEAT, NAV }

    data class Entry(
        val priority: Priority,
        val frame: ByteArray,
        val note: String? = null,
        val repeat: Boolean = false,
        val completion: CompletableDeferred<Boolean>? = null,
        val navTraceId: String? = null,
        val navSourceElapsedMs: Long? = null,
        val queuedElapsedMs: Long = System.nanoTime() / 1_000_000,
        val callAlertRevision: Long? = null,
    )

    private val urgent = Channel<Entry>(capacity = 32)
    private val heartbeat = Channel<Entry>(capacity = 4, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val nav = Channel<Entry>(
        capacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            AppLog.w("NavTrace", "DROP trace=${it.navTraceId ?: "none"} note=${it.note} reason=queue-overflow-or-cancellation")
        },
    )

    private val _lastNav = MutableStateFlow<ByteArray?>(null)
    val lastNav: StateFlow<ByteArray?> = _lastNav.asStateFlow()

    fun enqueue(entry: Entry): Boolean {
        // Drop identical-content NAV frames to avoid spamming the bike with no-ops.
        if (entry.priority == Priority.NAV) {
            val last = _lastNav.value
            if (!entry.repeat && last != null && last.contentEquals(entry.frame)) {
                entry.completion?.complete(false)
                return false
            }
            _lastNav.value = entry.frame.copyOf()
        }
        val target = when (entry.priority) {
            Priority.URGENT -> urgent
            Priority.HEARTBEAT -> heartbeat
            Priority.NAV -> nav
        }
        val accepted = target.trySend(entry).isSuccess
        if (!accepted) {
            AppLog.w("FrameWriter", "Queue rejected ${entry.note} priority=${entry.priority}")
            entry.completion?.complete(false)
        }
        return accepted
    }

    /**
     * Single next entry, preferring higher priorities. Suspends if all queues empty.
     */
    suspend fun take(): Entry {
        // Eager non-blocking poll on urgent first
        val u = urgent.tryReceive(); if (u.isSuccess) return u.getOrThrow()
        val h = heartbeat.tryReceive(); if (h.isSuccess) return h.getOrThrow()
        val n = nav.tryReceive(); if (n.isSuccess) return n.getOrThrow()
        // Block waiting for whichever fires first
        return kotlinx.coroutines.selects.select {
            urgent.onReceive { it }
            heartbeat.onReceive { it }
            nav.onReceive { it }
        }
    }
}
