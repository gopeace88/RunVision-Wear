// app/src/main/kotlin/com/runvision/wear/engine/StopDetector.kt
package com.runvision.wear.engine

/**
 * Stop Detector using cadence and distance change
 *
 * Detects when user has stopped running based on:
 * 1. Low cadence (< 60 spm)
 * 2. No distance change for 2+ seconds
 */
class StopDetector {

    private var lastCadence: Int? = null  // null until first update
    private var lastDistanceChangeTime: Long = System.currentTimeMillis()

    companion object {
        const val STOP_CADENCE_THRESHOLD = 60      // spm
        const val STOP_TIME_THRESHOLD_MS = 2000L   // 2 seconds
    }

    /**
     * Update with latest cadence reading
     */
    fun updateCadence(cadence: Int) {
        lastCadence = cadence
    }

    /**
     * Call when distance has changed (GPS or Health Services update)
     */
    fun updateDistanceChange() {
        lastDistanceChangeTime = System.currentTimeMillis()
    }

    /**
     * Check if user is currently stopped
     *
     * @return true if stopped (low cadence OR no distance change)
     */
    fun isStopped(): Boolean {
        val now = System.currentTimeMillis()
        val cadence = lastCadence

        // If no cadence data yet, not stopped (initial state)
        if (cadence == null) {
            return false
        }

        // Condition 1: Low cadence
        val lowCadence = cadence < STOP_CADENCE_THRESHOLD

        // Condition 2: No distance change for threshold duration
        val noDistanceChange = (now - lastDistanceChangeTime) > STOP_TIME_THRESHOLD_MS

        return lowCadence || noDistanceChange
    }

    /**
     * Reset detector state
     */
    fun reset() {
        lastCadence = null
        lastDistanceChangeTime = System.currentTimeMillis()
    }
}
