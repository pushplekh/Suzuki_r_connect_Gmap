package dev.mrwick.redline.notifications

import android.content.Context
import android.telecom.TelecomManager
import android.service.notification.StatusBarNotification
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.ble.FrameWriter
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.nav.GoogleMapsParser
import dev.mrwick.redline.nav.MapsNavSource
import dev.mrwick.redline.protocol.SmsFrame
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton router for every NotificationListener event. Filters by package and
 * routes to: Maps nav parser, phone call/SMS encoders, or allowlisted-app SMS frame.
 *
 * Keeps a small allowlist cache so we don't have to round-trip through DataStore
 * on every notification.
 */
object NotificationDispatcher {

    private val tag = "NotifDispatcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val allowlist = MutableStateFlow<Set<String>>(Settings.DEFAULT_ALLOWLIST)
    private val calendarAlerts = MutableStateFlow(false)
    private val calendarGate = CalendarAlerts.Gate()
    @Volatile private var mapsNavNotificationKey: String? = null

    // PERF: NotificationListenerService can reconnect (system restarts the
    // listener after notification-access toggles, app updates, etc.). Each
    // reconnect previously stacked another forever-running collector on this
    // singleton's scope. Gate with an AtomicBoolean so we wire it exactly once
    // per process (audit findings 2.2 + 6.1).
    private val attached = AtomicBoolean(false)

    fun attach(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        // PERF: explicit applicationContext + AppGraph singleton — guarantees
        // we never accidentally retain a non-application Context if a caller
        // ever passes one in, and shares the single Settings handle.
        val appCtx = context.applicationContext
        val s = AppGraph.settings(appCtx)
        scope.launch {
            s.mirrorAllowlist.collect { set -> allowlist.value = set }
        }
        scope.launch {
            s.calendarAlerts.collect { calendarAlerts.value = it }
        }
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        try {
            val isPhone = sbn.packageName in PHONE_PACKAGES ||
                sbn.packageName == context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            if (isPhone || sbn.packageName in CallAlerts.WHATSAPP_PACKAGES) {
                if (CallAlerts.posted(sbn)) return
                if (isPhone) {
                    logUnrecognizedCall(sbn, "Unrecognized dialer notification")
                    return
                }
            } else if (sbn.notification?.category in CALL_CATEGORIES) {
                logUnrecognizedCall(sbn, "Call notification from unlisted package")
            }
            if (calendarAlerts.value && CalendarAlerts.isCalendarAlert(sbn.packageName, sbn.notification?.category)) {
                handleCalendar(sbn)
                return
            }
            when (sbn.packageName) {
                GoogleMapsParser.PKG_GOOGLE_MAPS -> handleMaps(context, sbn)
                in SMS_PACKAGES -> handleSms(sbn, isSms = true)
                else -> {
                    if (sbn.packageName in allowlist.value) handleSms(sbn, isSms = false)
                }
            }
        } catch (t: Throwable) {
            AppLog.w(tag, "onPosted threw for ${sbn.packageName}", t)
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        CallAlerts.tracker.removed(sbn.key)
        if (sbn.packageName !in CallAlerts.WHATSAPP_PACKAGES && CallAlerts.looksMissed(sbn)) {
            CallAlerts.tracker.clearTelephonyMissed()
        }
        if (sbn.packageName == GoogleMapsParser.PKG_GOOGLE_MAPS) {
            dev.mrwick.redline.util.AppLog.i(
                "NavTrace", "REMOVED keyHash=${sbn.key.hashCode()} matchesActive=${mapsNavNotificationKey == sbn.key}",
            )
        }
        if (sbn.packageName == GoogleMapsParser.PKG_GOOGLE_MAPS &&
            mapsNavNotificationKey == sbn.key
        ) {
            mapsNavNotificationKey = null
            MapsNavSource.clear()
        }
    }

    private fun handleMaps(context: Context, sbn: StatusBarNotification) {
        val parsed = GoogleMapsParser.parse(context, sbn) ?: return
        mapsNavNotificationKey = sbn.key
        MapsNavSource.update(parsed)
    }

    private fun handleCalendar(sbn: StatusBarNotification) {
        if (CallAlerts.tracker.state.value.inCall) return
        if (!calendarGate.accept(sbn.packageName, sbn.key, System.currentTimeMillis())) return
        val frame = CalendarAlerts.frame()
        AppLog.i(tag, "Calendar alert package=${sbn.packageName} category=${sbn.notification?.category}")
        scope.launch {
            delay(CalendarAlerts.FIRST_DELAY_MS)
            repeat(CalendarAlerts.SEND_COUNT) {
                delay(CalendarAlerts.SEND_GAP_MS)
                AppGraph.frameWriter?.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, frame, "calendar/${sbn.packageName}"))
            }
        }
    }

    private fun handleSms(sbn: StatusBarNotification, isSms: Boolean) {
        if (CallAlerts.tracker.state.value.inCall) {
            dev.mrwick.redline.util.AppLog.i("CallAlerts", "Suppressing message banner during active call")
            return
        }
        val extras = sbn.notification?.extras ?: return
        val sender = extras.getString(android.app.Notification.EXTRA_TITLE)?.take(20) ?: return
        val body = extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty()
        // Dedup on (package + sender + first 32 chars of body). Stops Spotify/WhatsApp
        // from re-firing every 1s when they update progress / read-receipts.
        if (!Dedup.firstTime("${sbn.packageName}:$sender:${body.take(32)}")) return
        // ASSUMED: count = 1 per notification; future improvement is to read EXTRA_NUMBER if present
        // ASSUMED: Android exposes the badge count via the magic key "android.number" on N+ but
        // doesn't expose a Notification.EXTRA_NUMBER constant — using the string literal.
        val count = extras.getInt("android.number", 1).coerceIn(1, 99)
        val frame = SmsFrame(
            sender = sender,
            messageCount = count,
            silenced = true,
            typeByte = if (isSms) 0x4E else 0x57, // 'N' for SMS, 'W' for everything-else (WhatsApp-style)
        ).encode()
        scope.launch { AppGraph.frameWriter?.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, frame, "sms/${sbn.packageName}")) }
    }

    private val loggedUnrecognized = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun logUnrecognizedCall(sbn: StatusBarNotification, reason: String) {
        val description = CallAlerts.describe(sbn)
        if (loggedUnrecognized.size > 200) loggedUnrecognized.clear()
        if (loggedUnrecognized.add("${sbn.key.hashCode()}:$description")) {
            AppLog.i("CallAlerts", "$reason ${description}")
        }
    }

    private val CALL_CATEGORIES = setOf(
        android.app.Notification.CATEGORY_CALL,
        android.app.Notification.CATEGORY_MISSED_CALL,
    )
    private val PHONE_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.android.server.telecom",
    )
    private val SMS_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.android.messaging",
    )
}
