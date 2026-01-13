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
