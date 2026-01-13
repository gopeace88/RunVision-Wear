package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DistanceCalculatorTest {

    private lateinit var calculator: DistanceCalculator

    @Before
    fun setup() {
        calculator = DistanceCalculator()
    }

    @Test
    fun `haversine calculates correct distance`() {
        // Seoul City Hall to Gwanghwamun: approximately 1km
        val distance = DistanceCalculator.haversineDistance(
            37.5663, 126.9779,  // Seoul City Hall
            37.5759, 126.9769   // Gwanghwamun
        )
        assertTrue("Distance should be ~1km but was $distance", distance in 900f..1200f)
    }

    @Test
    fun `first point returns 0 distance`() {
        val distance = calculator.addPoint(37.5663, 126.9779)
        assertEquals(0f, distance, 0.01f)
    }

    @Test
    fun `accumulates distance correctly`() {
        calculator.addPoint(37.5663, 126.9779)  // Point 1
        calculator.addPoint(37.5673, 126.9779)  // Point 2 (~100m north)
        val total = calculator.addPoint(37.5683, 126.9779)  // Point 3 (~200m total)

        assertTrue("Total should be ~200m but was $total", total in 150f..250f)
    }

    @Test
    fun `filters out noise below threshold`() {
        calculator.addPoint(37.5663, 126.9779)
        // Very small movement (< 0.5m) should be ignored
        val distance = calculator.addPoint(37.56630001, 126.97790001)
        assertEquals(0f, distance, 0.01f)
    }

    @Test
    fun `reset clears accumulated distance`() {
        calculator.addPoint(37.5663, 126.9779)
        calculator.addPoint(37.5673, 126.9779)
        calculator.reset()

        val distance = calculator.addPoint(37.5683, 126.9779)
        assertEquals(0f, distance, 0.01f)
    }
}
