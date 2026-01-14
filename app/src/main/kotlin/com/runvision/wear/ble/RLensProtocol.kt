package com.runvision.wear.ble

import java.util.UUID

/**
 * rLens BLE Protocol Implementation
 * Based on rLens BLE V1.0.1 Specification
 *
 * Packet Format: [Metric_ID (1 byte)] [Value (4 bytes, Little-Endian UINT32)]
 *
 * Reference: runvision-iq/source/ILensProtocol.mc (Garmin Monkey C implementation)
 */
object RLensProtocol {

    // ========== Service UUIDs ==========
    val EXERCISE_SERVICE_UUID: UUID = UUID.fromString("4b329cf2-3816-498c-8453-ee8798502a08")
    val CONFIG_SERVICE_UUID: UUID = UUID.fromString("58211c97-482a-2808-2d3e-228405f1e749")

    // ========== Characteristic UUIDs ==========
    val EXERCISE_DATA_UUID: UUID = UUID.fromString("c259c1bd-18d3-c348-b88d-5447aea1b615")
    val UNIVERSAL_SUBSCRIPTIONS_UUID: UUID = UUID.fromString("c1329ce5-b463-31a5-8b78-bd220c1480cd")
    val CURRENT_TIME_UUID: UUID = UUID.fromString("54ac7f82-eb87-aa4e-0154-a71d80471e6e")
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("33bd4a32-f763-0391-2820-55610f999aef")

    // ========== Metric IDs ==========
    const val METRIC_ELAPSED_TIME = 0x01    // Elapsed time in seconds
    const val METRIC_DISTANCE = 0x06        // Distance in meters
    const val METRIC_VELOCITY = 0x07        // Pace in seconds/km
    const val METRIC_HEART_RATE = 0x0B      // Heart rate in bpm
    const val METRIC_CADENCE = 0x0E         // Cadence in steps per minute

    // Additional metric IDs from spec
    const val METRIC_RECORD_STATUS = 0x01   // 0: Start, 1: Pause, 2: End
    const val METRIC_HEAT_DISSIPATION = 0x02
    const val METRIC_EXERCISE_TIME = 0x03
    const val METRIC_TOTAL_TIME = 0x04
    const val METRIC_PAUSE_TIME = 0x05
    const val METRIC_AVG_MOVEMENT_SPEED = 0x08
    const val METRIC_AVG_SPEED = 0x09
    const val METRIC_MAX_SPEED = 0x0A
    const val METRIC_AVG_HEART_RATE = 0x0C
    const val METRIC_MAX_HEART_RATE = 0x0D
    const val METRIC_MAX_CADENCE = 0x0F
    const val METRIC_AVG_CADENCE = 0x10
    const val METRIC_POWER = 0x11
    const val METRIC_MAX_POWER = 0x12
    const val METRIC_AVG_POWER = 0x13

    /**
     * Create metric packet with Little-Endian UINT32 encoding
     *
     * @param metricId Metric identifier (see METRIC_* constants)
     * @param value Integer value to encode (clamped to [0, Int.MAX_VALUE])
     * @return ByteArray 5-byte packet [MetricID, Value_LSB, ..., Value_MSB]
     */
    fun createPacket(metricId: Int, value: Int): ByteArray {
        // Clamp value to safe range [0, Int.MAX_VALUE]
        val safeValue = value.coerceIn(0, Int.MAX_VALUE)

        return byteArrayOf(
            metricId.toByte(),
            (safeValue and 0xFF).toByte(),           // Byte 0 (LSB)
            ((safeValue shr 8) and 0xFF).toByte(),   // Byte 1
            ((safeValue shr 16) and 0xFF).toByte(),  // Byte 2
            ((safeValue shr 24) and 0xFF).toByte()   // Byte 3 (MSB)
        )
    }

    /**
     * Create elapsed time packet
     * @param seconds Elapsed time in seconds
     */
    fun createElapsedTimePacket(seconds: Int): ByteArray =
        createPacket(METRIC_ELAPSED_TIME, seconds)

    /**
     * Create distance packet
     * @param meters Distance in meters
     */
    fun createDistancePacket(meters: Int): ByteArray =
        createPacket(METRIC_DISTANCE, meters)

    /**
     * Create pace/velocity packet
     * @param secondsPerKm Pace in seconds per kilometer (e.g., 292 = 4:52/km)
     */
    fun createPacePacket(secondsPerKm: Int): ByteArray =
        createPacket(METRIC_VELOCITY, secondsPerKm)

    /**
     * Create heart rate packet
     * @param bpm Heart rate in beats per minute
     */
    fun createHeartRatePacket(bpm: Int): ByteArray =
        createPacket(METRIC_HEART_RATE, bpm)

    /**
     * Create cadence packet
     * @param spm Cadence in steps per minute
     */
    fun createCadencePacket(spm: Int): ByteArray =
        createPacket(METRIC_CADENCE, spm)

    /**
     * Create record status packet
     * @param status 0: Start, 1: Pause, 2: End
     */
    fun createRecordStatusPacket(status: Int): ByteArray =
        createPacket(METRIC_RECORD_STATUS, status)

    /**
     * Create exercise time packet
     * @param seconds Exercise time in seconds
     */
    fun createExerciseTimePacket(seconds: Int): ByteArray =
        createPacket(METRIC_EXERCISE_TIME, seconds)

    /**
     * Create power packet
     * @param watts Power in watts
     */
    fun createPowerPacket(watts: Int): ByteArray =
        createPacket(METRIC_POWER, watts)

    // ========== Current Time Packet for Elapsed Time ==========
    // WORKAROUND: Send elapsed time using iLens's Current Time characteristic
    // This allows pause/resume to work correctly (watch controls the time display)
    // Reference: runvision-iq/source/ILensProtocol.mc:createCurrentTimePacket

    /**
     * Create Current Time packet with elapsed time
     *
     * Packet Structure (10 bytes):
     *   [0-1]  year (UINT16, little-endian) - current date
     *   [2]    month (UINT8, 1-12) - current date
     *   [3]    day (UINT8, 1-31) - current date
     *   [4]    hour (UINT8, 0-23) - ELAPSED hours
     *   [5]    minute (UINT8, 0-59) - ELAPSED minutes
     *   [6]    second (UINT8, 0-59) - ELAPSED seconds
     *   [7]    dayOfWeek (UINT8, 1=Mon...7=Sun) - current
     *   [8]    fraction256 (UINT8, 1/256 second) - always 0
     *   [9]    reason (UINT8, 0x02=External Reference)
     *
     * @param elapsedSeconds Exercise elapsed time in seconds
     * @return ByteArray 10-byte packet
     */
    fun createCurrentTimePacket(elapsedSeconds: Int): ByteArray {
        val calendar = java.util.Calendar.getInstance()

        // Year (UINT16, little-endian)
        val year = calendar.get(java.util.Calendar.YEAR)

        // Month (1-12)
        val month = calendar.get(java.util.Calendar.MONTH) + 1

        // Day (1-31)
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        // Convert elapsed seconds to H:M:S
        val hours = (elapsedSeconds / 3600) % 24
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60

        // DayOfWeek: Calendar uses 1=Sunday...7=Saturday
        // iLens expects: 1=Monday...7=Sunday
        val calDow = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val iLensDow = if (calDow == java.util.Calendar.SUNDAY) 7 else calDow - 1

        return byteArrayOf(
            (year and 0xFF).toByte(),           // Year low byte
            ((year shr 8) and 0xFF).toByte(),   // Year high byte
            month.toByte(),
            day.toByte(),
            hours.toByte(),
            minutes.toByte(),
            seconds.toByte(),
            iLensDow.toByte(),
            0x00,                               // Fraction256
            0x02                                // Reason: External Reference
        )
    }

    // ========== Universal Subscriptions Initialization Commands ==========
    // CRITICAL: Must be sent after BLE connection before sending exercise data
    // Reference: iLens-BLE-Connection-Best-Practices.md

    /**
     * Universal Subscriptions command 1: Read version/language
     * Write to UNIVERSAL_SUBSCRIPTIONS_UUID, wait for NOTIFY response
     */
    fun createUniversalSubCommand1(): ByteArray = byteArrayOf(0x10, 0x01)

    /**
     * Universal Subscriptions command 2: Read BT address
     * Write to UNIVERSAL_SUBSCRIPTIONS_UUID, wait for NOTIFY response
     */
    fun createUniversalSubCommand2(): ByteArray = byteArrayOf(0x10, 0x04)

    /**
     * Universal Subscriptions command 3: Read translation settings
     * Write to UNIVERSAL_SUBSCRIPTIONS_UUID, wait for NOTIFY response
     */
    fun createUniversalSubCommand3(): ByteArray = byteArrayOf(0x10, 0x03)
}
