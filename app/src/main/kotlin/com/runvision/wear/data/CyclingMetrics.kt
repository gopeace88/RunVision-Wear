package com.runvision.wear.data

/**
 * Honest cycling metrics for the watch screen.
 * BLE/HUD slot remapping (speed×60, altitude→cadence slot) is NOT done here —
 * that is CyclingEngine.getRLensPayload()'s job. This class is what the user sees.
 */
data class CyclingMetrics(
    val elapsedSeconds: Int = 0,
    val distanceMeters: Float = 0f,
    val speedKmh: Float = 0f,
    val heartRate: Int = 0,
    val altitudeM: Int = 0
) {
    val elapsedFormatted: String
        get() {
            val min = elapsedSeconds / 60
            val sec = elapsedSeconds % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }

    // Locale-safe 1-decimal (mirrors RunningMetrics.distanceKmFormatted approach;
    // some locales render '.' as ',' with String.format).
    val speedFormatted: String
        get() {
            val intPart = speedKmh.toInt()
            val decPart = ((speedKmh - intPart) * 10).toInt()
            return "$intPart.$decPart"
        }

    val distanceKmFormatted: String
        get() {
            val km = distanceMeters / 1000f
            val intPart = km.toInt()
            val decPart = ((km - intPart) * 10).toInt()
            return "$intPart.$decPart"
        }

    val altitudeFormatted: String
        get() = "$altitudeM"
}
