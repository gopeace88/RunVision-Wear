// app/src/test/kotlin/com/runvision/wear/engine/StopDetectorTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StopDetectorTest {

    private lateinit var detector: StopDetector

    @Before
    fun setup() {
        detector = StopDetector()
    }

    @Test
    fun `initially not stopped`() {
        assertFalse(detector.isStopped())
    }

    @Test
    fun `low cadence triggers stop`() {
        detector.updateCadence(50) // Below 60 threshold
        assertTrue(detector.isStopped())
    }

    @Test
    fun `normal cadence does not trigger stop`() {
        detector.updateCadence(170)
        detector.updateDistanceChange()
        assertFalse(detector.isStopped())
    }

    @Test
    fun `no distance change for 2 seconds triggers stop`() {
        detector.updateCadence(170)
        detector.updateDistanceChange()

        // Simulate 2.5 seconds passing
        Thread.sleep(2500)

        assertTrue(detector.isStopped())
    }

    @Test
    fun `reset clears state`() {
        detector.updateCadence(50)
        assertTrue(detector.isStopped())

        detector.reset()
        detector.updateCadence(170)
        detector.updateDistanceChange()
        assertFalse(detector.isStopped())
    }
}
