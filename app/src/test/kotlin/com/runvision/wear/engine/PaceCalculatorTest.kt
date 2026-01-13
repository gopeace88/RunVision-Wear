package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * TDD tests for PaceCalculator
 *
 * Formula reference: runvision-iq/source/RunVisionIQView.mc:361-368
 * pace (min/km) = 60 / speed (km/h)
 */
class PaceCalculatorTest {

    @Test
    fun `calculate pace from speed in m per s`() {
        // 12 km/h = 3.33 m/s -> 5:00 min/km = 300 seconds
        val paceSeconds = PaceCalculator.calculatePaceSeconds(3.33f)
        assertEquals(300, paceSeconds, 5)  // 5 seconds tolerance
    }

    @Test
    fun `return 0 for zero speed`() {
        assertEquals(0, PaceCalculator.calculatePaceSeconds(0f))
    }

    @Test
    fun `return 0 for negative speed`() {
        assertEquals(0, PaceCalculator.calculatePaceSeconds(-1f))
    }

    @Test
    fun `calculate pace for typical running speed`() {
        // 10 km/h = 2.78 m/s -> 6:00 min/km = 360 seconds
        val paceSeconds = PaceCalculator.calculatePaceSeconds(2.78f)
        assertTrue("Expected pace around 360s but was $paceSeconds", paceSeconds in 355..365)
    }

    @Test
    fun `calculate pace for fast running speed`() {
        // 15 km/h = 4.17 m/s -> 4:00 min/km = 240 seconds
        val paceSeconds = PaceCalculator.calculatePaceSeconds(4.17f)
        assertTrue("Expected pace around 240s but was $paceSeconds", paceSeconds in 235..245)
    }

    @Test
    fun `calculate pace for walking speed`() {
        // 5 km/h = 1.39 m/s -> 12:00 min/km = 720 seconds
        val paceSeconds = PaceCalculator.calculatePaceSeconds(1.39f)
        assertTrue("Expected pace around 720s but was $paceSeconds", paceSeconds in 710..730)
    }

    @Test
    fun `format pace correctly for standard time`() {
        assertEquals("5:00", PaceCalculator.formatPace(300))
    }

    @Test
    fun `format pace correctly with single digit seconds`() {
        assertEquals("4:05", PaceCalculator.formatPace(245))
    }

    @Test
    fun `format pace correctly for fast pace`() {
        assertEquals("4:52", PaceCalculator.formatPace(292))
    }

    @Test
    fun `format pace returns placeholder for zero`() {
        assertEquals("--:--", PaceCalculator.formatPace(0))
    }

    @Test
    fun `format pace returns placeholder for negative`() {
        assertEquals("--:--", PaceCalculator.formatPace(-1))
    }

    @Test
    fun `very slow speed does not overflow`() {
        // 0.5 m/s = 1.8 km/h -> ~33:20 min/km = 2000 seconds
        val paceSeconds = PaceCalculator.calculatePaceSeconds(0.5f)
        assertTrue("Pace should be positive for slow speed", paceSeconds > 0)
    }

    // Helper function for tolerance comparison
    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue(
            "Expected $expected +/- $tolerance but was $actual",
            actual in (expected - tolerance)..(expected + tolerance)
        )
    }
}
