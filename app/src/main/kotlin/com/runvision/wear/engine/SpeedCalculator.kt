package com.runvision.wear.engine

import java.util.ArrayDeque

/**
 * GPS Speed Calculator with moving average smoothing
 *
 * Uses 5-sample moving average to reduce GPS noise
 */
class SpeedCalculator(private val bufferSize: Int = 5) {

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimestamp: Long = 0
    private val speedBuffer = ArrayDeque<Float>(bufferSize)

    /**
     * Calculate smoothed speed from GPS point
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param timestamp Timestamp in milliseconds
     * @return Smoothed speed in m/s
     */
    fun calculateSpeed(lat: Double, lon: Double, timestamp: Long): Float {
        val lastLatVal = lastLat
        val lastLonVal = lastLon

        if (lastLatVal == null || lastLonVal == null) {
            lastLat = lat
            lastLon = lon
            lastTimestamp = timestamp
            return 0f
        }

        val distanceMeters = DistanceCalculator.haversineDistance(
            lastLatVal, lastLonVal, lat, lon
        )
        val timeDeltaSec = (timestamp - lastTimestamp) / 1000f

        lastLat = lat
        lastLon = lon
        lastTimestamp = timestamp

        if (timeDeltaSec <= 0) return getSmoothedSpeed()

        val instantSpeed = distanceMeters / timeDeltaSec

        // Add to moving average buffer
        speedBuffer.addLast(instantSpeed)
        if (speedBuffer.size > bufferSize) {
            speedBuffer.removeFirst()
        }

        return getSmoothedSpeed()
    }

    fun getSmoothedSpeed(): Float {
        if (speedBuffer.isEmpty()) return 0f
        return speedBuffer.average().toFloat()
    }

    fun reset() {
        lastLat = null
        lastLon = null
        lastTimestamp = 0
        speedBuffer.clear()
    }
}
