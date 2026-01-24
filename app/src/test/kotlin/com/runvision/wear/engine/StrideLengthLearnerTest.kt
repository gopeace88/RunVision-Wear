// app/src/test/kotlin/com/runvision/wear/engine/StrideLengthLearnerTest.kt
package com.runvision.wear.engine

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StrideLengthLearnerTest {

    private lateinit var learner: StrideLengthLearner

    @Before
    fun setup() {
        // Use null context for basic tests (no persistence)
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

    // ========== Persistence Tests ==========

    @Test
    fun `saveToStorage saves stride to SharedPreferences`() {
        val mockEditor = mockk<SharedPreferences.Editor> {
            every { putFloat(any(), any()) } returns this
            every { apply() } returns Unit
        }
        val mockPrefs = mockk<SharedPreferences> {
            every { edit() } returns mockEditor
            every { contains(any()) } returns false
        }
        val mockContext = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockPrefs
        }

        val learnerWithContext = StrideLengthLearner(mockContext)

        // Learn stride (triggers save)
        learnerWithContext.updateWithGpsData(600.0, 750)

        verify { mockEditor.putFloat("learned_stride_length", 0.80f) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `loadFromStorage loads stride from SharedPreferences`() {
        val mockPrefs = mockk<SharedPreferences> {
            every { contains("learned_stride_length") } returns true
            every { getFloat("learned_stride_length", any()) } returns 0.85f
        }
        val mockContext = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockPrefs
        }

        val learnerWithContext = StrideLengthLearner(mockContext)

        // Should have loaded stride from storage
        assertTrue(learnerWithContext.hasLearnedStride())
        assertEquals(0.85f, learnerWithContext.getStrideLength(180), 0.01f)
    }

    @Test
    fun `loadFromStorage does nothing when no saved data`() {
        val mockPrefs = mockk<SharedPreferences> {
            every { contains("learned_stride_length") } returns false
        }
        val mockContext = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockPrefs
        }

        val learnerWithContext = StrideLengthLearner(mockContext)

        // Should not have learned stride
        assertFalse(learnerWithContext.hasLearnedStride())
        // Should use default calculation
        assertEquals(0.85f, learnerWithContext.getStrideLength(180), 0.01f)
    }

    @Test
    fun `clearStorage removes stride from SharedPreferences`() {
        val mockEditor = mockk<SharedPreferences.Editor> {
            every { remove(any()) } returns this
            every { apply() } returns Unit
        }
        val mockPrefs = mockk<SharedPreferences> {
            every { edit() } returns mockEditor
            every { contains(any()) } returns false
        }
        val mockContext = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockPrefs
        }

        val learnerWithContext = StrideLengthLearner(mockContext)
        learnerWithContext.clearStorage()

        verify { mockEditor.remove("learned_stride_length") }
        verify { mockEditor.apply() }
    }

    @Test
    fun `null context disables persistence gracefully`() {
        val learnerNoContext = StrideLengthLearner(null)

        // Should work without errors
        learnerNoContext.updateWithGpsData(600.0, 750)
        assertTrue(learnerNoContext.hasLearnedStride())

        // Save/load should be no-ops
        learnerNoContext.saveToStorage()
        learnerNoContext.loadFromStorage()
        learnerNoContext.clearStorage()

        // Still has in-memory data
        assertTrue(learnerNoContext.hasLearnedStride())
    }
}
