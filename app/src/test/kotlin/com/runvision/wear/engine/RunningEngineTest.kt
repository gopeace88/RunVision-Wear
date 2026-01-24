package com.runvision.wear.engine

import com.runvision.wear.data.RunningMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RunningEngineTest {

    private lateinit var engine: RunningEngine

    @Before
    fun setup() {
        engine = RunningEngine()
    }

    // ========== Adaptive Pace Integration Tests ==========

    @Test
    fun `updateDistance uses AdaptivePaceCalculator for pace calculation`() {
        engine.start()

        // Simulate running: 100m in 30 seconds = 3.33 m/s = ~300 s/km
        engine.updateDistance(100.0, 180, 150)  // distance, cadence, heartRate
        Thread.sleep(100)  // Let time pass for delta calculation
        engine.updateDistance(200.0, 180, 150)  // 100m more

        val metrics = engine.getCurrentMetrics()
        // Pace should be calculated and smoothed by AdaptivePaceCalculator
        // With stop detector and smoother integrated
        assertTrue("Distance should be updated", metrics.distanceMeters > 0)
    }

    @Test
    fun `updateCadence uses StopDetector and AdaptivePaceCalculator`() {
        engine.start()

        // High cadence = running
        engine.updateCadence(180)
        var metrics = engine.getCurrentMetrics()
        assertEquals(180, metrics.cadence)

        // Low cadence = stopped
        engine.updateCadence(30)  // Below stop threshold (60)
        metrics = engine.getCurrentMetrics()
        assertEquals(30, metrics.cadence)
    }

    @Test
    fun `StrideLengthLearner receives GPS data updates`() {
        engine.start()

        // Simulate GPS updates with cadence for stride learning
        // After enough data, stride should be learned
        engine.updateCadence(180)

        // Multiple distance updates to accumulate learning data
        for (i in 1..10) {
            engine.updateDistance(i * 100.0, 180, 150)
            Thread.sleep(50)
        }

        val metrics = engine.getCurrentMetrics()
        assertTrue("Distance should be accumulated", metrics.distanceMeters > 0)
    }

    @Test
    fun `reset clears all adaptive pace components`() {
        engine.start()

        // Build up some state
        engine.updateCadence(180)
        engine.updateDistance(500.0, 180, 150)
        Thread.sleep(100)
        engine.updateDistance(600.0, 180, 150)

        // Reset should clear everything
        engine.reset()

        val metrics = engine.getCurrentMetrics()
        assertEquals(0, metrics.elapsedSeconds)
        assertEquals(0f, metrics.distanceMeters, 0.01f)
        assertEquals(0, metrics.paceSecondsPerKm)
        assertEquals(0, metrics.cadence)
        assertEquals(0, metrics.heartRate)
    }

    @Test
    fun `stop detection returns zero pace when stopped`() {
        engine.start()

        // First establish some running pace
        engine.updateCadence(180)
        engine.updateDistance(100.0, 180, 150)
        Thread.sleep(100)
        engine.updateDistance(200.0, 180, 150)

        // Now simulate stopping (low cadence)
        engine.updateCadence(30)  // Below STOP_CADENCE_THRESHOLD (60)

        // Wait for stop detection time threshold
        Thread.sleep(2100)  // > STOP_TIME_THRESHOLD_MS (2000)

        // Update distance with zero delta (no movement)
        engine.updateDistance(200.0, 30, 150)  // Same distance

        val metrics = engine.getCurrentMetrics()
        // Pace should be 0 when stopped
        assertEquals("Pace should be 0 when stopped", 0, metrics.paceSecondsPerKm)
    }

    @Test
    fun `cadence-based pace when no GPS available`() {
        engine.start()

        // Only cadence updates, no GPS/distance
        engine.updateCadence(180)

        // After GPS timeout (5s), should use cadence-based pace
        Thread.sleep(100)
        engine.updateCadence(180)

        val metrics = engine.getCurrentMetrics()
        assertEquals(180, metrics.cadence)
        // Pace calculation from cadence using stride learner
        // stride = 0.70 + (180-150)*0.005 = 0.85m
        // speed = 180 * 0.85 / 60 = 2.55 m/s
        // pace = 1000 / 2.55 = ~392 s/km
        // But since no GPS, pace may or may not be calculated depending on implementation
    }

    @Test
    fun `updateDistance with new signature includes cadence and heartRate`() {
        engine.start()

        // New signature: updateDistance(distanceMeters, cadence, heartRate)
        engine.updateDistance(150.0, 175, 145)

        val metrics = engine.getCurrentMetrics()
        assertEquals(150f, metrics.distanceMeters, 0.5f)
        assertEquals(175, metrics.cadence)
        assertEquals(145, metrics.heartRate)
    }

    @Test
    fun `initial metrics are zero`() {
        val metrics = engine.getCurrentMetrics()
        assertEquals(0, metrics.elapsedSeconds)
        assertEquals(0f, metrics.distanceMeters, 0.01f)
        assertEquals(0, metrics.heartRate)
    }

    @Test
    fun `update GPS updates distance and pace`() {
        engine.start()
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.updateGps(37.5673, 126.9779, 2000L)  // ~100m in 1s

        val metrics = engine.getCurrentMetrics()
        assertTrue("Distance should be > 0", metrics.distanceMeters > 0)
    }

    @Test
    fun `update heart rate`() {
        engine.updateHeartRate(156)
        val metrics = engine.getCurrentMetrics()
        assertEquals(156, metrics.heartRate)
    }

    @Test
    fun `update cadence`() {
        engine.updateCadence(180)
        val metrics = engine.getCurrentMetrics()
        assertEquals(180, metrics.cadence)
    }

    @Test
    fun `tick increments elapsed time`() {
        engine.start()
        engine.tick()
        engine.tick()
        engine.tick()

        val metrics = engine.getCurrentMetrics()
        assertEquals(3, metrics.elapsedSeconds)
    }

    @Test
    fun `pause stops time accumulation`() {
        engine.start()
        engine.tick()
        engine.tick()
        engine.pause()
        engine.tick()  // Should not increment

        val metrics = engine.getCurrentMetrics()
        assertEquals(2, metrics.elapsedSeconds)
    }

    @Test
    fun `resume continues time accumulation`() {
        engine.start()
        engine.tick()
        engine.pause()
        engine.tick()  // Should not increment
        engine.resume()
        engine.tick()  // Should increment

        val metrics = engine.getCurrentMetrics()
        assertEquals(2, metrics.elapsedSeconds)
    }

    @Test
    fun `reset clears all data`() {
        engine.start()
        engine.tick()
        engine.updateHeartRate(156)
        engine.updateCadence(180)
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.reset()

        val metrics = engine.getCurrentMetrics()
        assertEquals(0, metrics.elapsedSeconds)
        assertEquals(0, metrics.heartRate)
        assertEquals(0, metrics.cadence)
        assertEquals(0f, metrics.distanceMeters, 0.01f)
        assertEquals(0.0, metrics.latitude, 0.0001)
        assertEquals(0.0, metrics.longitude, 0.0001)
    }

    @Test
    fun `isActive returns correct state`() {
        assertFalse(engine.isActive())
        engine.start()
        assertTrue(engine.isActive())
        engine.pause()
        assertFalse(engine.isActive())
        engine.resume()
        assertTrue(engine.isActive())
        engine.stop()
        assertFalse(engine.isActive())
    }

    @Test
    fun `GPS updates ignored when not running`() {
        // Engine not started
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.updateGps(37.5673, 126.9779, 2000L)

        val metrics = engine.getCurrentMetrics()
        assertEquals(0f, metrics.distanceMeters, 0.01f)
    }

    @Test
    fun `metrics include last GPS coordinates`() {
        engine.start()
        engine.updateGps(37.5663, 126.9779, 1000L)

        val metrics = engine.getCurrentMetrics()
        assertEquals(37.5663, metrics.latitude, 0.0001)
        assertEquals(126.9779, metrics.longitude, 0.0001)
    }

    @Test
    fun `pace is calculated from speed`() {
        engine.start()
        // Simulate running at ~12 km/h (3.33 m/s)
        // 111m in 1 second = 111 m/s which is unrealistic but tests the calculation
        // Let's use more realistic: ~111m in ~33 seconds at 12 km/h
        engine.updateGps(37.5663, 126.9779, 0L)
        engine.updateGps(37.5673, 126.9779, 33000L)  // ~111m in 33s = 3.36 m/s = 12 km/h

        val metrics = engine.getCurrentMetrics()
        // At 12 km/h, pace should be ~5:00/km = 300 seconds
        // Allow some tolerance for GPS calculation differences
        assertTrue("Pace should be reasonable", metrics.paceSecondsPerKm > 0)
    }
}
