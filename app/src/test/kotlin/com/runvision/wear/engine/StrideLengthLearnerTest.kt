// app/src/test/kotlin/com/runvision/wear/engine/StrideLengthLearnerTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StrideLengthLearnerTest {

    private lateinit var learner: StrideLengthLearner

    @Before
    fun setup() {
        learner = StrideLengthLearner()
    }

    @Test
    fun `default stride for cadence 150 is 0_70m`() {
        val stride = learner.getStrideLength(150)
        assertEquals(0.70f, stride, 0.01f)
    }

    @Test
    fun `default stride for cadence 180 is 0_85m`() {
        val stride = learner.getStrideLength(180)
        assertEquals(0.85f, stride, 0.01f)
    }

    @Test
    fun `default stride for cadence 200 is 0_95m`() {
        val stride = learner.getStrideLength(200)
        assertEquals(0.95f, stride, 0.01f)
    }

    @Test
    fun `default stride clamps at low cadence`() {
        val stride = learner.getStrideLength(100)
        assertEquals(0.55f, stride, 0.01f)
    }

    @Test
    fun `learning updates stride after threshold`() {
        // Simulate 600m with 750 steps = 0.80m stride
        learner.updateWithGpsData(600.0, 750)

        val stride = learner.getStrideLength(180)
        assertEquals(0.80f, stride, 0.01f)
    }

    @Test
    fun `learning requires minimum distance`() {
        // Only 400m - not enough
        learner.updateWithGpsData(400.0, 500)

        // Should still use default
        val stride = learner.getStrideLength(180)
        assertEquals(0.85f, stride, 0.01f)
    }

    @Test
    fun `hasLearnedStride returns correct state`() {
        assertFalse(learner.hasLearnedStride())

        learner.updateWithGpsData(600.0, 750)
        assertTrue(learner.hasLearnedStride())
    }

    @Test
    fun `reset clears learned data`() {
        learner.updateWithGpsData(600.0, 750)
        assertTrue(learner.hasLearnedStride())

        learner.reset()
        assertFalse(learner.hasLearnedStride())
    }
}
