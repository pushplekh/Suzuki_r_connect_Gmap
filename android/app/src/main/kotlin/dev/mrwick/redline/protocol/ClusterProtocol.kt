package dev.mrwick.redline.protocol

/**
 * Wire variants selected by the OEM 7.6 cluster-name branch.
 * Frame encoders remain canonical (additive checksum); adapt once at the BLE boundary.
 */
enum class ClusterProtocol {
    STANDARD,
    LEGACY_ACCESS_BURGMAN;

    fun toWire(frame: ByteArray): ByteArray {
        require(isWellFormed(frame)) { "Invalid canonical Suzuki frame" }
        val wire = frame.copyOf()
        if (this == LEGACY_ACCESS_BURGMAN) {
            if (wire[1] == FrameType.HEARTBEAT.code) {
                for (i in 16..27) wire[i] = 0xFF.toByte()
                if (wire[7] == 0.toByte()) wire[7] = '0'.code.toByte()
            }
            wire[28] = (checksum(wire) xor 0xFF).toByte()
        }
        return wire
    }

    /** Validate the selected wire checksum before passing canonical bytes to the codec. */
    fun decode(wire: ByteArray): Frame {
        if (this == STANDARD) return decodeFrame(wire)
        require(wire.size == FRAME_LEN && wire[0] == HEADER && wire[29] == TERMINATOR) {
            "Invalid legacy Suzuki frame envelope"
        }
        require((wire[28].toInt() and 0xFF) == (checksum(wire) xor 0xFF)) {
            "Invalid legacy Suzuki frame checksum"
        }
        val decoded = decodeFrame(wire.copyOf().also { it[28] = checksum(it).toByte() })
        val raw = wire.copyOf()
        return when (decoded) {
            is NavFrame -> decoded.copy(raw = raw)
            is CallFrame -> decoded.copy(raw = raw)
            is HeartbeatFrame -> decoded.copy(raw = raw)
            is MissedCallFrame -> decoded.copy(raw = raw)
            is SmsFrame -> decoded.copy(raw = raw)
            is IdentityFrame -> decoded.copy(raw = raw)
            is TelemetryFrame -> decoded.copy(raw = raw)
        }
    }

    companion object {
        fun fromClusterName(name: String?): ClusterProtocol? = when {
            name == null || name.length < 2 -> null
            // HomeScreenActivity.onCreate restores K.g for every second-character A,
            // including SAS21; the pairing callback's AS01/02/11/12 list is narrower.
            name[1] == 'A' -> LEGACY_ACCESS_BURGMAN
            name[1] == 'B' -> STANDARD
            else -> null
        }
    }
}
