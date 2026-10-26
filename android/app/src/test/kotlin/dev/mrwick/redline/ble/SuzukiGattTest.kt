package dev.mrwick.redline.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuzukiGattTest {
    private fun capturedAccessService() = SuzukiGatt.Service(
        SuzukiGatt.ACCESS_SERVICE_UUID,
        listOf(
            SuzukiGatt.Characteristic(SuzukiGatt.ACCESS_WRITE_UUID, 0x04),
            SuzukiGatt.Characteristic(SuzukiGatt.ACCESS_NOTIFY_UUID, 0x10, true),
            SuzukiGatt.Characteristic(UUID.fromString("00000003-0000-1000-8000-008025000000"), 0x08),
            SuzukiGatt.Characteristic(UUID.fromString("00000004-0000-1000-8000-008025000000"), 0x20, true),
        ),
    )

    @Test fun resolvesAllFourCharacteristicsFromSas21UserLog() {
        val captured = capturedAccessService()
        for (chars in listOf(captured.characteristics, captured.characteristics.reversed())) {
            val endpoints = SuzukiGatt.resolve(listOf(captured.copy(characteristics = chars)))
            assertEquals(SuzukiGatt.ACCESS_WRITE_UUID, endpoints.write)
            assertEquals(SuzukiGatt.ACCESS_NOTIFY_UUID, endpoints.notify)
            assertFalse(endpoints.writeWithResponse)
            assertFalse(endpoints.indicate)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotGuessAlternateAccessChannelWhenCapturedNotifyHasNoCcc() {
        val captured = capturedAccessService()
        SuzukiGatt.resolve(listOf(captured.copy(characteristics = captured.characteristics.map {
            if (it.uuid == SuzukiGatt.ACCESS_NOTIFY_UUID) it.copy(hasCcc = false) else it
        })))
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotApplyCapturedAccessChannelsToDifferentService() {
        SuzukiGatt.resolve(listOf(capturedAccessService().copy(uuid = SuzukiGatt.SERVICE_UUID)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFourChannelLayoutInsteadOfPreferringResponseWrite() {
        val captured = capturedAccessService()
        SuzukiGatt.resolve(listOf(captured.copy(characteristics = captured.characteristics.map {
            if (it.uuid == SuzukiGatt.ACCESS_WRITE_UUID) it.copy(uuid = UUID.randomUUID()) else it
        })))
    }

    private val write = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    private val notify = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")

    private fun service(
        uuid: UUID = SuzukiGatt.ACCESS_SERVICE_UUID,
        writeProperties: Int = 0x08,
        notifyProperties: Int = 0x10,
        ccc: Boolean = true,
    ) = SuzukiGatt.Service(uuid, listOf(
        SuzukiGatt.Characteristic(write, writeProperties),
        SuzukiGatt.Characteristic(notify, notifyProperties, ccc),
    ))

    @Test fun resolvesFefbWithoutAssumingCharacteristicUuidsOrServiceOrder() {
        val standardServices = listOf("1800", "1801", "180a").map {
            SuzukiGatt.Service(UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb"), emptyList())
        }
        val endpoints = SuzukiGatt.resolve(listOf(service()) + standardServices)
        assertEquals(SuzukiGatt.ACCESS_SERVICE_UUID, endpoints.service)
        assertEquals(write, endpoints.write)
        assertEquals(notify, endpoints.notify)
        assertTrue(endpoints.writeWithResponse)
        assertFalse(endpoints.indicate)
    }

    @Test fun preservesFff0Support() {
        val endpoints = SuzukiGatt.resolve(listOf(SuzukiGatt.Service(SuzukiGatt.SERVICE_UUID, listOf(
            SuzukiGatt.Characteristic(SuzukiGatt.WRITE_CHAR_UUID, 0x08),
            SuzukiGatt.Characteristic(SuzukiGatt.NOTIFY_CHAR_UUID, 0x10, true),
        ))))
        assertEquals(SuzukiGatt.WRITE_CHAR_UUID, endpoints.write)
        assertEquals(SuzukiGatt.NOTIFY_CHAR_UUID, endpoints.notify)
    }

    @Test fun doesNotAssumeUnloggedUuidSuffixForFefb() {
        val uuid = UUID.fromString("0000fefb-1234-5678-9abc-0123456789ab")
        assertEquals(uuid, SuzukiGatt.resolve(listOf(service(uuid))).service)
    }

    @Test fun followsActualWriteAndIndicationCapabilities() {
        val endpoints = SuzukiGatt.resolve(listOf(service(writeProperties = 0x04, notifyProperties = 0x20)))
        assertFalse(endpoints.writeWithResponse)
        assertTrue(endpoints.indicate)
    }

    @Test fun prefersAcknowledgedWriteAndNotificationsWhenBothSupported() {
        val endpoints = SuzukiGatt.resolve(listOf(service(writeProperties = 0x0C, notifyProperties = 0x30)))
        assertTrue(endpoints.writeWithResponse)
        assertFalse(endpoints.indicate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNotifyWithoutCcc() {
        SuzukiGatt.resolve(listOf(service(ccc = false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAmbiguousServices() {
        SuzukiGatt.resolve(listOf(service(), service(SuzukiGatt.SERVICE_UUID)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotWriteToUnrelatedVendorService() {
        SuzukiGatt.resolve(listOf(service(UUID.randomUUID())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAmbiguousWriteCharacteristics() {
        val base = service()
        SuzukiGatt.resolve(listOf(base.copy(characteristics = base.characteristics +
            SuzukiGatt.Characteristic(UUID.randomUUID(), 0x08))))
    }
}
