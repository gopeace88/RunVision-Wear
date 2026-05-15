package com.runvision.wear.data

import org.junit.Assert.*
import org.junit.Test

class CyclingMetricsTest {

    @Test
    fun `elapsed formatted as m colon ss`() {
        assertEquals("3:05", CyclingMetrics(elapsedSeconds = 185).elapsedFormatted)
        assertEquals("0:00", CyclingMetrics(elapsedSeconds = 0).elapsedFormatted)
    }

    @Test
    fun `speed formatted to one decimal locale-safe`() {
        assertEquals("25.5", CyclingMetrics(speedKmh = 25.55f).speedFormatted)
        assertEquals("30.0", CyclingMetrics(speedKmh = 30.0f).speedFormatted)
        assertEquals("0.0", CyclingMetrics(speedKmh = 0f).speedFormatted)
    }

    @Test
    fun `distance km formatted like running metrics`() {
        assertEquals("5.2", CyclingMetrics(distanceMeters = 5230f).distanceKmFormatted)
        assertEquals("0.0", CyclingMetrics(distanceMeters = 0f).distanceKmFormatted)
    }

    @Test
    fun `altitude formatted as integer meters`() {
        assertEquals("1200", CyclingMetrics(altitudeM = 1200).altitudeFormatted)
        assertEquals("0", CyclingMetrics(altitudeM = 0).altitudeFormatted)
    }
}
