// app/src/test/kotlin/com/runvision/wear/engine/PaceSmootherTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PaceSmootherTest {

    private lateinit var smoother: PaceSmoother

    @Before
    fun setup() {
        smoother = PaceSmoother(windowSize = 5)
    }

    @Test
    fun `addPace returns same value for first input`() {
        val result = smoother.addPace(300)
        assertEquals(300, result)
    }

    @Test
    fun `addPace returns average of multiple values`() {
        smoother.addPace(300)
        smoother.addPace(320)
        val result = smoother.addPace(310)
        assertEquals(310, result) // (300+320+310)/3 = 310
    }

    @Test
    fun `addPace ignores outlier above 150 percent`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.addPace(300)
        val result = smoother.addPace(600) // 200% of avg, should be ignored
        assertEquals(300, result)
    }

    @Test
    fun `addPace ignores outlier below 50 percent`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.addPace(300)
        val result = smoother.addPace(100) // 33% of avg, should be ignored
        assertEquals(300, result)
    }

    @Test
    fun `window respects max size`() {
        repeat(10) { smoother.addPace(100) }
        val result = smoother.addPace(150) // 150% of avg is at boundary, should be accepted
        // Window: [100, 100, 100, 100, 150] -> avg = 110
        assertEquals(110, result)
    }

    @Test
    fun `reset clears buffer`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.reset()
        assertEquals(0, smoother.getSmoothedPace())
    }
}
