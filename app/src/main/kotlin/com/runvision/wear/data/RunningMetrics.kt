package com.runvision.wear.data

data class RunningMetrics(
    val elapsedSeconds: Int = 0,
    val distanceMeters: Float = 0f,
    val paceSecondsPerKm: Int = 0,
    val heartRate: Int = 0,
    val cadence: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
) {
    val elapsedFormatted: String
        get() {
            val min = elapsedSeconds / 60
            val sec = elapsedSeconds % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }

    val paceFormatted: String
        get() {
            if (paceSecondsPerKm <= 0) return "--:--"
            val min = paceSecondsPerKm / 60
            val sec = paceSecondsPerKm % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }

    val distanceKmFormatted: String
        get() = String.format("%.1f", distanceMeters / 1000f)

    val distanceKm: Float
        get() = distanceMeters / 1000f
}
