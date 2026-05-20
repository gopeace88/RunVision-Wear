package com.runvision.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.runvision.wear.network.ElevationLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * Garmin US Patent 6735542 — two-state altitude feedback loop.
 *
 *   H_out = H_B + U
 *
 * where
 *   H_B = T0/L · (1 − (P/P_base)^(1/EXP))         (ISA, baro provisional)
 *   ΔH  = H_B − H_REF                              (DEM or GPS reference)
 *   X1' = (ΔH − X1) / τ                            (eq. 12)
 *   X2' = X1 / τ                                   (eq. 13)
 *   U   = −(C1·X1 + C2·X2),  C1 = 2/τ, C2 = 1/τ² (positive, outer minus → negative feedback)
 *
 * τ is mode-switched: COARSE (fast settle on startup / after a jolt) vs FINE
 * (slow steady-state). A periodic re-anchor of P_base in FINE mode soaks up
 * slow drift without nudging the observed output. State persists across
 * activity boundaries via SharedPreferences (JSON).
 *
 * The reference source is, in priority order:
 *   1. Open-Meteo Copernicus GLO-90 DEM at the current GPS cell (σ ≈ 5 m)
 *   2. GPS WGS84 altitude if Health Services reports verticalPositionErrorMeters
 *      within SIGMA_GPS_GATE
 *   3. (none) — output is just H_B + U, no learning that sample
 *
 * Reference-frame limitation: Wear OS 4 cannot use AltitudeConverter (API 34+
 * but companion offset data is gated). When the GPS fallback path is active,
 * we feed ellipsoid altitude unchanged. Korea geoid offset (~25–30 m) is
 * absorbed by the σ_v gate + ALPHA/BETA thresholds. DEM (already MSL) is the
 * primary path, so this only matters when DEM is unavailable.
 *
 * NOT thread-safe: a single SensorEventListener instance is registered on a
 * single SensorManager handler thread, plus an external pushGps() call from
 * the Health Services main-thread callback. The state is read/written under
 * `synchronized(this)`. The GPS cell mutation is rare (~1 Hz) and small.
 */
class AltitudeProvider(
    private val context: Context,
    /** Network-backed DEM client. Injectable for tests. */
    private val elevation: ElevationLookup = ElevationLookup(),
) {

    companion object {
        private const val TAG = "AltitudeProvider"
        private const val PREFS_NAME = "altitude_provider_prefs"
        private const val KEY_STATE = "altitude_state_json"

        // ISA constants
        private const val L = 0.0065            // K/m, lapse rate
        private const val T0 = 288.15           // K, sea-level standard temp
        private const val G = 9.80665           // m/s²
        private const val R_A = 287.05          // J/(kg·K)
        private val EXP = G / (R_A * L)         // ≈ 5.2558

        // Mode switching thresholds (× σ_REF)
        private const val ALPHA = 3.0
        private const val BETA  = 6.0
        // Time constants (seconds)
        private const val TAU_FINE = 300.0
        private const val TAU_COARSE = 30.0
        // GPS vertical-accuracy gate (m). Above this, GPS is not used as reference.
        private const val SIGMA_GPS_GATE = 15.0
        // DEM nominal sigma (m). GLO-90 spec is ~4 m RMS in non-mountainous terrain.
        private const val SIGMA_DEM = 5.0
        // Recalibrate P_base no more often than this in FINE mode.
        private const val RECAL_INTERVAL_MS = 600_000L
        // Refuse degenerate dt (sensor backpressure / wakeup race).
        private const val MAX_DT_S = 5.0
        private const val MIN_DT_S = 0.05

        /**
         * Pure step of the two-state loop. Exposed for unit testing.
         * Mirrors the live code path inside [onSample] except for SharedPreferences
         * persistence and the optional P_base re-anchor (which is timer-driven).
         *
         * @param s current state
         * @param hB provisional baro altitude from ISA
         * @param hRef reference altitude (DEM or GPS), null → no learning this step
         * @param sigmaRef reference sigma
         * @param dt seconds since last step
         * @return Pair(nextState, outputAltitudeMeters)
         */
        fun step(
            s: AltitudeState,
            hB: Double,
            hRef: Double?,
            sigmaRef: Double?,
            dt: Double,
        ): Pair<AltitudeState, Double> {
            // Defense: H_B sanity. NaN/Inf 또는 비현실적 범위면 propagate (sensor garbage 방어).
            if (!hB.isFinite() || kotlin.math.abs(hB) >= 12000.0) {
                return s to (s.x1.let { if (it.isFinite()) hB + s.u else 0.0 })
            }
            // H_REF sanity. GPS/DEM spike(±50000m 등) 방어. 비정상이면 ref 무시 → propagate.
            val validRef = hRef != null && sigmaRef != null && hRef.isFinite() && kotlin.math.abs(hRef) < 10000.0
            if (!validRef) {
                return s to (hB + s.u)
            }
            val deltaH = hB - hRef!!
            val totalErr = kotlin.math.abs(s.u + deltaH)
            val newMode = when {
                totalErr > BETA * sigmaRef!! -> AltitudeState.MODE_COARSE
                s.mode == AltitudeState.MODE_COARSE && totalErr < ALPHA * sigmaRef ->
                    AltitudeState.MODE_FINE
                else -> s.mode
            }
            val tau = if (newMode == AltitudeState.MODE_COARSE) TAU_COARSE else TAU_FINE
            // Patent eq.12-13: X1' = (ΔH-X1)/τ, X2' = X1/τ → 둘 다 1/τ scaling.
            // 이전 의사코드에서 /τ 빠져 effective τ=dt로 300배 빠름 → X2 폭주.
            // c1, c2 positive + outer minus → negative feedback.
            val c1 = 2.0 / tau
            val c2 = (1.0 / tau).pow(2.0)
            val rawX1 = s.x1 + (dt / tau) * (deltaH - s.x1)
            val rawX2 = s.x2 + (dt / tau) * s.x1
            val rawU = -(c1 * rawX1 + c2 * rawX2)
            // Defense: state NaN/Inf reset + U clamp (±2000m).
            val newX1 = if (rawX1.isFinite()) rawX1 else 0.0
            val newX2 = if (rawX2.isFinite()) rawX2 else 0.0
            val safeU = if (rawU.isFinite()) rawU else 0.0
            val newU = safeU.coerceIn(-2000.0, 2000.0)
            val next = s.copy(x1 = newX1, x2 = newX2, u = newU, mode = newMode)
            return next to (hB + newU)
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True if this device has a barometer. Callers degrade to GPS-only when false. */
    val hasBarometer: Boolean = pressureSensor != null

    @Volatile private var state: AltitudeState = AltitudeState.fromJson(prefs.getString(KEY_STATE, null))
    @Volatile private var lastSampleNanos: Long = 0L

    // Latest GPS hint, updated from Health Services LOCATION callback.
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var lastGpsAltWgs84: Double? = null
    @Volatile private var lastGpsVerticalSigma: Double? = null

    /** Latest fused altitude (MSL if DEM was used, ellipsoid otherwise). Null until first sample. */
    @Volatile private var lastFusedM: Double? = null
    fun currentAltitudeM(): Double? = lastFusedM

    var onAltitude: ((Double) -> Unit)? = null

    /** Optional logging hook — invoked every sample with diagnostic info. */
    var onDiagnostic: ((diag: Diag) -> Unit)? = null
    data class Diag(
        val hB: Double, val hRef: Double?, val refSource: String,
        val mode: Int, val u: Double, val pBasePa: Double, val sigma: Double?,
    )

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return
            val pressureHpa = event.values.firstOrNull()?.toDouble() ?: return
            if (!pressureHpa.isFinite() || pressureHpa < 300.0 || pressureHpa > 1100.0) return
            onSample(pressureHpa, event.timestamp)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private var fetchJob: Job? = null

    /**
     * Start sampling the barometer. Returns true if registered; false if the
     * device has no barometer (caller should fall back to GPS-only path).
     */
    fun start(): Boolean {
        if (pressureSensor == null) {
            Log.w(TAG, "No TYPE_PRESSURE sensor available — caller should use GPS altitude as-is")
            return false
        }
        sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "AltitudeProvider started (state=${state})")
        return true
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        persist()
        Log.d(TAG, "AltitudeProvider stopped (state=${state})")
    }

    /**
     * Feed a GPS sample from Health Services. lat/lon drive DEM lookup; the
     * ellipsoid altitude + vertical sigma act as a fallback reference when
     * DEM is unavailable. Pass `verticalSigma = null` if Health Services
     * doesn't expose it on this device (treated as "GPS unusable as reference").
     */
    fun pushGps(lat: Double, lon: Double, gpsAltMeters: Double?, verticalSigma: Double?) {
        lastLat = lat
        lastLon = lon
        lastGpsAltWgs84 = gpsAltMeters?.takeIf { it.isFinite() && it > -1000.0 && it < 10000.0 }
        lastGpsVerticalSigma = verticalSigma?.takeIf { it.isFinite() && it > 0.0 }
        // Pre-warm DEM cache; idempotent + dedupes per cell.
        fetchJob?.cancel()
        fetchJob = scope.launch { elevation.fetchAsync(lat, lon) }
    }

    // --- core loop --------------------------------------------------------

    private fun onSample(pressureHpa: Double, eventNanos: Long) {
        val dt = computeDt(eventNanos) ?: return

        synchronized(this) {
            val s = state
            val pPa = pressureHpa * 100.0

            // 1) Provisional baro altitude (ISA).
            val hB = T0 / L * (1.0 - (pPa / s.pBasePa).pow(1.0 / EXP))

            // 2) Pick reference.
            val ref = pickReference()
            val hRef = ref?.first
            val sigmaRef = ref?.second
            val refSource = when {
                ref == null -> "none"
                sigmaRef == SIGMA_DEM -> "dem"
                else -> "gps"
            }

            // 3) One pure step of the two-state loop.
            val (afterStep, postStepOut) = step(s, hB, hRef, sigmaRef, dt)

            // 4) Periodic re-anchor of P_base while FINE-tracking. The output
            //    H_out is unchanged across this swap by construction.
            val nowMs = System.currentTimeMillis()
            val (recalPBase, recalU, recalT) = if (
                hRef != null &&
                afterStep.mode == AltitudeState.MODE_FINE &&
                (nowMs - afterStep.tLastRecalMs) > RECAL_INTERVAL_MS
            ) {
                val hOut = postStepOut
                val base = (1.0 - hOut * L / T0)
                if (base > 0.0 && base.isFinite()) {
                    val newPBase = pPa / base.pow(EXP)
                    Triple(newPBase, 0.0, nowMs)
                } else Triple(s.pBasePa, afterStep.u, afterStep.tLastRecalMs)
            } else Triple(s.pBasePa, afterStep.u, afterStep.tLastRecalMs)

            val next = afterStep.copy(
                u = recalU, pBasePa = recalPBase, tLastRecalMs = recalT,
            )
            state = next

            val out = if (recalPBase == s.pBasePa) postStepOut else {
                // P_base just changed. Recompute H_B against the new P_base so the
                // output is continuous across the recal (it equals the pre-recal
                // value by construction of `newPBase`).
                T0 / L * (1.0 - (pPa / recalPBase).pow(1.0 / EXP)) + recalU
            }
            lastFusedM = out
            onAltitude?.invoke(out)
            onDiagnostic?.invoke(Diag(hB, hRef, refSource, next.mode, recalU, recalPBase, sigmaRef))
        }
    }

    private fun computeDt(eventNanos: Long): Double? {
        // Sensor event timestamps use elapsedRealtimeNanos on most Wear OS devices,
        // but some Galaxy models report uptimeNanos. Use the delta only — absolute
        // value is irrelevant. First sample seeds the clock.
        val last = lastSampleNanos
        lastSampleNanos = eventNanos
        if (last == 0L) return null
        val dt = (eventNanos - last) / 1e9
        if (dt < MIN_DT_S || dt > MAX_DT_S) {
            // Sensor was paused / device slept. Seed but don't integrate.
            return null
        }
        return dt
    }

    private fun pickReference(): Pair<Double, Double>? {
        val lat = lastLat
        val lon = lastLon
        if (lat != null && lon != null) {
            val dem = elevation.lookup(lat, lon)
            if (dem != null) return dem to SIGMA_DEM
        }
        val gAlt = lastGpsAltWgs84
        val gSigma = lastGpsVerticalSigma
        if (gAlt != null && gSigma != null && gSigma <= SIGMA_GPS_GATE) {
            return gAlt to gSigma
        }
        return null
    }

    private fun persist() {
        prefs.edit().putString(KEY_STATE, state.toJson()).apply()
    }
}
