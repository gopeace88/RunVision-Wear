package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CyclingEngineTest {

    private lateinit var engine: CyclingEngine

    @Before
    fun setup() {
        engine = CyclingEngine()
        engine.reset()
        engine.start()
    }

    // ---- velocity ×60 scaling: byte-mirror of runvision-iq CyclingStrategy.mc:35 ----

    @Test
    fun `scaleSpeedToVelocity matches runvision-iq golden vectors`() {
        assertEquals(1530, CyclingEngine.scaleSpeedToVelocity(25.5f))
        assertEquals(1800, CyclingEngine.scaleSpeedToVelocity(30.0f))
        assertEquals(1533, CyclingEngine.scaleSpeedToVelocity(25.55f)) // decimal preserved
        assertEquals(0, CyclingEngine.scaleSpeedToVelocity(0f))
        assertEquals(0, CyclingEngine.scaleSpeedToVelocity(-5f)) // clamp
    }

    // ---- tick ----

    @Test
    fun `tick increments only when running`() {
        engine.tick(); engine.tick()
        assertEquals(2, engine.getCurrentMetrics().elapsedSeconds)
        engine.pause()
        engine.tick()
        assertEquals(2, engine.getCurrentMetrics().elapsedSeconds)
        engine.resume()
        engine.tick()
        assertEquals(3, engine.getCurrentMetrics().elapsedSeconds)
    }

    // ---- honest metrics ----

    @Test
    fun `heart rate altitude distance flow into honest metrics`() {
        engine.updateHeartRate(150)
        engine.updateAltitude(1200.0)
        engine.updateDistance(5000.0)
        val m = engine.getCurrentMetrics()
        assertEquals(150, m.heartRate)
        assertEquals(1200, m.altitudeM)
        assertEquals(5000f, m.distanceMeters, 0.01f)
    }

    @Test
    fun `speed in km per h equals gps m per s times 3point6`() {
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.updateGps(37.5673, 126.9779, 11000L)
        val kmh = engine.getCurrentMetrics().speedKmh
        assertTrue("speedKmh ~36 but was $kmh", kmh in 18f..54f)
    }

    @Test
    fun `health services distance preferred over gps`() {
        engine.updateDistance(2000.0)
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.updateGps(37.5800, 126.9779, 2000L)
        assertEquals(2000f, engine.getCurrentMetrics().distanceMeters, 0.01f)
    }

    @Test
    fun `updates before start are ignored for gps and distance`() {
        val fresh = CyclingEngine()
        fresh.reset()
        fresh.updateGps(37.5663, 126.9779, 1000L)
        fresh.updateDistance(999.0)
        assertEquals(0f, fresh.getCurrentMetrics().distanceMeters, 0.01f)
    }

    // ---- BLE remap payload ----

    @Test
    fun `getRLensPayload remaps cycling values into RunningMetrics fields`() {
        engine.updateHeartRate(150)
        engine.updateAltitude(1200.0)
        engine.updateDistance(5000.0)
        engine.tick(); engine.tick()
        val p = engine.getRLensPayload()
        assertEquals(2, p.elapsedSeconds)
        assertEquals(5000f, p.distanceMeters, 0.01f)
        assertEquals(150, p.heartRate)
        assertEquals(1200, p.cadence)
        assertEquals(
            CyclingEngine.scaleSpeedToVelocity(engine.getCurrentMetrics().speedKmh),
            p.paceSecondsPerKm
        )
    }

    @Test
    fun `reset clears all state`() {
        engine.updateHeartRate(150); engine.updateAltitude(800.0); engine.tick()
        engine.reset()
        val m = engine.getCurrentMetrics()
        assertEquals(0, m.elapsedSeconds)
        assertEquals(0, m.heartRate)
        assertEquals(0, m.altitudeM)
        assertEquals(0f, m.distanceMeters, 0.01f)
        assertFalse(engine.isActive())
    }
}
