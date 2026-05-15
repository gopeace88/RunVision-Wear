package com.runvision.wear.engine

import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.data.RunningMetrics

/**
 * Cycling Engine — parallel to RunningEngine, ZERO modification to it.
 *
 * Produces two views:
 *  - getCurrentMetrics(): honest CyclingMetrics for the watch screen
 *  - getRLensPayload(): RunningMetrics with fields remapped so the UNMODIFIED
 *    RLensConnection.sendMetrics() emits bytes identical to runvision-iq's
 *    CyclingStrategy (speed×60 -> 0x07, altitude -> 0x0E, HR -> 0x0B).
 *
 * Wear OS watches always have an optical HR sensor active during a session,
 * so runvision-iq's 30s HR-lock / totalAscent fallback is dead code here and
 * is intentionally NOT implemented (spec §2.3).
 *
 * Reuses SpeedCalculator + DistanceCalculator (mode-agnostic, instance-only).
 */
class CyclingEngine {

    private val speedCalculator = SpeedCalculator()
    private val distanceCalculator = DistanceCalculator()

    private var isRunning: Boolean = false
    private var elapsedSeconds: Int = 0
    private var heartRate: Int = 0
    private var altitudeM: Int = 0
    private var speedKmh: Float = 0f
    private var distanceM: Float = 0f
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var hasHealthServicesDistance: Boolean = false

    companion object {
        /**
         * runvision-iq CyclingStrategy.mc:35 byte-mirror.
         * rLens divides 0x07 by 60 to display, so send km/h ×60 (decimals preserved).
         * iq: (speedKmh * 60 + 0.5).toNumber(); encodeUINT32 clamps [0, 2147483647].
         */
        fun scaleSpeedToVelocity(speedKmh: Float): Int =
            ((speedKmh.toDouble() * 60.0) + 0.5).toInt().coerceIn(0, Int.MAX_VALUE)
    }

    fun start() { isRunning = true }
    fun pause() { isRunning = false }
    fun resume() { isRunning = true }
    fun stop() { isRunning = false }
    fun isActive(): Boolean = isRunning

    fun tick() {
        if (isRunning) elapsedSeconds++
    }

    /** HR may arrive before start (MeasureClient); store latest unconditionally — mirrors RunningEngine. */
    fun updateHeartRate(bpm: Int) { heartRate = bpm }

    /** Current altitude in meters (GPS-derived; barometer-less watches are lower accuracy — spec caveat). */
    fun updateAltitude(meters: Double) { altitudeM = meters.toInt() }

    fun updateGps(lat: Double, lon: Double, timestamp: Long) {
        if (!isRunning) return
        lastLat = lat
        lastLon = lon
        val speedMs = speedCalculator.calculateSpeed(lat, lon, timestamp)
        speedKmh = speedMs * 3.6f
        if (!hasHealthServicesDistance) {
            distanceCalculator.addPoint(lat, lon)
            distanceM = distanceCalculator.getTotalDistance()
        }
    }

    /** Health Services sensor-fused distance — preferred over raw GPS (mirrors RunningEngine policy). */
    fun updateDistance(meters: Double) {
        if (!isRunning) return
        hasHealthServicesDistance = true
        distanceM = meters.toFloat()
    }

    fun getCurrentMetrics(): CyclingMetrics = CyclingMetrics(
        elapsedSeconds = elapsedSeconds,
        distanceMeters = distanceM,
        speedKmh = speedKmh,
        heartRate = heartRate,
        altitudeM = altitudeM
    )

    /**
     * Remapped RunningMetrics consumed by the UNMODIFIED RLensConnection.sendMetrics():
     *   createExerciseTimePacket(elapsedSeconds) -> 0x03
     *   createPacePacket(paceSecondsPerKm)       -> 0x07  (= speed×60)
     *   createHeartRatePacket(heartRate)         -> 0x0B
     *   createCadencePacket(cadence)             -> 0x0E  (= altitude m)
     *   createDistancePacket(distanceMeters)     -> 0x06
     */
    fun getRLensPayload(): RunningMetrics = RunningMetrics(
        elapsedSeconds = elapsedSeconds,
        distanceMeters = distanceM,
        paceSecondsPerKm = scaleSpeedToVelocity(speedKmh),
        heartRate = heartRate,
        cadence = altitudeM,
        latitude = lastLat,
        longitude = lastLon
    )

    fun reset() {
        isRunning = false
        elapsedSeconds = 0
        heartRate = 0
        altitudeM = 0
        speedKmh = 0f
        distanceM = 0f
        lastLat = 0.0
        lastLon = 0.0
        hasHealthServicesDistance = false
        speedCalculator.reset()
        distanceCalculator.reset()
    }
}
