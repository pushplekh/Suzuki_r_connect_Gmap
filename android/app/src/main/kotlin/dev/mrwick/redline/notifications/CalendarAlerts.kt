package dev.mrwick.redline.notifications

import android.app.Notification
import dev.mrwick.redline.protocol.SmsFrame

/**
 * Calendar / meeting reminder on the cluster, from Ride Connect 7.6
 * `NotificationService.p` / `.k` / `.i`: an a535 frame with type byte 'X' (0x58),
 * no sender, count 0, not silenced, sent three times 200 ms apart after a 1 s delay.
 */
internal object CalendarAlerts {
    const val FIRST_DELAY_MS = 1_000L
    const val SEND_GAP_MS = 200L
    const val SEND_COUNT = 3
    const val PACKAGE_REPEAT_MS = 5_000L

    val MEETING_PACKAGES = setOf(
        "com.google.android.apps.tachyon",
        "com.google.android.apps.meetings",
        "com.cisco.webex.meetings",
        "com.microsoft.teams",
        "us.zoom.videomeetings",
        "com.zoho.android.calendar",
    )

    private val MEETING_CATEGORIES = setOf(
        Notification.CATEGORY_EVENT,
        Notification.CATEGORY_REMINDER,
        Notification.CATEGORY_CALL,
    )

    /**
     * Calendar apps always qualify. Meeting apps qualify only for event, reminder or
     * call notifications, so ordinary chat messages keep their message alert.
     */
    fun isCalendarAlert(packageName: String, category: String?): Boolean =
        "calendar" in packageName ||
            (packageName in MEETING_PACKAGES && category in MEETING_CATEGORIES)

    fun frame(): ByteArray =
        SmsFrame(sender = "", messageCount = 0, silenced = false, typeByte = 0x58).encode()

    /** OEM dedup: same package within 5 s, or a notification already alerted. */
    class Gate {
        private var lastPackage: String? = null
        private var lastAtMs = 0L
        private val seenKeys = LinkedHashSet<String>()

        @Synchronized
        fun accept(packageName: String, key: String, nowMs: Long): Boolean {
            if (packageName == lastPackage && nowMs - lastAtMs < PACKAGE_REPEAT_MS) return false
            if (!seenKeys.add(key)) return false
            if (seenKeys.size > 200) seenKeys.remove(seenKeys.first())
            lastPackage = packageName
            lastAtMs = nowMs
            return true
        }
    }
}
