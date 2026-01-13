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
     *
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees
     * @param timestamp Timestamp in milliseconds
     */
    fun updateGps(lat: Double, lon: Double, timestamp: Long) {
        if (!isRunning) return

        distanceCalculator.addPoint(lat, lon)
        val speedMs = speedCalculator.calculateSpeed(lat, lon, timestamp)
        paceSecondsPerKm = PaceCalculator.calculatePaceSeconds(speedMs)
        lastLat = lat
        lastLon = lon
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
     *
     * @param spm Steps per minute
     */
    fun updateCadence(spm: Int) {
        cadence = spm
    }

    /**
     * Get current aggregated running metrics
     *
     * @return RunningMetrics with all current values
     */
    fun getCurrentMetrics(): RunningMetrics {
        return RunningMetrics(
            elapsedSeconds = elapsedSeconds,
            distanceMeters = distanceCalculator.getTotalDistance(),
            paceSecondsPerKm = paceSecondsPerKm,
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
        isRunning = false
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
