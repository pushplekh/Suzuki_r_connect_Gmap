package dev.mrwick.redline.notifications

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Detects SIM calls from PHONE_STATE broadcasts, as the OEM CallReceiverBroadcast does.
 * READ_PHONE_STATE enables the broadcast, READ_CALL_LOG adds the number and
 * READ_CONTACTS allows the contact-name lookup.
 */
internal class TelephonyCallSource(
    private val context: Context,
    private val onState: (TelephonyCallState, String?) -> Unit = { _, _ -> },
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyCallState.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyCallState.OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyCallState.IDLE
                else -> return
            }
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
            val pending = goAsync()
            scope.launch {
                try {
                    val name = number?.let(::contactName)
                    CallAlerts.tracker.telephony(state, CallAlerts.telephonyCaller(name, number))
                    onState(state, number)
                    AppLog.i(
                        "CallAlerts",
                        "TELEPHONY state=$state numberAvailable=${number != null} contactName=${name != null} " +
                            "revision=${CallAlerts.tracker.state.value.revision}",
                    )
                } catch (t: Throwable) {
                    AppLog.w("CallAlerts", "TELEPHONY state=$state handling failed", t)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
        AppLog.i(
            "CallAlerts",
            "TELEPHONY source started phoneState=${granted(Manifest.permission.READ_PHONE_STATE)} " +
                "callLog=${granted(Manifest.permission.READ_CALL_LOG)} contacts=${granted(Manifest.permission.READ_CONTACTS)}",
        )
    }

    fun stop() {
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            registered = false
        }
        scope.cancel()
        CallAlerts.tracker.telephonyStopped()
    }

    private fun contactName(number: String): String? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: RuntimeException) {
            AppLog.w("CallAlerts", "Contact lookup failed", e)
            null
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )
    }
}
