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
    /** Network-backed DEM client. Injectable for tests. Default uses context for disk persist. */
    private val elevation: ElevationLookup = ElevationLookup(context),
) {

    /** Inverse-variance weighted anchor input. σ는 GPS verticalAccuracy(m).
     *  Nested at class level (not companion) so `AltitudeProvider.GpsAnchorSample` works from outside. */
    data class GpsAnchorSample(
        val altitudeMeters: Double,
        val verticalSigmaMeters: Double,
    )
    /** weightedGpsAnchor result + 첫 anchor 시점 mode hint. */
    data class GpsAnchor(
        val altitudeMeters: Double,
        val stateMode: Int,
    )

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
        // Periodic state persist throttle. Mirrors watchOS twin (30s). 운동 중 워치 sleep
        // / process kill 시 anchor 손실 방어 — stop()만 의존하지 않도록.
        private const val PERSIST_INTERVAL_MS = 30_000L
        // Refuse degenerate dt (sensor backpressure / wakeup race).
        private const val MAX_DT_S = 5.0
        private const val MIN_DT_S = 0.05
        // Initial anchor: need this many GPS samples before weighted-mean anchor is trusted.
        // Below threshold → weightedGpsAnchor returns null, caller keeps accumulating.
        const val GPS_ANCHOR_MIN_SAMPLES = 15

        /**
         * Inverse-variance weighted mean of accumulated GPS samples.
         * Returns null until [GPS_ANCHOR_MIN_SAMPLES] samples are collected
         * (cold-start GPS noise floor too high before then).
         *
         * mean = Σ(altᵢ / σᵢ²) / Σ(1 / σᵢ²)
         *
         * Lower-σ samples dominate — late high-quality fix outweighs early noisy ones.
         */
        fun weightedGpsAnchor(samples: List<GpsAnchorSample>): GpsAnchor? {
            if (samples.size < GPS_ANCHOR_MIN_SAMPLES) return null
            var sumWx = 0.0
            var sumW = 0.0
            for (s in samples) {
                if (!s.altitudeMeters.isFinite() || !s.verticalSigmaMeters.isFinite() || s.verticalSigmaMeters <= 0.0) continue
                val w = 1.0 / (s.verticalSigmaMeters * s.verticalSigmaMeters)
                sumWx += w * s.altitudeMeters
                sumW += w
            }
            if (sumW <= 0.0 || !sumW.isFinite()) return null
            return GpsAnchor(altitudeMeters = sumWx / sumW, stateMode = AltitudeState.MODE_COARSE)
        }

        /**
         * Hard-reset baseline against a freshly-acquired reference altitude.
         * Used **once per session** when the first valid reference (DEM cache hit
         * or GPS weighted anchor) is acquired — bypasses the 10-min FINE-mode
         * recal gate so the user sees a correct baseline immediately.
         *
         * Pure transform of state (mirrors the live re-anchor block in onSample).
         * X1/X2/U=0 reset, P_base computed so that ISA(pressurePa, newPBase) = referenceAltitudeMeters.
         */
        fun reanchorForInitialReference(
            state: AltitudeState,
            pressurePa: Double,
            referenceAltitudeMeters: Double,
            nowMs: Long,
        ): AltitudeState {
            val base = 1.0 - referenceAltitudeMeters * L / T0
            val newPBase = if (base > 0.0 && base.isFinite()) pressurePa / base.pow(EXP) else state.pBasePa
            return state.copy(
                x1 = 0.0,
                x2 = 0.0,
                u = 0.0,
                pBasePa = newPBase,
                mode = AltitudeState.MODE_COARSE,
                tLastRecalMs = nowMs,
            )
        }

        /** ISA baro altitude. Exposed for unit testing the re-anchor round-trip. */
        fun baroAltitudeMeters(pressurePa: Double, pBasePa: Double): Double {
            return T0 / L * (1.0 - (pressurePa / pBasePa).pow(1.0 / EXP))
        }

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
            // Defense: H_B sanity. NaN/Inf 또는 비현실적 범위(>=12000m, 예: 손상된 baseline)면
            // sensor garbage. hB + s.u 로 전파하면 NaN/Inf 또는 불가능한 고도(12000m+)가 그대로
            // fused altitude로 흘러감 → bounded sentinel(0.0) 반환, state는 학습 없이 유지.
            if (!hB.isFinite() || kotlin.math.abs(hB) >= 12000.0) {
                return s to 0.0
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
    @Volatile private var lastPersistMs: Long = 0L

    // Latest GPS hint, updated from Health Services LOCATION callback.
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var lastGpsAltWgs84: Double? = null
    @Volatile private var lastGpsVerticalSigma: Double? = null

    // GPS anchor accumulator. hasAnchoredThisSession == false 인 동안만 채워짐.
    // 첫 reference (DEM cache hit 또는 weighted GPS anchor) 확보 시 reanchor + clear + flag=true.
    // session-local flag (persisted X) — 이전 세션의 tLastRecalMs로 판정 시 새 위치에서 reanchor 못 함.
    private val anchorSamples = ArrayDeque<GpsAnchorSample>()
    private val anchorBufferCap = 20
    @Volatile private var hasAnchoredThisSession: Boolean = false

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
        // session-local anchor state reset — 이전 세션 buffer가 새 위치 sample과 섞이면
        // 잘못된 weighted altitude로 reanchor.
        synchronized(anchorSamples) { anchorSamples.clear() }
        hasAnchoredThisSession = false
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
        val cleanAlt = gpsAltMeters?.takeIf { it.isFinite() && it > -1000.0 && it < 10000.0 }
        val cleanSigma = verticalSigma?.takeIf { it.isFinite() && it > 0.0 }
        lastGpsAltWgs84 = cleanAlt
        lastGpsVerticalSigma = cleanSigma
        // Accumulate weighted-anchor samples until first reference is acquired.
        // Quality gate: σ_v ≤ SIGMA_GPS_GATE — poor indoor/cold-start GPS가 baseline 영구 skew
        // 방지. normal reference path와 동일 gate 적용.
        if (!hasAnchoredThisSession && cleanAlt != null && cleanSigma != null && cleanSigma <= SIGMA_GPS_GATE) {
            synchronized(anchorSamples) {
                anchorSamples.addLast(GpsAnchorSample(cleanAlt, cleanSigma))
                while (anchorSamples.size > anchorBufferCap) anchorSamples.removeFirst()
            }
        }
        // Pre-warm DEM cache (network OK before first anchor — user expects phone-connected
        // warm-up). After first anchor, lookup() is called with fetchOnMiss=false; no fetch.
        if (!hasAnchoredThisSession) {
            fetchJob?.cancel()
            fetchJob = scope.launch { elevation.fetchAsync(lat, lon) }
        }
    }

    // --- core loop --------------------------------------------------------

    private fun onSample(pressureHpa: Double, eventNanos: Long) {
        val dt = computeDt(eventNanos) ?: return

        synchronized(this) {
            var s = state
            val pPa = pressureHpa * 100.0
            val nowMsForAnchor = System.currentTimeMillis()

            // 0) Initial reference acquisition (one-shot per session). Bypasses
            //    the FINE+10min recal gate so the user sees a sensible baseline
            //    immediately instead of after 30+ min of slow loop convergence.
            //    Priority: DEM cache hit > weighted GPS anchor (15+ samples).
            //    Gate by session-local flag (not persisted tLastRecalMs) so a new
            //    workout at a new location can re-anchor even if old recal timestamp survives.
            if (!hasAnchoredThisSession && lastLat != null && lastLon != null) {
                val initialRef: Double? = elevation.lookup(lastLat!!, lastLon!!, fetchOnMiss = true)
                    ?: synchronized(anchorSamples) {
                        weightedGpsAnchor(anchorSamples.toList())?.altitudeMeters
                    }
                if (initialRef != null && initialRef.isFinite() && kotlin.math.abs(initialRef) < 10000.0) {
                    s = reanchorForInitialReference(s, pPa, initialRef, nowMsForAnchor)
                    state = s
                    synchronized(anchorSamples) { anchorSamples.clear() }
                    hasAnchoredThisSession = true
                    Log.d(TAG, "Initial reanchor: pBase=${s.pBasePa} refAlt=$initialRef")
                }
            }

            // 1) Provisional baro altitude (ISA).
            val hB = T0 / L * (1.0 - (pPa / s.pBasePa).pow(1.0 / EXP))

            // hB가 sensor garbage(NaN/Inf 또는 |hB|>=12000m, 예: 손상된 baseline)면 이번 sample은
            // 학습·publish를 모두 건너뜀 → 거짓 0m/극단값을 HUD·rLens로 내보내지 않고 직전 고도(lastFusedM)
            // 유지. step()의 0.0 센티넬이 valid 고도처럼 전파되던 문제 해소.
            if (!hB.isFinite() || kotlin.math.abs(hB) >= 12000.0) {
                Log.w(TAG, "Invalid hB=$hB — skipping altitude publish (holding last=$lastFusedM)")
                return@synchronized
            }

            // 2) Pick reference. After first anchor: cache-only — no network round trips.
            val ref = pickReference(cacheOnly = hasAnchoredThisSession)
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

            // Periodic persist (30s throttle) — process kill / sleep 시에도 anchor 보존.
            if (nowMs - lastPersistMs > PERSIST_INTERVAL_MS) {
                persist()
                lastPersistMs = nowMs
            }
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

    private fun pickReference(cacheOnly: Boolean = false): Pair<Double, Double>? {
        val lat = lastLat
        val lon = lastLon
        if (lat != null && lon != null) {
            val dem = elevation.lookup(lat, lon, fetchOnMiss = !cacheOnly)
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
