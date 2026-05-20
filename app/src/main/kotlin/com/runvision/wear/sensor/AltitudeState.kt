package com.runvision.wear.sensor

/**
 * Persistent state for the two-state altitude feedback loop.
 *
 * Crosses activity boundaries via SharedPreferences (CSV string — Android-free
 * so unit tests can round-trip without org.json stubbing).
 * Kept tiny: 5 doubles + 1 long + 1 enum-as-int. No schema migrations.
 *
 * Algorithm reference: US Patent 6735542 (Garmin), two-state feedback loop
 * with mode-switched time constants. See AltitudeProvider for the live loop.
 */
data class AltitudeState(
    /** First integrator output: tracks the slow drift between baro and reference. */
    val x1: Double = 0.0,
    /** Second integrator output: tracks the constant offset (baseline pressure error). */
    val x2: Double = 0.0,
    /** Correction quantity applied to provisional baro altitude (m). */
    val u: Double = 0.0,
    /** Base reference pressure in Pa (recalibrated periodically in FINE mode). */
    val pBasePa: Double = 101325.0,
    /** Tracking mode: 0 = COARSE (fast, large error), 1 = FINE (slow, settled). */
    val mode: Int = MODE_COARSE,
    /** Wall-clock millis of last successful pBase recalibration. */
    val tLastRecalMs: Long = 0L,
) {
    companion object {
        const val MODE_COARSE = 0
        const val MODE_FINE = 1
        private const val MAGIC = "v1"

        /**
         * Parse a previously-serialized state. Any malformed input → defaults.
         * Format: "v1|x1|x2|u|pBasePa|mode|tLastRecalMs"
         */
        fun fromJson(s: String?): AltitudeState {
            if (s.isNullOrBlank()) return AltitudeState()
            return try {
                val parts = s.split("|")
                if (parts.size != 7 || parts[0] != MAGIC) return AltitudeState()
                AltitudeState(
                    x1 = parts[1].toDouble(),
                    x2 = parts[2].toDouble(),
                    u = parts[3].toDouble(),
                    pBasePa = parts[4].toDouble(),
                    mode = parts[5].toInt(),
                    tLastRecalMs = parts[6].toLong(),
                )
            } catch (_: Exception) {
                AltitudeState()
            }
        }
    }

    fun toJson(): String = "$MAGIC|$x1|$x2|$u|$pBasePa|$mode|$tLastRecalMs"
}
