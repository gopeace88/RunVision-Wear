// app/src/main/kotlin/com/runvision/wear/engine/AdaptivePaceCalculator.kt
package com.runvision.wear.engine

/**
 * Adaptive Pace Calculator
 *
 * Automatically switches between GPS and cadence-based pace calculation.
 * Uses GPS when available, falls back to cadence after timeout.
 */
class AdaptivePaceCalculator(
    private val strideLearner: StrideLengthLearner,
    private val smoother: PaceSmoother,
    private val stopDetector: StopDetector
) {

    private var hasGps: Boolean = false
    private var lastGpsTime: Long = 0

    companion object {
        const val GPS_TIMEOUT_MS = 5000L  // 5 seconds
    }

    /**
     * Update with GPS distance data
     *
     * @param distanceMeters Distance traveled in meters
     * @param deltaTimeSeconds Time elapsed in seconds
     * @return Smoothed pace in seconds per km, or 0 if stopped
     */
    fun updateWithGpsDistance(distanceMeters: Double, deltaTimeSeconds: Double): Int {
        hasGps = true
        lastGpsTime = System.currentTimeMillis()
        stopDetector.updateDistanceChange()

        if (stopDetector.isStopped()) return 0
        if (deltaTimeSeconds <= 0 || distanceMeters <= 0) return smoother.getSmoothedPace()

        val speedMs = distanceMeters / deltaTimeSeconds
        val rawPace = if (speedMs > 0) (1000.0 / speedMs).toInt() else 0

        return smoother.addPace(rawPace)
    }

    /**
     * Update with cadence data (for non-GPS mode)
     *
     * @param cadence Steps per minute
     * @return Smoothed pace in seconds per km, or 0 if stopped
     */
    fun updateWithCadence(cadence: Int): Int {
        stopDetector.updateCadence(cadence)

        if (stopDetector.isStopped()) return 0

        val now = System.currentTimeMillis()
        if (hasGps && (now - lastGpsTime) < GPS_TIMEOUT_MS) {
            return smoother.getSmoothedPace()
        }

        hasGps = false

        if (cadence <= 0) return smoother.getSmoothedPace()

        val stride = strideLearner.getStrideLength(cadence)
        val speedMs = cadence * stride / 60.0f
        val rawPace = if (speedMs > 0) (1000.0 / speedMs).toInt() else 0

        return smoother.addPace(rawPace)
    }

    /**
     * Check if currently using GPS mode
     *
     * @return true if GPS data received within timeout
     */
    fun isGpsMode(): Boolean {
        val now = System.currentTimeMillis()
        return hasGps && (now - lastGpsTime) < GPS_TIMEOUT_MS
    }

    /**
     * Reset all state
     */
    fun reset() {
        hasGps = false
        lastGpsTime = 0
        smoother.reset()
        stopDetector.reset()
    }
}
