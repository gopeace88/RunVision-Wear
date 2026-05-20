package com.runvision.wear.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AltitudeStateTest {

    @Test
    fun `default state has sea-level base pressure and coarse mode`() {
        val s = AltitudeState()
        assertEquals(101325.0, s.pBasePa, 0.0)
        assertEquals(AltitudeState.MODE_COARSE, s.mode)
        assertEquals(0.0, s.x1, 0.0)
        assertEquals(0.0, s.x2, 0.0)
        assertEquals(0.0, s.u, 0.0)
    }

    @Test
    fun `json round-trips all fields`() {
        val original = AltitudeState(
            x1 = 1.5, x2 = -0.25, u = 3.7, pBasePa = 100500.0,
            mode = AltitudeState.MODE_FINE, tLastRecalMs = 1_234_567_890L,
        )
        val restored = AltitudeState.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson handles null and garbage gracefully`() {
        assertEquals(AltitudeState(), AltitudeState.fromJson(null))
        assertEquals(AltitudeState(), AltitudeState.fromJson(""))
        assertEquals(AltitudeState(), AltitudeState.fromJson("not csv"))
        // Wrong magic / mismatched arity → defaults, not a crash.
        assertEquals(AltitudeState(), AltitudeState.fromJson("v0|1|2|3|4|5|6"))
        assertEquals(AltitudeState(), AltitudeState.fromJson("v1|1|2"))
        assertNotNull(AltitudeState.fromJson(AltitudeState().toJson()))
    }
}
