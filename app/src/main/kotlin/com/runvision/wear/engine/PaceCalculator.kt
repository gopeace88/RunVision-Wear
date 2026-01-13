package com.runvision.wear.engine

/**
 * Pace Calculator
 * Converts GPS speed (m/s) to pace (seconds per km)
 *
 * This implements the same algorithm used in Garmin DataField.
 * Reference: runvision-iq/source/RunVisionIQView.mc:361-368
 *
 * Formula: pace (min/km) = 60 / speed (km/h)
 * Where speed (km/h) = speed (m/s) * 3.6
 */
object PaceCalculator {

    /**
     * Calculate pace in seconds per km from speed in m/s
     *
     * @param speedMs Speed in meters per second
     * @return Pace in total seconds per kilometer, or 0 for invalid speed
     *
     * Example:
     * - 12 km/h = 3.33 m/s -> 5:00 min/km = 300 seconds
     * - 10 km/h = 2.78 m/s -> 6:00 min/km = 360 seconds
     */
    fun calculatePaceSeconds(speedMs: Float): Int {
        if (speedMs <= 0f) return 0

        // Convert m/s to km/h
        val speedKmh = speedMs * 3.6f

        // Calculate pace in minutes per km
        val paceMinPerKm = 60.0f / speedKmh

        // Extract minutes and seconds (matching Garmin implementation)
        val paceMin = paceMinPerKm.toInt()
        val paceSec = ((paceMinPerKm - paceMin) * 60).toInt()

        // Return total seconds
        return paceMin * 60 + paceSec
    }

    /**
     * Format pace seconds as "M:SS" string
     *
     * @param paceSeconds Total pace in seconds per kilometer
     * @return Formatted string like "5:00" or "--:--" for invalid values
     */
    fun formatPace(paceSeconds: Int): String {
        if (paceSeconds <= 0) return "--:--"

        val min = paceSeconds / 60
        val sec = paceSeconds % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }
}
