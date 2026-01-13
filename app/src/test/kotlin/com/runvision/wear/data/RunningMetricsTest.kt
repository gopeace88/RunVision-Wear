package com.runvision.wear.data

import org.junit.Assert.*
import org.junit.Test

class RunningMetricsTest {

    @Test
    fun `format elapsed time as mm-ss`() {
        val metrics = RunningMetrics(
            elapsedSeconds = 125,
            distanceMeters = 0f,
            paceSecondsPerKm = 0,
            heartRate = 0,
            cadence = 0,
            latitude = 0.0,
            longitude = 0.0
        )
        assertEquals("2:05", metrics.elapsedFormatted)
    }

    @Test
    fun `format pace as mm-ss per km`() {
        val metrics = RunningMetrics(
            elapsedSeconds = 0,
            distanceMeters = 0f,
            paceSecondsPerKm = 292,  // 4:52
            heartRate = 0,
            cadence = 0,
            latitude = 0.0,
            longitude = 0.0
        )
        assertEquals("4:52", metrics.paceFormatted)
    }

    @Test
    fun `format distance as km with one decimal`() {
        val metrics = RunningMetrics(
            elapsedSeconds = 0,
            distanceMeters = 5230f,
            paceSecondsPerKm = 0,
            heartRate = 0,
            cadence = 0,
            latitude = 0.0,
            longitude = 0.0
        )
        assertEquals("5.2", metrics.distanceKmFormatted)
    }
}
