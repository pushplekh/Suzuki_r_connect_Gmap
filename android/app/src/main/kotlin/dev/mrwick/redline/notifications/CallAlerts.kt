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
) {
    suspend fun sendBurst(send: suspend (Frame) -> Unit) {
        // The OEM delays before each of its three sends.
        repeat(3) {
            delay(200)
            frames.forEach { send(it) }
        }
    }
}

internal class CallAlertTracker {
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

    @Synchronized
    fun removed(key: String) {
        val old = alerts.remove(key) ?: return
        publish(if (old.phase == CallPhase.MISSED) setOf(old.whatsapp) else emptySet())
    }

    @Synchronized
    fun retainKeys(keys: Set<String>) {
        val removed = alerts.values.filter { it.key !in keys }
        if (removed.isEmpty()) return
        removed.forEach { alerts.remove(it.key) }
        publish(removed.filter { it.phase == CallPhase.MISSED }.map { it.whatsapp }.toSet())
    }

    private fun publish(removedMissedSources: Set<Boolean>) {
        val effective = alerts.values.filter { alert ->
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
        _state.value = CallAlertSnapshot(
            revision = _state.value.revision + 1,
            frames = frames,
            cleared = if (missed.isNotEmpty()) false
                else if (removedMissedSources.isNotEmpty()) true else _state.value.cleared,
            inCall = alerts.values.any { it.phase != CallPhase.MISSED },
        )
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

    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val MISSED = Regex("""\b(?:\d+\s+)?missed\s+(?:(?:voice|video)\s+)?calls?\b""", RegexOption.IGNORE_CASE)
    private val INCOMING = Regex("""\b(?:incoming\s+(?:(?:voice|video)\s+)?call|ringing)\b""", RegexOption.IGNORE_CASE)
    private val ONGOING = Regex("""\b(?:ongoing\s+(?:(?:voice|video)\s+)?call|call\s+in\s+progress)\b""", RegexOption.IGNORE_CASE)
    private val GENERIC = Regex("""(?:phone|calls?|whatsapp|unknown|private number|calling|ongoing)""", RegexOption.IGNORE_CASE)
}
