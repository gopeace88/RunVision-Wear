// app/src/test/kotlin/com/runvision/wear/engine/AdaptivePaceCalculatorTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptivePaceCalculatorTest {

    private lateinit var calculator: AdaptivePaceCalculator
    private lateinit var strideLearner: StrideLengthLearner
    private lateinit var smoother: PaceSmoother
    private lateinit var stopDetector: StopDetector

    @Before
    fun setup() {
        strideLearner = StrideLengthLearner()
        smoother = PaceSmoother(windowSize = 5)
        stopDetector = StopDetector()
        calculator = AdaptivePaceCalculator(strideLearner, smoother, stopDetector)
    }

    @Test
    fun `gps mode calculates pace from distance and time`() {
        stopDetector.updateCadence(170)
        stopDetector.updateDistanceChange()

        // 100m in 30 seconds = 3.33 m/s = 5:00/km = 300 seconds
        val pace = calculator.updateWithGpsDistance(100.0, 30.0)
        assertTrue("Expected ~300, got $pace", pace in 270..330)
    }

    @Test
    fun `gps mode returns 0 when stopped`() {
        stopDetector.updateCadence(50) // Stopped

        val pace = calculator.updateWithGpsDistance(100.0, 30.0)
        assertEquals(0, pace)
    }

    @Test
    fun `cadence mode uses stride learner`() {
        stopDetector.updateCadence(180)
        stopDetector.updateDistanceChange()

        // Cadence 180 with default stride formula:
        // stride = 0.70 + (180 - 150) * 0.005 = 0.70 + 0.15 = 0.85m
        // speed = 180 * 0.85 / 60 = 2.55 m/s
        // pace = 1000 / 2.55 = 392 seconds (6:32/km)
        val pace = calculator.updateWithCadence(180)
        assertTrue("Expected ~392, got $pace", pace in 350..450)
    }

    @Test
    fun `cadence mode returns 0 when stopped`() {
        stopDetector.updateCadence(50)

        val pace = calculator.updateWithCadence(50)
        assertEquals(0, pace)
    }

    @Test
    fun `mode switches from gps to cadence after timeout`() {
        stopDetector.updateCadence(180)
        stopDetector.updateDistanceChange()

        // First, GPS mode
        calculator.updateWithGpsDistance(100.0, 30.0)
        assertTrue(calculator.isGpsMode())

        // Simulate 6 seconds without GPS
        Thread.sleep(6000)

        calculator.updateWithCadence(180)
        assertFalse(calculator.isGpsMode())
    }

    @Test
    fun `reset clears all state`() {
        stopDetector.updateCadence(170)
        stopDetector.updateDistanceChange()
        calculator.updateWithGpsDistance(100.0, 30.0)
        calculator.reset()

        assertFalse(calculator.isGpsMode())
        assertEquals(0, smoother.getSmoothedPace())
    }
}
