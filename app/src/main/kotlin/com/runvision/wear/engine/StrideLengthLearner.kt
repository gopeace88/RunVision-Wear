// app/src/main/kotlin/com/runvision/wear/engine/StrideLengthLearner.kt
package com.runvision.wear.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Stride Length Learner
 *
 * Learns personal stride length from GPS data.
 * Falls back to cadence-based estimation when no learned data.
 * Persists learned stride to SharedPreferences.
 *
 * Default formula: stride = 0.70 + (cadence - 150) * 0.005
 * Range: 0.55m (cadence 120) to 1.00m (cadence 210)
 */
class StrideLengthLearner(private val context: Context? = null) {

    private var learnedStrideLength: Float? = null
    private var gpsDistanceAccum: Double = 0.0
    private var stepCountAccum: Long = 0

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        loadFromStorage()
    }

    companion object {
        const val MIN_DISTANCE_FOR_LEARNING = 500.0  // meters
        const val MIN_STEPS_FOR_LEARNING = 600L
        const val DEFAULT_STRIDE_BASE = 0.70f        // meters
        const val STRIDE_CADENCE_FACTOR = 0.005f
        const val MIN_STRIDE = 0.55f
        const val MAX_STRIDE = 1.00f

        private const val PREFS_NAME = "stride_length_learner"
        private const val KEY_LEARNED_STRIDE = "learned_stride_length"
    }

    /**
     * Update with GPS distance and step count
     */
    fun updateWithGpsData(distanceDelta: Double, stepsDelta: Long) {
        if (distanceDelta <= 0 || stepsDelta <= 0) return

        gpsDistanceAccum += distanceDelta
        stepCountAccum += stepsDelta

        // Learn stride when threshold reached
        if (gpsDistanceAccum >= MIN_DISTANCE_FOR_LEARNING &&
            stepCountAccum >= MIN_STEPS_FOR_LEARNING) {
            learnedStrideLength = (gpsDistanceAccum / stepCountAccum).toFloat()
                .coerceIn(MIN_STRIDE, MAX_STRIDE)
            saveToStorage()
        }
    }

    /**
     * Get stride length for given cadence
     */
    fun getStrideLength(cadence: Int): Float {
        return learnedStrideLength ?: calculateDefaultStride(cadence)
    }

    /**
     * Check if stride has been learned from GPS data
     */
    fun hasLearnedStride(): Boolean = learnedStrideLength != null

    /**
     * Calculate default stride based on cadence
     * Formula: stride = 0.70 + (cadence - 150) * 0.005
     */
    private fun calculateDefaultStride(cadence: Int): Float {
        val adjustment = (cadence - 150) * STRIDE_CADENCE_FACTOR
        return (DEFAULT_STRIDE_BASE + adjustment).coerceIn(MIN_STRIDE, MAX_STRIDE)
    }

    /**
     * Reset all learned data
     * Note: Does NOT clear persisted storage. Call clearStorage() explicitly to remove persisted data.
     */
    fun reset() {
        learnedStrideLength = null
        gpsDistanceAccum = 0.0
        stepCountAccum = 0
    }

    /**
     * Save learned stride length to persistent storage
     */
    fun saveToStorage() {
        val stride = learnedStrideLength ?: return
        prefs?.edit()?.putFloat(KEY_LEARNED_STRIDE, stride)?.apply()
    }

    /**
     * Load learned stride length from persistent storage
     */
    fun loadFromStorage() {
        prefs?.let { sharedPrefs ->
            if (sharedPrefs.contains(KEY_LEARNED_STRIDE)) {
                learnedStrideLength = sharedPrefs.getFloat(KEY_LEARNED_STRIDE, 0f)
            }
        }
    }

    /**
     * Clear persisted storage (for testing or user-initiated reset)
     */
    fun clearStorage() {
        prefs?.edit()?.remove(KEY_LEARNED_STRIDE)?.apply()
    }
}
