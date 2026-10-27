package dev.mrwick.redline.system

import android.content.Context
import android.speech.tts.TextToSpeech
import dev.mrwick.redline.util.AppLog
import java.util.Locale

/**
 * Which spoken alert, if any, a link or telemetry change should produce.
 * Phrases match Ride Connect 7.6 (`utils.i`, `utils.h`, `onClusterDataRecev`).
 */
class VoiceAlertPolicy {
    private var ready = false
    private var lowLevelSpoken = false

    fun onReady(): String? {
        if (ready) return null
        ready = true
        lowLevelSpoken = false
        return CONNECTED
    }

    fun onDisconnected(): String? {
        if (!ready) return null
        ready = false
        return DISCONNECTED
    }

    /** a537 byte 24: petrol models alert when it is not '1'..'6'; e-ACCESS when (byte - 64) < 26. */
    fun onTelemetryByte24(byte24: Int, isEAccess: Boolean): String? {
        if (!ready || lowLevelSpoken) return null
        val phrase = if (isEAccess) {
            LOW_BATTERY.takeIf { byte24 - 64 < 26 }
        } else {
            LOW_FUEL.takeIf { byte24 !in 0x31..0x36 }
        }
        if (phrase != null) lowLevelSpoken = true
        return phrase
    }

    companion object {
        const val CONNECTED = "Connected"
        const val DISCONNECTED = "Disconnected"
        const val LOW_FUEL = "Low Fuel Level"
        const val LOW_BATTERY = "Low Battery Level"
    }
}

/** Android TextToSpeech wrapper; speaks with Locale.US like Ride Connect. */
class VoiceAlerts(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    fun speak(phrase: String) {
        AppLog.i(TAG, "speak \"$phrase\"")
        if (ready) {
            tts?.speak(phrase, TextToSpeech.QUEUE_ADD, null, phrase)
            return
        }
        pending.addLast(phrase)
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts ?: return@TextToSpeech
                val lang = if (status == TextToSpeech.SUCCESS) engine.setLanguage(Locale.US) else TextToSpeech.LANG_NOT_SUPPORTED
                if (status != TextToSpeech.SUCCESS ||
                    lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    AppLog.w(TAG, "TextToSpeech unavailable status=$status lang=$lang")
                    pending.clear()
                    return@TextToSpeech
                }
                ready = true
                while (pending.isNotEmpty()) {
                    val next = pending.removeFirst()
                    engine.speak(next, TextToSpeech.QUEUE_ADD, null, next)
                }
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
    }

    private companion object {
        const val TAG = "VoiceAlerts"
    }
}
