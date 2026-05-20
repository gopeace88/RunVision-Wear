package com.runvision.wear.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Step-response sanity check for the two-state loop math.
 *
 * We don't try to replicate the full Garmin algorithm dynamics here — just
 * pin a few invariants that protect against an accidental spec divergence:
 *  1. With ΔH = 0 (baro and reference agree), the loop output equals hB
 *     and integrators don't drift away from zero.
 *  2. With a sustained constant ΔH > 0, X1 moves toward ΔH, X2 grows,
 *     and U evolves with the spec sign convention.
 *  3. The mode never falls into FINE in one step when ΔH greatly exceeds
 *     ALPHA·σ_REF, regardless of starting state.
 */
class AltitudeProviderStepTest {

    @Test
    fun `no error keeps state near zero and output equals hB`() {
        var s = AltitudeState()
        val hB = 110.0
        repeat(20) {
            val (next, out) = AltitudeProvider.step(s, hB, hB, 5.0, dt = 1.0)
            s = next
            // Output rides hB exactly when there's no error and U starts at 0.
            assertEquals(hB, out, 1e-9)
        }
        assertEquals(0.0, s.x1, 1e-9)
        assertEquals(0.0, s.x2, 1e-9)
        assertEquals(0.0, s.u, 1e-9)
    }

    @Test
    fun `positive deltaH drives X1 toward deltaH`() {
        // hB above hRef by 10 m. Expect X1 to chase deltaH (=10).
        // Use dt=0.1 so the discrete X1 += dt*(ΔH-X1) update is below
        // critical step size — otherwise dt=1 overshoots to ΔH in one tick.
        var s = AltitudeState()
        val hB = 120.0
        val hRef = 110.0
        repeat(5) {
            val (next, _) = AltitudeProvider.step(s, hB, hRef, sigmaRef = 5.0, dt = 0.1)
            s = next
        }
        // X1 should be strictly between 0 and ΔH=10, chasing the target.
        assertTrue("X1 should be positive but < ΔH, got ${s.x1}", s.x1 > 0.0 && s.x1 < 10.0)
        assertNotEquals(0.0, s.u, 1e-6)
    }

    @Test
    fun `large error keeps the loop in COARSE mode`() {
        // Start in FINE then hit a huge error → must flip back to COARSE.
        var s = AltitudeState(mode = AltitudeState.MODE_FINE)
        val (next, _) = AltitudeProvider.step(s, hB = 200.0, hRef = 100.0, sigmaRef = 5.0, dt = 1.0)
        assertEquals(AltitudeState.MODE_COARSE, next.mode)
    }

    @Test
    fun `null reference leaves state untouched and emits hB plus U`() {
        val s = AltitudeState(x1 = 1.0, x2 = 2.0, u = 3.5)
        val (next, out) = AltitudeProvider.step(s, hB = 100.0, hRef = null, sigmaRef = null, dt = 1.0)
        // No learning, no mutation.
        assertEquals(s, next)
        assertEquals(103.5, out, 1e-9)
    }

    @Test
    fun `small error inside ALPHA settles into FINE from COARSE`() {
        // Start COARSE with no offset history, feed a small ΔH (< ALPHA·σ).
        var s = AltitudeState(mode = AltitudeState.MODE_COARSE)
        val (next, _) = AltitudeProvider.step(s, hB = 102.0, hRef = 100.0, sigmaRef = 5.0, dt = 1.0)
        // |U + ΔH| = |0 + 2| = 2 < ALPHA(3)·σ(5) = 15 → should switch to FINE.
        assertEquals(AltitudeState.MODE_FINE, next.mode)
    }
}
