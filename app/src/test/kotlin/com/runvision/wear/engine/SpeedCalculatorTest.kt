package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpeedCalculatorTest {

    private lateinit var calculator: SpeedCalculator

    @Before
    fun setup() {
        calculator = SpeedCalculator()
    }

    @Test
    fun `first point returns 0 speed`() {
        val speed = calculator.calculateSpeed(37.5663, 126.9779, 1000L)
        assertEquals(0f, speed, 0.01f)
    }

    @Test
    fun `calculates speed from two points`() {
        calculator.calculateSpeed(37.5663, 126.9779, 1000L)
        // Move ~100m in 10 seconds = 10 m/s
        val speed = calculator.calculateSpeed(37.5673, 126.9779, 11000L)
        assertTrue("Speed should be ~10 m/s but was $speed", speed in 5f..15f)
    }

    @Test
    fun `smooths speed with moving average`() {
        // Add several points to fill the buffer
        // Each point ~10m apart at 1 second intervals = ~10 m/s base speed
        calculator.calculateSpeed(37.566300, 126.9779, 1000L)
        calculator.calculateSpeed(37.566390, 126.9779, 2000L)  // ~10m in 1s
        calculator.calculateSpeed(37.566480, 126.9779, 3000L)  // ~10m in 1s
        calculator.calculateSpeed(37.566570, 126.9779, 4000L)  // ~10m in 1s
        val smoothedSpeed = calculator.calculateSpeed(37.566660, 126.9779, 5000L)

        // Should be averaged around 10 m/s
        assertTrue("Smoothed speed should be around 10 m/s but was $smoothedSpeed", smoothedSpeed in 5f..15f)
    }

    @Test
    fun `reset clears speed buffer`() {
        calculator.calculateSpeed(37.5663, 126.9779, 1000L)
        calculator.calculateSpeed(37.5673, 126.9779, 2000L)
        calculator.reset()

        val speed = calculator.calculateSpeed(37.5683, 126.9779, 3000L)
        assertEquals(0f, speed, 0.01f)
    }
}
