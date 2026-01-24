// app/src/main/kotlin/com/runvision/wear/engine/PaceSmoother.kt
package com.runvision.wear.engine

import java.util.ArrayDeque

/**
 * Pace Smoother with moving average and outlier rejection
 *
 * Uses N-sample moving average to reduce noise.
 * Rejects outliers that deviate more than 50% from current average.
 */
class PaceSmoother(private val windowSize: Int = 5) {

    private val paceBuffer = ArrayDeque<Int>(windowSize)

    /**
     * Add a pace value and return the smoothed result
     *
     * @param paceSeconds Raw pace in seconds per km
     * @return Smoothed pace, or the input if buffer is empty
     */
    fun addPace(paceSeconds: Int): Int {
        if (paceSeconds <= 0) return getSmoothedPace()

        // Outlier rejection: ignore if outside ±50% of current average
        if (paceBuffer.isNotEmpty()) {
            val avg = paceBuffer.average()
            if (paceSeconds < avg * 0.5 || paceSeconds > avg * 1.5) {
                return getSmoothedPace()
            }
        }

        paceBuffer.addLast(paceSeconds)
        if (paceBuffer.size > windowSize) {
            paceBuffer.removeFirst()
        }

        return getSmoothedPace()
    }

    /**
     * Get current smoothed pace without adding new value
     */
    fun getSmoothedPace(): Int {
        if (paceBuffer.isEmpty()) return 0
        return paceBuffer.average().toInt()
    }

    /**
     * Reset the smoother, clearing all buffered values
     */
    fun reset() {
        paceBuffer.clear()
    }
}
