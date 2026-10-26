package dev.mrwick.redline.ble

import java.util.UUID

/**
 * Gixxer constants and capability-checked endpoint discovery.
 * Verified on the original Gixxer (NOTES.md "BLE GATT tree (M1)"):
 *   - one vendor service 0xFFF0
 *   - one write characteristic 0xFFF1 (phone -> bike)
 *   - one notify characteristic 0xFFF2 (bike -> phone)
 */
object SuzukiGatt {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val ACCESS_SERVICE_UUID: UUID = UUID.fromString("0000fefb-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val ACCESS_WRITE_UUID: UUID = UUID.fromString("00000001-0000-1000-8000-008025000000")
    val ACCESS_NOTIFY_UUID: UUID = UUID.fromString("00000002-0000-1000-8000-008025000000")
    private val ACCESS_RESPONSE_WRITE_UUID: UUID = UUID.fromString("00000003-0000-1000-8000-008025000000")
    private val ACCESS_INDICATE_UUID: UUID = UUID.fromString("00000004-0000-1000-8000-008025000000")

    data class Characteristic(
        val uuid: UUID,
        val properties: Int,
        val hasCcc: Boolean = false,
    )

    data class Service(val uuid: UUID, val characteristics: List<Characteristic>)

    data class Endpoints(
        val service: UUID,
        val write: UUID,
        val notify: UUID,
        val writeWithResponse: Boolean,
        val indicate: Boolean,
    )

    /**
     * OEM selects the vendor service's first/second characteristics by position.
     * Restrict discovery to the captured vendor services and verify capabilities
     * rather than assuming UUIDs, service order, or arbitrary nearby BLE devices.
     */
    fun resolve(services: List<Service>): Endpoints {
        val candidates = services.filter {
            // The supplied Access logs record only the first 32 UUID bits.
            it.uuid.toString().substringBefore('-') in setOf("0000fff0", "0000fefb")
        }.mapNotNull { service ->
            // SAS21 log: four channels. OEM writes characteristic index 0 and
            // subscribes to index 1; bind those observed UUIDs, not list order.
            if (isCapturedAccessLayout(service)) {
                return@mapNotNull Endpoints(
                    service.uuid, ACCESS_WRITE_UUID, ACCESS_NOTIFY_UUID,
                    writeWithResponse = false, indicate = false,
                )
            }
            val writes = service.characteristics.filter { it.properties and (0x08 or 0x04) != 0 }
            val notifies = service.characteristics.filter {
                it.hasCcc && it.properties and (0x10 or 0x20) != 0
            }
            if (writes.size != 1 || notifies.size != 1 || writes.single().uuid == notifies.single().uuid) {
                null
            } else {
                Endpoints(
                    service.uuid, writes.single().uuid, notifies.single().uuid,
                    writeWithResponse = writes.single().properties and 0x08 != 0,
                    indicate = notifies.single().properties and 0x10 == 0,
                )
            }
        }
        require(candidates.size == 1) {
            "Expected one Suzuki write/notify pair on FFF0 or FEFB; found ${candidates.size}. See GATT inventory."
        }
        return candidates.single()
    }

    private fun isCapturedAccessLayout(service: Service): Boolean {
        if (service.uuid != ACCESS_SERVICE_UUID || service.characteristics.size != 4) return false
        val expected = setOf(
            Characteristic(ACCESS_WRITE_UUID, 0x04),
            Characteristic(ACCESS_NOTIFY_UUID, 0x10, hasCcc = true),
            Characteristic(ACCESS_RESPONSE_WRITE_UUID, 0x08),
            Characteristic(ACCESS_INDICATE_UUID, 0x20, hasCcc = true),
        )
        return service.characteristics.toSet() == expected
    }

    /**
     * Standard Bluetooth SIG Device Information Service (0x180A).
     * The bike publishes these characteristics on connect (Phase 1 NOTES.md):
     *   0x2A23 System ID, 0x2A24 Model Number, 0x2A25 Serial Number,
     *   0x2A26 Firmware Rev, 0x2A28 Software Rev, 0x2A29 Manufacturer,
     *   0x2A2A IEEE Cert, 0x2A50 PnP ID.
     */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CHAR_SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val CHAR_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CHAR_HARDWARE_REV: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_SYSTEM_ID: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")
    val CHAR_IEEE_CERT: UUID = UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb")
    val CHAR_PNP_ID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
}
