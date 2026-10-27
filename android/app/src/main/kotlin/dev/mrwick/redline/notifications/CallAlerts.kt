package dev.mrwick.redline.notifications

import android.app.Notification
import android.app.Person
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.mrwick.redline.protocol.CallFrame
import dev.mrwick.redline.protocol.Frame
import dev.mrwick.redline.protocol.MissedCallFrame
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay

internal enum class CallPhase { RINGING, ONGOING, MISSED }

internal enum class TelephonyCallState { RINGING, OFFHOOK, IDLE }

internal data class CallAlert(
    val key: String,
    val caller: String,
    val phase: CallPhase,
    val whatsapp: Boolean,
    val count: Int = 1,
    val group: String? = null,
    val summary: Boolean = false,
)

internal data class CallAlertSnapshot(
    val revision: Long = 0,
    val frames: List<Frame> = emptyList(),
    // OEM a533 byte 15 uses Y for cleared, not for pending.
    val cleared: Boolean = false,
    val inCall: Boolean = false,
    val ringingSinceMs: Long? = null,
) {
    val ringingFrames: List<Frame>
        get() = frames.filter { it is CallFrame && it.state == '1'.code }

    fun holdsNavigation(nowMs: Long): Boolean =
        ringingSinceMs != null && nowMs - ringingSinceMs < MAX_RING_REFRESH_MS

    suspend fun sendBurst(send: suspend (Frame) -> Unit) {
        // The OEM delays before each of its three sends.
        repeat(3) {
            delay(200)
            frames.forEach { send(it) }
        }
    }

    // OEM 7.6 sends one burst per call-state change. diagnostics9 showed the cluster
    // call display ending after about 1-3 s while still ringing, so Redline resends.
    // The 1 s cadence is a design choice to verify on the cluster.
    suspend fun sendAndRefreshWhileRinging(nowMs: () -> Long, send: suspend (Frame) -> Unit) {
        sendBurst(send)
        val refresh = ringingFrames
        if (refresh.isEmpty()) return
        while (true) {
            delay(RING_REFRESH_MS)
            if (!holdsNavigation(nowMs())) return
            refresh.forEach { send(it) }
        }
    }

    companion object {
        const val RING_REFRESH_MS = 1_000L
        const val MAX_RING_REFRESH_MS = 90_000L
    }
}

internal class CallAlertTracker(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val alerts = linkedMapOf<String, CallAlert>()
    private val missedSources = mutableSetOf<Boolean>()
    private val _state = MutableStateFlow(CallAlertSnapshot())
    val state = _state.asStateFlow()

    @Synchronized
    internal fun clearForTest() {
        alerts.clear()
        missedSources.clear()
        _state.value = CallAlertSnapshot()
    }

    @Synchronized
    fun posted(alert: CallAlert) {
        if (alerts[alert.key] == alert) return
        if (alert.phase == CallPhase.ONGOING &&
            alerts[alert.key]?.phase !in setOf(CallPhase.RINGING, CallPhase.ONGOING)
        ) {
            AppLog.i("CallAlerts", "Ongoing notification has no observed incoming history; not inferring call direction")
            return
        }
        if (alert.phase == CallPhase.MISSED) missedSources.add(alert.whatsapp)
        val old = alerts.put(alert.key, alert)
        publish(if (old?.phase == CallPhase.MISSED) setOf(old.whatsapp) else emptySet())
    }

    // OEM 7.6 CallReceiverBroadcast drives call frames from PHONE_STATE broadcasts.
    // diagnostics10 showed a real SIM call produced no recognized dialer notification.
    @Synchronized
    fun telephony(state: TelephonyCallState, caller: String?) {
        val current = alerts[TELEPHONY_KEY]
        when (state) {
            TelephonyCallState.RINGING -> posted(CallAlert(
                key = TELEPHONY_KEY,
                caller = caller ?: current?.caller ?: UNKNOWN_CALLER,
                phase = CallPhase.RINGING,
                whatsapp = false,
            ))
            // OFFHOOK without prior RINGING is an outgoing call; it is not shown.
            TelephonyCallState.OFFHOOK -> if (current != null) posted(current.copy(phase = CallPhase.ONGOING))
            TelephonyCallState.IDLE -> {
                if (current == null) return
                alerts.remove(TELEPHONY_KEY)
                if (current.phase == CallPhase.RINGING) {
                    missedSources.add(false)
                    alerts[TELEPHONY_MISSED_KEY] = CallAlert(
                        key = TELEPHONY_MISSED_KEY,
                        caller = current.caller,
                        phase = CallPhase.MISSED,
                        whatsapp = false,
                        count = ((alerts[TELEPHONY_MISSED_KEY]?.count ?: 0) + 1).coerceAtMost(255),
                    )
                }
                publish(emptySet())
            }
        }
    }

    @Synchronized
    fun telephonyStopped() {
        if (alerts.remove(TELEPHONY_KEY) != null) publish(emptySet())
    }

    @Synchronized
    fun clearTelephonyMissed() {
        if (alerts.remove(TELEPHONY_MISSED_KEY) != null) publish(setOf(false))
    }

    @Synchronized
    fun removed(key: String) {
        val old = alerts.remove(key) ?: return
        if (old.phase == CallPhase.MISSED && !old.whatsapp) alerts.remove(TELEPHONY_MISSED_KEY)
        publish(if (old.phase == CallPhase.MISSED) setOf(old.whatsapp) else emptySet())
    }

    @Synchronized
    fun retainKeys(keys: Set<String>) {
        val removed = alerts.values.filter { it.key !in keys && !it.key.startsWith(TELEPHONY_PREFIX) }
        if (removed.isEmpty()) return
        removed.forEach { alerts.remove(it.key) }
        publish(removed.filter { it.phase == CallPhase.MISSED }.map { it.whatsapp }.toSet())
    }

    private fun publish(removedMissedSources: Set<Boolean>) {
        val telephonyLive = TELEPHONY_KEY in alerts
        val dialerMissed = alerts.values.any {
            it.phase == CallPhase.MISSED && !it.whatsapp && !it.key.startsWith(TELEPHONY_PREFIX)
        }
        val effective = alerts.values.filter { alert ->
            val fromDialer = !alert.whatsapp && !alert.key.startsWith(TELEPHONY_PREFIX)
            when {
                alert.key == TELEPHONY_MISSED_KEY -> !dialerMissed
                fromDialer && alert.phase != CallPhase.MISSED -> !telephonyLive
                else -> true
            }
        }.filter { alert ->
            !alert.summary || alerts.values.none { !it.summary && it.group == alert.group }
        }
        val missed = effective.filter { it.phase == CallPhase.MISSED }
        val frames = buildList {
            for (source in missedSources) {
                val remaining = missed.filter { it.whatsapp == source }
                add(MissedCallFrame(
                    name = remaining.lastOrNull()?.caller.orEmpty(),
                    missedCount = remaining.sumOf { it.count.coerceIn(1, 255) }.coerceAtMost(255),
                    isWhatsapp = source,
                ))
            }
            effective.filter { it.phase != CallPhase.MISSED }.forEach {
                add(CallFrame(it.caller, it.whatsapp, if (it.phase == CallPhase.RINGING) '1'.code else '2'.code))
            }
        }
        val ringing = effective.any { it.phase == CallPhase.RINGING }
        val previous = _state.value
        _state.value = CallAlertSnapshot(
            revision = previous.revision + 1,
            frames = frames,
            cleared = if (missed.isNotEmpty()) false
                else if (removedMissedSources.isNotEmpty()) true else previous.cleared,
            inCall = alerts.values.any { it.phase != CallPhase.MISSED },
            ringingSinceMs = if (ringing) previous.ringingSinceMs ?: nowMs() else null,
        )
    }

    companion object {
        const val TELEPHONY_PREFIX = "telephony:"
        const val TELEPHONY_KEY = "${TELEPHONY_PREFIX}call"
        const val TELEPHONY_MISSED_KEY = "${TELEPHONY_PREFIX}missed"
        const val UNKNOWN_CALLER = "UNKNOWN"
    }
}

internal object CallAlerts {
    val tracker = CallAlertTracker()

    fun posted(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val labels = listOf(title.trim(), text.trim())
        val callType = if (Build.VERSION.SDK_INT >= 31) extras.getInt(Notification.EXTRA_CALL_TYPE) else 0
        val phase = when {
            notification.category == Notification.CATEGORY_MISSED_CALL -> CallPhase.MISSED
            Build.VERSION.SDK_INT >= 31 && callType == Notification.CallStyle.CALL_TYPE_INCOMING -> CallPhase.RINGING
            Build.VERSION.SDK_INT >= 31 && callType == Notification.CallStyle.CALL_TYPE_ONGOING -> CallPhase.ONGOING
            labels.any { MISSED.matches(it) } -> CallPhase.MISSED
            labels.any { INCOMING.matches(it) } -> CallPhase.RINGING
            notification.category == Notification.CATEGORY_CALL &&
                (labels.any { ONGOING.matches(it) } || extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) ->
                CallPhase.ONGOING
            else -> {
                tracker.removed(sbn.key)
                if (notification.category == Notification.CATEGORY_CALL) {
                    AppLog.w("CallAlerts", "Unsupported call notification package=${sbn.packageName} callType=$callType")
                    return true
                }
                return false
            }
        }
        @Suppress("DEPRECATION")
        val person = if (Build.VERSION.SDK_INT >= 31) extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON) else null
        val caller = callerLabel(person, title, text)
        if (caller == null) {
            AppLog.w("CallAlerts", "Caller unavailable package=${sbn.packageName} phase=$phase; sending UNKNOWN")
        }
        tracker.posted(CallAlert(
            key = sbn.key,
            caller = caller ?: "UNKNOWN",
            phase = phase,
            whatsapp = sbn.packageName in WHATSAPP_PACKAGES,
            count = notification.number.coerceIn(1, 255),
            group = sbn.groupKey,
            summary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        ))
        AppLog.i("CallAlerts", "INPUT package=${sbn.packageName} phase=$phase callerAvailable=${caller != null} revision=${tracker.state.value.revision}")
        return true
    }

    private fun callerLabel(person: Person?, title: String, text: String): String? {
        val candidates = listOf(
            person?.name?.toString(),
            person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:"),
            title,
            text,
        )
        return candidates.filterNotNull().firstNotNullOfOrNull { value ->
            val cleaned = value.replace(MISSED, "").replace(INCOMING, "").replace(ONGOING, "")
                .trim().trim(':', '-', ' ')
                .filter { it in ' '..'~' }.trim()
            cleaned.takeIf { it.isNotEmpty() && !GENERIC.matches(it) }?.take(20)
        }
    }

    fun telephonyCaller(contactName: String?, number: String?): String? =
        listOf(contactName, number).firstNotNullOfOrNull { value ->
            value?.filter { it in ' '..'~' }?.trim()?.takeIf { it.isNotEmpty() }?.take(20)
        }

    fun looksMissed(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if (notification.category == Notification.CATEGORY_MISSED_CALL) return true
        val extras = notification.extras ?: return false
        return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT).any { key ->
            extras.getCharSequence(key)?.let { MISSED.containsMatchIn(it) } == true
        }
    }

    fun describe(sbn: StatusBarNotification): String {
        val notification = sbn.notification
        val extras = notification?.extras
        val callType = if (Build.VERSION.SDK_INT >= 31) extras?.getInt(Notification.EXTRA_CALL_TYPE) ?: 0 else 0
        return "package=${sbn.packageName} category=${notification?.category} callType=$callType " +
            "ongoing=${sbn.isOngoing} chronometer=${extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)} " +
            "titleChars=${extras?.getCharSequence(Notification.EXTRA_TITLE)?.length ?: 0} " +
            "textChars=${extras?.getCharSequence(Notification.EXTRA_TEXT)?.length ?: 0}"
    }

    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val MISSED = Regex("""\b(?:\d+\s+)?missed\s+(?:(?:voice|video)\s+)?calls?\b""", RegexOption.IGNORE_CASE)
    private val INCOMING = Regex("""\b(?:incoming\s+(?:(?:voice|video)\s+)?call|ringing)\b""", RegexOption.IGNORE_CASE)
    private val ONGOING = Regex("""\b(?:ongoing\s+(?:(?:voice|video)\s+)?call|call\s+in\s+progress)\b""", RegexOption.IGNORE_CASE)
    private val GENERIC = Regex("""(?:phone|calls?|whatsapp|unknown|private number|calling|ongoing)""", RegexOption.IGNORE_CASE)
}
