package dev.mrwick.redline.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterProtocolTest {
    private val legacy = ClusterProtocol.LEGACY_ACCESS_BURGMAN

    @Test fun selectsOemNameBranches() {
        for (prefix in listOf("SAS01", "SAS02", "SAS11", "SAS12", "SAS21", "SAS99")) {
            assertEquals(legacy, ClusterProtocol.fromClusterName("${prefix}123456"))
        }
        assertEquals(ClusterProtocol.STANDARD, ClusterProtocol.fromClusterName("SBM11123456"))
        assertEquals(ClusterProtocol.STANDARD, ClusterProtocol.fromClusterName("SBS51123456"))
        for (name in listOf(null, "", "S", "Access 125")) {
            assertNull(ClusterProtocol.fromClusterName(name))
        }
    }

    @Test fun sas21CapturedTelemetryUsesComplementedChecksumAndPreservesRawBytes() {
        val wire = hex("a5373030303030343034363030333239363031313336350135000000707f")
        assertEquals(0x8F, checksum(wire))
        assertEquals(0x70, wire[28].toInt() and 0xFF)
        val selected = ClusterProtocol.fromClusterName("SAS21")!!
        val telemetry = selected.decode(wire) as TelemetryFrame
        assertEquals(0, telemetry.speedKmh)
        assertEquals(4046, telemetry.odometerKm)
        assertEquals(329.6, telemetry.tripAKm, 0.001)
        assertEquals(1136.5, telemetry.tripBKm, 0.001)
        assertEquals(5, telemetry.fuelBars)
        assertEquals(1, telemetry.byte23)
        assertArrayEquals(wire, telemetry.raw)
    }

    @Test fun sas21TransmitsComplementedIdentityNavigationAndLegacyHeartbeat() {
        val selected = ClusterProtocol.fromClusterName("SAS21")!!
        val nav = hex("a53102ff303039304d30313238504dffffff303030304d3131ffffff197f")
        val wireNav = selected.toWire(nav)
        assertEquals(0xE6, wireNav[28].toInt() and 0xFF)
        assertArrayEquals(nav.copyOfRange(0, 28), wireNav.copyOfRange(0, 28))
        val identity = IdentityFrame(name = "Rider", isFresh = true).encode()
        assertEquals(checksum(identity) xor 0xFF, selected.toWire(identity)[28].toInt() and 0xFF)
        val hb = hex("a533314effffff323031333835314e4effffffffff01c601ffffffff6e7f")
        val wireHeartbeat = selected.toWire(hb)
        assertArrayEquals(ByteArray(12) { 0xFF.toByte() }, wireHeartbeat.copyOfRange(16, 28))
        assertEquals(checksum(wireHeartbeat) xor 0xFF, wireHeartbeat[28].toInt() and 0xFF)
    }

    @Test fun identityChecksumIsComplementedWithoutChangingBody() {
        val canonical = hex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")
        val expected = hex("a53641524a554e000000000000000000000000000000ffffffffff46087f")
        assertArrayEquals(expected, legacy.toWire(canonical))
        assertEquals(0xF7, canonical[28].toInt() and 0xFF)
    }

    @Test fun navigationUsesTheSameLegacyChecksumRule() {
        val canonical = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        val expected = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffffe07f")
        assertArrayEquals(expected, legacy.toWire(canonical))
    }

    @Test fun legacyHeartbeatPadsTailAndUsesAsciiZeroSignalBeforeChecksum() {
        val canonical = hex("a5333359323134003035303135344e4effffffffff010001ffffffff1a7f")
        val expected = hex("a5333359323134303035303135344e4e" + "ffffffffffffffffffffffff" + "ba7f")
        assertArrayEquals(expected, legacy.toWire(canonical))
        assertEquals(1.toByte(), canonical[21])
    }

    @Test fun standardPreservesCapturedFramesExactly() {
        for (text in listOf(
            "a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f",
            "a5333359323134003035303135344e4effffffffff010001ffffffff1a7f",
            "a53641524a554e000000000000000000000000000000ffffffffff46f77f",
        )) {
            val canonical = hex(text)
            assertArrayEquals(canonical, ClusterProtocol.STANDARD.toWire(canonical))
        }
    }

    @Test fun decodesSyntheticLegacyTelemetryAfterCheckingWireChecksum() {
        val wire = hex("a5373030303031363732393034393131303030393834394e3439ffffd97f")
        val decoded = legacy.decode(wire) as TelemetryFrame
        assertEquals(16729, decoded.odometerKm)
        assertArrayEquals(wire, decoded.raw)
        assertEquals(0xD9, wire[28].toInt() and 0xFF)
    }

    @Test fun legacyNavRemainsDecodableByPreviewAndInspector() {
        val wire = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffffe07f")
        val decoded = legacy.decode(wire) as NavFrame
        assertEquals(8, decoded.maneuverId)
        assertEquals("05.1", decoded.distTotal)
        assertArrayEquals(wire, decoded.raw)
        assertArrayEquals(wire, legacy.toWire(decoded.encode()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadLegacyChecksum() {
        legacy.decode(hex("a5373030303031363732393034393131303030393834394e3439ffff267f"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDamagedCanonicalFrameRatherThanRepairingIt() {
        legacy.toWire(hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff007f"))
    }
}
