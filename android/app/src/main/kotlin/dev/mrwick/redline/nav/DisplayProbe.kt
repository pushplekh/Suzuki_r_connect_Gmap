package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.IdentityFrame
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.finalize

internal enum class DisplayProbe(val label: String) {
    IDENTITY("Test reference banner: TEXT TEST (R)"),
    IDENTITY_SAVED("1. Test saved rider name (R / reconnect)"),
    IDENTITY_FRESH("2. Test saved rider name (F / fresh)"),
    ETA_AM("Show arrival time: 09:45 AM"),
    ETA_PM("Show arrival time: 06:30 PM"),
    ETA("Test letters in time field"),
    NEXT("Test letters in next-distance field"),
    TOTAL("Test letters in total-distance field"),
    ETA_LABEL_BASELINE("1. Show baseline (12:34 PM)"),
    ETA_LABEL_CANDIDATE("2. Test ABC in unassigned bytes 15-17");

    val investigatesEtaLabel: Boolean
        get() = this == ETA_LABEL_BASELINE || this == ETA_LABEL_CANDIDATE

    val testsIdentity: Boolean
        get() = this == IDENTITY || this == IDENTITY_SAVED || this == IDENTITY_FRESH

    val testsArrivalTime: Boolean
        get() = this == ETA_AM || this == ETA_PM

    fun frame(riderName: String = "TEXT TEST"): ByteArray = when {
        testsIdentity -> IdentityFrame(
            name = if (this == IDENTITY) "TEXT TEST" else riderName,
            isFresh = this == IDENTITY_FRESH,
        ).encode()
        testsArrivalTime -> NavFrame(
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = "0123",
            distNextUnit = "M",
            eta = if (this == ETA_AM) "0945AM" else "0630PM",
            distTotal = "0045",
            distTotalUnit = "K",
            status = "1",
            continueFlag = "1",
        ).encode()
        investigatesEtaLabel -> {
            val bytes = NavFrame(
                maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
                distNext = "0123",
                distNextUnit = "M",
                eta = "1234PM",
                distTotal = "0045",
                distTotalUnit = "K",
                status = "1",
                continueFlag = "1",
            ).encode()
            if (this == ETA_LABEL_CANDIDATE) {
                // Hypothesis only: these padding bytes might affect the separate ETA label.
                "ABC".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 15)
            }
            finalize(bytes)
        }
        else -> NavFrame(
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = if (this == NEXT) "ABCD" else "    ",
            distNextUnit = " ",
            eta = if (this == ETA) "ABCDEF" else "      ",
            distTotal = if (this == TOTAL) "WXYZ" else "    ",
            distTotalUnit = " ",
            status = "1",
            continueFlag = "1",
        ).encode()
    }
}

internal class DisplayProbeWindow {
    private var deadline = 0L
    private var running = false

    @Synchronized fun begin(now: Long, navigationActive: Boolean): Boolean {
        if (navigationActive || running) return false
        running = true
        deadline = now + 8_000
        return true
    }

    @Synchronized fun suppressAutomaticDisplay(now: Long, navigationActive: Boolean): Boolean {
        if (navigationActive) deadline = 0
        return now < deadline
    }

    @Synchronized fun finish() {
        deadline = 0
        running = false
    }
}
