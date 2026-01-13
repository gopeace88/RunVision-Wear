package com.runvision.wear.engine

import kotlin.math.*

/**
 * GPS Distance Calculator using Haversine formula
 *
 * Reference: runvision-iq design document Section 5.2
 */
class DistanceCalculator {

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var totalDistanceMeters: Float = 0f

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0
        private const val NOISE_THRESHOLD_METERS = 0.5f

        /**
         * Calculate distance between two GPS coordinates using Haversine formula
         */
        fun haversineDistance(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Float {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)

            val c = 2 * atan2(sqrt(a), sqrt(1 - a))

            return (EARTH_RADIUS_METERS * c).toFloat()
        }
    }

    /**
     * Add GPS point and return accumulated distance
     * Filters out small movements below threshold
     */
    fun addPoint(lat: Double, lon: Double): Float {
        val last = lastLat
        if (last != null && lastLon != null) {
            val delta = haversineDistance(last, lastLon!!, lat, lon)

            // Filter out GPS noise
            if (delta > NOISE_THRESHOLD_METERS) {
                totalDistanceMeters += delta
            }
        }

        lastLat = lat
        lastLon = lon

        return totalDistanceMeters
    }

    fun getTotalDistance(): Float = totalDistanceMeters

    fun reset() {
        lastLat = null
        lastLon = null
        totalDistanceMeters = 0f
    }
}
