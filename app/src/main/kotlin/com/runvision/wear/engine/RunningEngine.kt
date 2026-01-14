package com.runvision.wear.engine

import com.runvision.wear.data.RunningMetrics

/**
 * Running Engine - Aggregates all running metrics
 *
 * Central coordinator that combines:
 * - DistanceCalculator (GPS distance)
 * - SpeedCalculator (smoothed speed)
 * - PaceCalculator (pace from speed)
 * - Sensor data (HR, cadence from Health Services)
 * - Timer (elapsed seconds)
 *
 * Produces RunningMetrics for display and rLens transmission.
 */
class RunningEngine {

    private val distanceCalculator = DistanceCalculator()
    private val speedCalculator = SpeedCalculator()

    private var elapsedSeconds: Int = 0
    private var heartRate: Int = 0
    private var cadence: Int = 0
    private var paceSecondsPerKm: Int = 0
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0

    // Pace smoothing - keep last valid pace to avoid flickering
    private var lastValidPace: Int = 0
    private var lastValidPaceTime: Long = 0

    // Cadence-based distance estimation (fallback)
    private var totalSteps: Long = 0
    private var lastCadenceTime: Long = 0
    private var hasGpsDistance: Boolean = false

    // Health Services distance tracking (most accurate)
    private var lastHealthDistance: Double = 0.0
    private var lastHealthDistanceTime: Long = 0
    private var hasHealthServicesDistance: Boolean = false

    companion object {
        // Average stride length in meters (running ~0.8m, walking ~0.7m)
        private const val STRIDE_LENGTH_METERS = 0.78

        // Valid pace range for running (seconds per km)
        // Elite: 3:00/km (180s), Slow jog: 10:00/km (600s)
        private const val MIN_PACE_SECONDS = 180   // 3:00 min/km (elite marathon)
        private const val MAX_PACE_SECONDS = 600   // 10:00 min/km (slow jog)

        // Minimum cadence to consider "running" (not walking)
        private const val MIN_RUNNING_CADENCE = 120  // steps per minute

        // Pace smoothing: keep valid pace for this duration before showing 0
        private const val PACE_VALID_DURATION_MS = 3000L  // 3 seconds
    }

    private var isRunning: Boolean = false

    /**
     * Start the running session
     */
    fun start() {
        isRunning = true
    }

    /**
     * Pause the running session (time stops accumulating)
     */
    fun pause() {
        isRunning = false
    }

    /**
     * Resume a paused session
     */
    fun resume() {
        isRunning = true
    }

    /**
     * Stop the running session
     */
    fun stop() {
        isRunning = false
    }

    /**
     * Call every second to increment elapsed time
     * Only increments when the engine is running
     */
    fun tick() {
        if (isRunning) {
            elapsedSeconds++
        }
    }

    /**
     * Update GPS location
     * Note: Pace is calculated from Health Services distance when available (more accurate)
     *
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees
     * @param timestamp Timestamp in milliseconds
     */
    fun updateGps(lat: Double, lon: Double, timestamp: Long) {
        if (!isRunning) return

        hasGpsDistance = true
        lastLat = lat
        lastLon = lon

        // Only use GPS for distance/pace if Health Services distance is not available
        if (!hasHealthServicesDistance) {
            distanceCalculator.addPoint(lat, lon)
            val speedMs = speedCalculator.calculateSpeed(lat, lon, timestamp)
            paceSecondsPerKm = PaceCalculator.calculatePaceSeconds(speedMs)
        }
    }

    /**
     * Update heart rate from sensor
     *
     * @param bpm Heart rate in beats per minute
     */
    fun updateHeartRate(bpm: Int) {
        heartRate = bpm
    }

    /**
     * Update cadence from sensor
     * Also calculates steps for distance estimation when GPS is unavailable
     *
     * @param spm Steps per minute
     */
    fun updateCadence(spm: Int) {
        cadence = spm

        // Estimate steps since last update
        val now = System.currentTimeMillis()
        if (lastCadenceTime > 0 && isRunning && spm > 0) {
            val deltaSeconds = (now - lastCadenceTime) / 1000.0
            val stepsInPeriod = (spm * deltaSeconds / 60.0).toLong()
            totalSteps += stepsInPeriod

            // If no GPS distance, calculate from cadence
            if (!hasGpsDistance) {
                val estimatedDistance = totalSteps * STRIDE_LENGTH_METERS
                distanceCalculator.setDistance(estimatedDistance)

                // Calculate pace from cadence (seconds per km)
                // speed (m/s) = cadence (steps/min) * stride_length (m) / 60
                // pace (s/km) = 1000 / speed
                if (spm > 0) {
                    val speedMs = spm * STRIDE_LENGTH_METERS / 60.0
                    paceSecondsPerKm = if (speedMs > 0) (1000.0 / speedMs).toInt() else 0
                }
            }
        }
        lastCadenceTime = now
    }

    /**
     * Update distance from Health Services
     * This is the most accurate distance (sensor-fused: GPS + accelerometer + step calibration)
     * Also calculates pace from distance delta
     *
     * @param meters Distance in meters
     */
    fun updateDistance(meters: Double) {
        if (!isRunning) return

        hasHealthServicesDistance = true
        distanceCalculator.setDistance(meters)

        // Calculate pace from Health Services distance delta
        val now = System.currentTimeMillis()
        if (lastHealthDistanceTime > 0 && meters > lastHealthDistance) {
            val deltaDistance = meters - lastHealthDistance  // meters
            val deltaTime = (now - lastHealthDistanceTime) / 1000.0  // seconds

            // Minimum 100ms to avoid division issues
            if (deltaTime > 0.1 && deltaDistance > 0) {
                val speedMs = (deltaDistance / deltaTime).toFloat()  // m/s
                paceSecondsPerKm = PaceCalculator.calculatePaceSeconds(speedMs)

                // Save as last valid pace if within valid range
                if (paceSecondsPerKm in MIN_PACE_SECONDS..MAX_PACE_SECONDS) {
                    lastValidPace = paceSecondsPerKm
                    lastValidPaceTime = now
                }
            }
        }

        lastHealthDistance = meters
        lastHealthDistanceTime = now
    }

    /**
     * Get current aggregated running metrics
     *
     * Pace smoothing strategy:
     * - Use lastValidPace if it was updated within PACE_VALID_DURATION_MS (3 seconds)
     * - Show 0 only if no valid pace for 3+ seconds (truly stopped)
     * - This prevents flickering when Health Services sends inconsistent distance deltas
     *
     * @return RunningMetrics with all current values
     */
    fun getCurrentMetrics(): RunningMetrics {
        val now = System.currentTimeMillis()

        // Use last valid pace if within valid duration, otherwise 0
        val displayPace = if (lastValidPace > 0 &&
                             (now - lastValidPaceTime) < PACE_VALID_DURATION_MS) {
            lastValidPace
        } else {
            0
        }

        return RunningMetrics(
            elapsedSeconds = elapsedSeconds,
            distanceMeters = distanceCalculator.getTotalDistance(),
            paceSecondsPerKm = displayPace,
            heartRate = heartRate,
            cadence = cadence,
            latitude = lastLat,
            longitude = lastLon
        )
    }

    /**
     * Reset all data to initial state
     */
    fun reset() {
        elapsedSeconds = 0
        heartRate = 0
        cadence = 0
        paceSecondsPerKm = 0
        lastLat = 0.0
        lastLon = 0.0
        totalSteps = 0
        lastCadenceTime = 0
        hasGpsDistance = false
        lastHealthDistance = 0.0
        lastHealthDistanceTime = 0
        hasHealthServicesDistance = false
        isRunning = false
        // Pace smoothing fields
        lastValidPace = 0
        lastValidPaceTime = 0
        distanceCalculator.reset()
        speedCalculator.reset()
    }

    /**
     * Check if the engine is currently running
     *
     * @return true if running, false if paused or stopped
     */
    fun isActive(): Boolean = isRunning
}
