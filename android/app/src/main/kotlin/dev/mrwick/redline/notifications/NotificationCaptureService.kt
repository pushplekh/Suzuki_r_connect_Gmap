package dev.mrwick.redline.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.mrwick.redline.util.AppLog

/**
 * Listens to every posted/dismissed Android notification.
 * Dispatch + filtering lives in [NotificationDispatcher] (singleton, shared with the foreground service).
 *
 * Lifecycle note: this service is started by the system when the user grants
 * notification-listener access. It runs in our app process, so singletons work.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i("NotificationListener", "connected; replaying active notifications")
        NotificationDispatcher.attach(applicationContext)
        // Replay any already-posted notifications through the dispatcher so we
        // don't miss the active Maps nav (which was posted before our listener
        // bound). Without this, the rider has to wait for the next Maps update
        // before the cluster sees a single a531 — confusing UX on first connect.
        try {
            val active = activeNotifications ?: run {
                AppLog.w("NotificationListener", "Active notification snapshot unavailable; call state cannot be restored")
                return
            }
            CallAlerts.tracker.retainKeys(active.map { it.key }.toSet())
            active.forEach { sbn ->
                NotificationDispatcher.onPosted(applicationContext, sbn)
            }
        } catch (e: SecurityException) {
            AppLog.w("NotificationListener", "Cannot replay active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        CallAlerts.tracker.retainKeys(emptySet())
        AppLog.w("NotificationListener", "disconnected; Maps callbacks unavailable until reconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationDispatcher.onPosted(applicationContext, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationDispatcher.onRemoved(sbn)
    }
}
