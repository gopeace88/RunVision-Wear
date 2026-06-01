package com.runvision.wear.health

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.*
import androidx.health.services.client.data.ExerciseTrackedStatus
import kotlinx.coroutines.guava.await

/**
 * Exercise Manager - Wraps Health Services ExerciseClient
 *
 * Uses MeasureClient for immediate heart rate before exercise starts,
 * then switches to ExerciseClient during the workout.
 */
class ExerciseManager(context: Context) {

    companion object {
        private const val TAG = "ExerciseManager"
    }

    private val healthServicesClient = HealthServices.getClient(context)
    private val exerciseClient: ExerciseClient = healthServicesClient.exerciseClient
    private val measureClient: MeasureClient = healthServicesClient.measureClient

    private var isMeasuring = false
    private var registrationRetryAttempted = false
    private val registrationRetryHandler = Handler(Looper.getMainLooper())

    var exerciseStartMs: Long = 0L
        private set
    var lastMetricElapsedMs: Long = 0L
        private set

    var onHeartRateUpdate: ((Int) -> Unit)? = null
    var onLocationUpdate: ((Double, Double, Long) -> Unit)? = null
    var onStepsUpdate: ((Int) -> Unit)? = null
    var onDistanceUpdate: ((Double) -> Unit)? = null  // Distance in meters
    var onStepsDeltaUpdate: ((Long) -> Unit)? = null  // Real step deltas for stride learning
    /**
     * GPS sample with altitude + vertical accuracy. Used by AltitudeProvider to
     * pre-warm DEM cell lookups and as a fallback reference when DEM is unavailable.
     * - `altMeters` is WGS84 ellipsoid on Wear OS 4 (no AltitudeConverter pre-API 34).
     * - `verticalSigma` is null if the SDK doesn't expose verticalPositionErrorMeters
     *   on this device — AltitudeProvider treats null as "GPS unusable as reference".
     */
    var onGpsForAltitudeUpdate: ((lat: Double, lon: Double, altMeters: Double?, verticalSigma: Double?) -> Unit)? = null

    // MeasureCallback for immediate heart rate (before exercise starts)
    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "Measure availability: $dataType -> $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let {
                Log.d(TAG, "Measure HR: ${it.value}")
                onHeartRateUpdate?.invoke(it.value.toInt())
            }
        }
    }

    private val exerciseCallback: ExerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            Log.d(TAG, "Exercise update received, state: ${update.exerciseStateInfo.state}")

            if (hasTrackedMetric(update)) {
                lastMetricElapsedMs = SystemClock.elapsedRealtime()
            }

            update.latestMetrics.getData(DataType.HEART_RATE_BPM)?.lastOrNull()?.let {
                Log.d(TAG, "Heart rate: ${it.value}")
                onHeartRateUpdate?.invoke(it.value.toInt())
            }

            update.latestMetrics.getData(DataType.LOCATION)?.lastOrNull()?.let { location ->
                Log.d(TAG, "Location: ${location.value.latitude}, ${location.value.longitude}")
                onLocationUpdate?.invoke(
                    location.value.latitude,
                    location.value.longitude,
                    System.currentTimeMillis()
                )
                // GPS sample → AltitudeProvider. ABSOLUTE_ELEVATION (Google internal
                // fusion) is intentionally NOT subscribed; we run our own US6735542
                // two-state loop in AltitudeProvider with DEM as the primary reference.
                val rawAlt = location.value.altitude
                val sanitizedAlt = if (rawAlt.isFinite() && rawAlt > -1000.0 && rawAlt < 10000.0) rawAlt else null
                val sigma = readVerticalSigma(location.value)
                onGpsForAltitudeUpdate?.invoke(
                    location.value.latitude,
                    location.value.longitude,
                    sanitizedAlt,
                    sigma,
                )
            }

            update.latestMetrics.getData(DataType.STEPS_PER_MINUTE)?.lastOrNull()?.let {
                Log.d(TAG, "Steps per minute: ${it.value}")
                onStepsUpdate?.invoke(it.value.toInt())
            }

            // STEPS delivers real measured step deltas — used for accurate stride calibration.
            // Unlike wall-clock reconstruction from cadence callbacks, these are platform-counted.
            update.latestMetrics.getData(DataType.STEPS)?.lastOrNull()?.let {
                Log.d(TAG, "Step delta: ${it.value}")
                onStepsDeltaUpdate?.invoke(it.value)
            }

            // DISTANCE_TOTAL is a cumulative metric - use Health Services' sensor-fused distance
            // This is more accurate than raw GPS (combines GPS + accelerometer + step calibration)
            update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.total?.let { distanceMeters ->
                Log.d(TAG, "Distance (Health Services): $distanceMeters m")
                onDistanceUpdate?.invoke(distanceMeters)
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            Log.d(TAG, "Lap summary received")
        }

        override fun onRegistered() {
            Log.d(TAG, "Exercise callback registered")
            registrationRetryAttempted = false
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
            if (!registrationRetryAttempted) {
                registrationRetryAttempted = true
                registrationRetryHandler.postDelayed({ retryCallbackRegistration() }, 1500L)
            }
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            Log.d(TAG, "Availability changed: $dataType -> $availability")
        }
    }

    private fun hasTrackedMetric(update: ExerciseUpdate): Boolean {
        return update.latestMetrics.getData(DataType.HEART_RATE_BPM).isNotEmpty() ||
            update.latestMetrics.getData(DataType.LOCATION).isNotEmpty() ||
            update.latestMetrics.getData(DataType.STEPS_PER_MINUTE).isNotEmpty() ||
            update.latestMetrics.getData(DataType.STEPS).isNotEmpty() ||
            update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.total != null
    }

    // Called at runtime (not during exerciseCallback construction) so referencing
    // exerciseCallback here is safe — avoids the definite-assignment error that a
    // direct self-reference inside onRegistrationFailed would cause.
    private fun retryCallbackRegistration() {
        try {
            Log.d(TAG, "Retrying exercise callback registration once")
            exerciseClient.setUpdateCallback(exerciseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exercise callback retry failed", e)
        }
    }

    /**
     * Read verticalPositionErrorMeters from a LocationData value if the SDK
     * exposes it. health-services-client adds new fields across alpha versions
     * — reflect once, cache the method, return null if unavailable.
     * Null sigma means "GPS unusable as altitude reference" downstream.
     */
    private var verticalSigmaMethod: java.lang.reflect.Method? = null
    private var verticalSigmaResolved = false
    private fun readVerticalSigma(loc: Any): Double? {
        if (!verticalSigmaResolved) {
            verticalSigmaMethod = try {
                // Kotlin property `verticalPositionErrorMeters` → JVM getter.
                loc.javaClass.getMethod("getVerticalPositionErrorMeters")
            } catch (_: NoSuchMethodException) {
                null
            }
            verticalSigmaResolved = true
            Log.d(
                TAG,
                "verticalPositionErrorMeters resolved=${verticalSigmaMethod != null} " +
                    "(null → GPS not used as altitude reference; DEM-only path)"
            )
        }
        val m = verticalSigmaMethod ?: return null
        return try {
            val v = m.invoke(loc) as? Double
            v?.takeIf { it.isFinite() && it > 0.0 }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Start immediate heart rate measurement (call when app starts)
     * This provides instant HR data before the exercise session begins
     */
    suspend fun startMeasuringHeartRate() {
        if (isMeasuring) return

        try {
            val capabilities = measureClient.getCapabilitiesAsync().await()
            if (DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure) {
                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
                isMeasuring = true
                Log.d(TAG, "Started measuring heart rate immediately")
            } else {
                Log.w(TAG, "Heart rate measurement not supported")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start heart rate measurement: ${e.message}", e)
        }
    }

    /**
     * Stop immediate heart rate measurement
     */
    suspend fun stopMeasuringHeartRate() {
        if (!isMeasuring) return

        try {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback).await()
            isMeasuring = false
            Log.d(TAG, "Stopped measuring heart rate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop heart rate measurement: ${e.message}", e)
        }
    }

    /**
     * Whether Health Services on this device supports the given exercise type.
     * Optimistic on query failure (returns true) so the runtime start gate
     * remains the backstop rather than false-disabling on a transient error.
     */
    suspend fun isExerciseTypeSupported(exerciseType: ExerciseType): Boolean {
        return try {
            exerciseType in exerciseClient.getCapabilitiesAsync().await().supportedExerciseTypes
        } catch (e: Exception) {
            Log.e(TAG, "Capability query failed for $exerciseType: ${e.message}", e)
            true
        }
    }

    /**
     * Start exercise session.
     * @param exerciseType RUNNING (default — running path byte-identical) or BIKING.
     * @return true if Health Services accepted the session start, false on failure.
     *         Running callers ignore the return value (behaviour unchanged).
     */
    suspend fun startExercise(exerciseType: ExerciseType = ExerciseType.RUNNING): Boolean {
        try {
            Log.d(TAG, "Starting exercise...")

            // Stop immediate measurement - ExerciseClient will take over
            stopMeasuringHeartRate()

            // Check current exercise state and end if exists
            val currentInfo = exerciseClient.getCurrentExerciseInfoAsync().await()
            Log.d(TAG, "Current exercise state: ${currentInfo.exerciseTrackedStatus}")

            @SuppressLint("RestrictedApi")
            if (currentInfo.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS ||
                currentInfo.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) {
                Log.d(TAG, "Ending existing exercise first...")
                try {
                    exerciseClient.endExerciseAsync().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not end existing exercise: ${e.message}")
                }
            }

            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            val runningCapabilities = capabilities.getExerciseTypeCapabilities(exerciseType)
            Log.d(TAG, "Supported data types ($exerciseType): ${runningCapabilities.supportedDataTypes}")

            val dataTypes = mutableSetOf<DataType<*, *>>()

            if (DataType.HEART_RATE_BPM in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.HEART_RATE_BPM)
                Log.d(TAG, "Adding HEART_RATE_BPM")
            }
            if (DataType.LOCATION in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.LOCATION)
                Log.d(TAG, "Adding LOCATION")
            }
            if (DataType.STEPS_PER_MINUTE in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.STEPS_PER_MINUTE)
                Log.d(TAG, "Adding STEPS_PER_MINUTE")
            }
            if (DataType.DISTANCE_TOTAL in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.DISTANCE_TOTAL)
                Log.d(TAG, "Adding DISTANCE_TOTAL")
            }
            if (DataType.STEPS in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.STEPS)
                Log.d(TAG, "Adding STEPS")
            }
            // NOTE: ABSOLUTE_ELEVATION (Google Health Services internal fusion) is
            // deliberately NOT subscribed. AltitudeProvider runs an in-app
            // US Patent 6735542 two-state feedback loop on raw barometer + DEM
            // reference instead. See sensor/AltitudeProvider.kt for details.

            Log.d(TAG, "Final data types to request: $dataTypes")

            val config = ExerciseConfig.builder(exerciseType)
                .setDataTypes(dataTypes)
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(true)  // Required for LOCATION data
                .build()

            // Register callback first, then start exercise
            registrationRetryAttempted = false
            exerciseClient.setUpdateCallback(exerciseCallback)
            Log.d(TAG, "Update callback set")

            exerciseClient.startExerciseAsync(config).await()
            exerciseStartMs = SystemClock.elapsedRealtime()
            lastMetricElapsedMs = 0L
            Log.d(TAG, "Exercise started successfully!")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exercise: ${e.message}", e)
            return false
        }
    }

    /**
     * Pause exercise
     */
    suspend fun pauseExercise() {
        try {
            exerciseClient.pauseExerciseAsync().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause exercise", e)
        }
    }

    /**
     * Resume exercise
     */
    suspend fun resumeExercise() {
        try {
            exerciseClient.resumeExerciseAsync().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume exercise", e)
        }
    }

    /**
     * End exercise session
     */
    suspend fun endExercise() {
        try {
            // Also stop measuring if it was still running
            stopMeasuringHeartRate()

            exerciseClient.endExerciseAsync().await()
            exerciseClient.clearUpdateCallbackAsync(exerciseCallback).await()
            Log.d(TAG, "Exercise ended")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end exercise", e)
        }
    }

    suspend fun getCurrentExerciseInfoAsync(): ExerciseInfo {
        return exerciseClient.getCurrentExerciseInfoAsync().await()
    }

    suspend fun reregisterCallback() {
        registrationRetryAttempted = false
        exerciseClient.setUpdateCallback(exerciseCallback)
        lastMetricElapsedMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "Exercise callback re-registered")
    }

    suspend fun restartExercise(type: ExerciseType): Boolean {
        return startExercise(type)
    }
}
