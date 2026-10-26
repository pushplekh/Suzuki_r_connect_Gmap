package dev.mrwick.redline.protocol

import androidx.compose.runtime.Immutable

/**
 * a534 — phone -> bike, missed-call notification.
 *
 * Two source paths:
 *   - CallReceiverBroadcast.e(): cellular missed call, byte 24 = 'N'
 *   - NotificationService.q() (7.6): WhatsApp missed call, byte 24 = 'W'
 *
 * Layout (after the source's "?4" + "Y1<name>" + zeros template + overrides):
 *   byte 2       0xFF (overridden)
 *   byte 3       missed-count int
 *   bytes 4-23   caller name (20 ASCII bytes, NUL-padded)
 *   byte 24      'N' (cellular) or 'W' (WhatsApp)
 *   bytes 25-27  0xFF
 */
@Immutable
data class MissedCallFrame(
    val name: String,
    val missedCount: Int = 1,
    val isWhatsapp: Boolean = false,
    override val raw: ByteArray? = null,
) : Frame {

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.MISSED_CALL.code
        buf[2] = 0xFF.toByte()
        buf[3] = (missedCount and 0xFF).toByte()
        // The OEM template's Y1 occupies bytes 2-3, overwritten by FF/count.
        val encoded = name.encodeAscii()
        val copyLen = minOf(encoded.size, 20)
        System.arraycopy(encoded, 0, buf, 4, copyLen)
        buf[24] = if (isWhatsapp) 0x57.toByte() else 0x4E.toByte()
        for (i in 25..27) buf[i] = 0xFF.toByte()
        return finalize(buf)
    }

    companion object {
        /** Decode an a534 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): MissedCallFrame {
            require(isWellFormed(frame)) { "a534 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.MISSED_CALL.code) {
                "not an a534 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }
            return MissedCallFrame(
                name = asciiSliceStripTrailing(frame, 4, 24),
                missedCount = frame[3].toInt() and 0xFF,
                isWhatsapp = (frame[24].toInt() and 0xFF) == 0x57,
                raw = frame.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MissedCallFrame) return false
        return name == other.name &&
            missedCount == other.missedCount &&
            isWhatsapp == other.isWhatsapp &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + missedCount
        result = 31 * result + isWhatsapp.hashCode()
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
