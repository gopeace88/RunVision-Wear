package com.runvision.wear.ble

import org.junit.Assert.*
import org.junit.Test

/**
 * RLensProtocol unit tests
 *
 * Tests BLE packet encoding for rLens AR glasses.
 * Packet format: [Metric_ID (1 byte)] [Value (4 bytes, Little-Endian UINT32)]
 */
class RLensProtocolTest {

    // ========== Heart Rate Packet Tests ==========

    @Test
    fun `create heart rate packet with correct format`() {
        val packet = RLensProtocol.createHeartRatePacket(156)

        assertEquals(5, packet.size)
        assertEquals(0x0B.toByte(), packet[0])  // Metric ID
        assertEquals(156.toByte(), packet[1])   // LSB
        assertEquals(0.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])     // MSB
    }

    @Test
    fun `create heart rate packet with zero value`() {
        val packet = RLensProtocol.createHeartRatePacket(0)

        assertEquals(5, packet.size)
        assertEquals(0x0B.toByte(), packet[0])
        assertEquals(0.toByte(), packet[1])
        assertEquals(0.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    @Test
    fun `create heart rate packet with max typical value`() {
        val packet = RLensProtocol.createHeartRatePacket(220)

        assertEquals(5, packet.size)
        assertEquals(0x0B.toByte(), packet[0])
        assertEquals(220.toByte(), packet[1])   // 220 = 0xDC
        assertEquals(0.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== Distance Packet Tests ==========

    @Test
    fun `create distance packet with little endian encoding`() {
        val packet = RLensProtocol.createDistancePacket(5230)  // 5.23km in meters

        assertEquals(5, packet.size)
        assertEquals(0x06.toByte(), packet[0])  // Metric ID
        // 5230 = 0x146E -> Little Endian: 6E 14 00 00
        assertEquals(0x6E.toByte(), packet[1])
        assertEquals(0x14.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    @Test
    fun `create distance packet with large value`() {
        val packet = RLensProtocol.createDistancePacket(42195)  // Marathon in meters

        assertEquals(5, packet.size)
        assertEquals(0x06.toByte(), packet[0])
        // 42195 = 0xA4D3 -> Little Endian: D3 A4 00 00
        assertEquals(0xD3.toByte(), packet[1])
        assertEquals(0xA4.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    @Test
    fun `create distance packet with multi-byte value`() {
        val packet = RLensProtocol.createDistancePacket(100000)  // 100km

        assertEquals(5, packet.size)
        assertEquals(0x06.toByte(), packet[0])
        // 100000 = 0x186A0 -> Little Endian: A0 86 01 00
        assertEquals(0xA0.toByte(), packet[1])
        assertEquals(0x86.toByte(), packet[2])
        assertEquals(0x01.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== Pace/Velocity Packet Tests ==========

    @Test
    fun `create pace packet`() {
        val packet = RLensProtocol.createPacePacket(292)  // 4:52 min/km = 292 seconds

        assertEquals(5, packet.size)
        assertEquals(0x07.toByte(), packet[0])  // VELOCITY metric ID
        // 292 = 0x124 -> Little Endian: 24 01 00 00
        assertEquals(0x24.toByte(), packet[1])
        assertEquals(0x01.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    @Test
    fun `create pace packet with slow pace`() {
        val packet = RLensProtocol.createPacePacket(600)  // 10:00 min/km = 600 seconds

        assertEquals(5, packet.size)
        assertEquals(0x07.toByte(), packet[0])
        // 600 = 0x258 -> Little Endian: 58 02 00 00
        assertEquals(0x58.toByte(), packet[1])
        assertEquals(0x02.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== Cadence Packet Tests ==========

    @Test
    fun `create cadence packet`() {
        val packet = RLensProtocol.createCadencePacket(180)  // 180 steps per minute

        assertEquals(5, packet.size)
        assertEquals(0x0E.toByte(), packet[0])  // CADENCE metric ID
        assertEquals(180.toByte(), packet[1])    // 180 = 0xB4
        assertEquals(0.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== Elapsed Time Packet Tests ==========

    @Test
    fun `create elapsed time packet`() {
        val packet = RLensProtocol.createElapsedTimePacket(3661)  // 1:01:01

        assertEquals(5, packet.size)
        assertEquals(0x01.toByte(), packet[0])  // RECORD_STATUS/ELAPSED_TIME metric ID
        // 3661 = 0xE4D -> Little Endian: 4D 0E 00 00
        assertEquals(0x4D.toByte(), packet[1])
        assertEquals(0x0E.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== Generic Packet Creation Tests ==========

    @Test
    fun `create packet with maximum 32-bit value`() {
        val packet = RLensProtocol.createPacket(0x06, Int.MAX_VALUE)

        assertEquals(5, packet.size)
        assertEquals(0x06.toByte(), packet[0])
        // Int.MAX_VALUE = 0x7FFFFFFF -> Little Endian: FF FF FF 7F
        assertEquals(0xFF.toByte(), packet[1])
        assertEquals(0xFF.toByte(), packet[2])
        assertEquals(0xFF.toByte(), packet[3])
        assertEquals(0x7F.toByte(), packet[4])
    }

    @Test
    fun `create packet with negative value should clamp to zero`() {
        val packet = RLensProtocol.createPacket(0x06, -100)

        assertEquals(5, packet.size)
        assertEquals(0x06.toByte(), packet[0])
        // Negative clamped to 0
        assertEquals(0.toByte(), packet[1])
        assertEquals(0.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(0.toByte(), packet[4])
    }

    // ========== UUID Tests ==========

    @Test
    fun `service and characteristic UUIDs match spec`() {
        assertEquals(
            "4b329cf2-3816-498c-8453-ee8798502a08",
            RLensProtocol.EXERCISE_SERVICE_UUID.toString()
        )
        assertEquals(
            "c259c1bd-18d3-c348-b88d-5447aea1b615",
            RLensProtocol.EXERCISE_DATA_UUID.toString()
        )
    }

    @Test
    fun `config service UUID matches spec`() {
        assertEquals(
            "58211c97-482a-2808-2d3e-228405f1e749",
            RLensProtocol.CONFIG_SERVICE_UUID.toString()
        )
    }

    @Test
    fun `universal subscriptions UUID matches spec`() {
        assertEquals(
            "c1329ce5-b463-31a5-8b78-bd220c1480cd",
            RLensProtocol.UNIVERSAL_SUBSCRIPTIONS_UUID.toString()
        )
    }

    // ========== Metric ID Constants Tests ==========

    @Test
    fun `metric ID constants match spec`() {
        assertEquals(0x01, RLensProtocol.METRIC_ELAPSED_TIME)
        assertEquals(0x06, RLensProtocol.METRIC_DISTANCE)
        assertEquals(0x07, RLensProtocol.METRIC_VELOCITY)
        assertEquals(0x0B, RLensProtocol.METRIC_HEART_RATE)
        assertEquals(0x0E, RLensProtocol.METRIC_CADENCE)
    }
}
