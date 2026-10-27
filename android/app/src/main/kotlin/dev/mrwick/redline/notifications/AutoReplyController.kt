package dev.mrwick.redline.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.mrwick.redline.util.AppLog

/** Decides when a SIM call gets an auto-reply SMS. */
internal class AutoReplyPolicy {

    data class Config(val enabled: Boolean, val rejectCall: Boolean, val bikeConnected: Boolean)

    sealed interface Action {
        data object RejectCall : Action
        data class SendSms(val number: String) : Action
    }

    private var lastState = TelephonyCallState.IDLE
    private var ringing = false
    private var answered = false
    private var handled = false
    private var number: String? = null

    fun on(state: TelephonyCallState, incomingNumber: String?, config: Config): List<Action> {
        val previous = lastState
        lastState = state
        when (state) {
            TelephonyCallState.RINGING -> {
                if (previous == TelephonyCallState.IDLE && !ringing) {
                    ringing = true
                    answered = false
                    handled = false
                    number = null
                }
                if (incomingNumber != null) number = incomingNumber
                val target = number
                if (config.enabled && config.rejectCall && config.bikeConnected && !handled && target != null) {
                    handled = true
                    return listOf(Action.RejectCall, Action.SendSms(target))
                }
            }
            TelephonyCallState.OFFHOOK -> if (ringing) answered = true
            TelephonyCallState.IDLE -> {
                if (!ringing) return emptyList()
                val target = number ?: incomingNumber
                val missed = !answered && !handled
                ringing = false
                number = null
                if (missed && config.enabled && config.bikeConnected && target != null) {
                    handled = true
                    return listOf(Action.SendSms(target))
                }
            }
        }
        return emptyList()
    }
}

/** Sends the rider's auto-reply SMS for SIM calls while the bike is connected. */
internal class AutoReplyController(
    private val context: Context,
    private val configProvider: () -> AutoReplyPolicy.Config,
    private val messageProvider: () -> String,
) {
    private val policy = AutoReplyPolicy()

    @Synchronized
    fun onTelephonyState(state: TelephonyCallState, number: String?) {
        for (action in policy.on(state, number, configProvider())) {
            when (action) {
                AutoReplyPolicy.Action.RejectCall -> rejectCall()
                is AutoReplyPolicy.Action.SendSms -> sendSms(action.number, messageProvider())
            }
        }
    }

    private fun rejectCall() {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            AppLog.w(TAG, "REJECT skipped: ANSWER_PHONE_CALLS not granted")
            return
        }
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            @Suppress("DEPRECATION")
            val ended = telecom?.endCall() ?: false
            AppLog.i(TAG, "REJECT endCall returned=$ended")
        } catch (t: Throwable) {
            AppLog.w(TAG, "REJECT failed", t)
        }
    }

    private fun sendSms(number: String, message: String) {
        if (!granted(Manifest.permission.SEND_SMS)) {
            AppLog.w(TAG, "SMS skipped: SEND_SMS not granted")
            return
        }
        try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            } ?: return
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            AppLog.i(TAG, "SMS sent chars=${message.length} parts=${parts.size}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "SMS failed", t)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AutoReply"

        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
        )
        val REJECT_PERMISSIONS = arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)
    }
}
