package com.runvision.wear.health

import android.annotation.SuppressLint
import android.content.Context
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

    var onHeartRateUpdate: ((Int) -> Unit)? = null
    var onLocationUpdate: ((Double, Double, Long) -> Unit)? = null
    var onStepsUpdate: ((Int) -> Unit)? = null
    var onDistanceUpdate: ((Double) -> Unit)? = null  // Distance in meters

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

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            Log.d(TAG, "Exercise update received, state: ${update.exerciseStateInfo.state}")

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
            }

            update.latestMetrics.getData(DataType.STEPS_PER_MINUTE)?.lastOrNull()?.let {
                Log.d(TAG, "Steps per minute: ${it.value}")
                onStepsUpdate?.invoke(it.value.toInt())
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
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            Log.d(TAG, "Availability changed: $dataType -> $availability")
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
     * Start running exercise session
     */
    suspend fun startExercise() {
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
            val runningCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.RUNNING)
            Log.d(TAG, "Supported data types: ${runningCapabilities.supportedDataTypes}")

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

            Log.d(TAG, "Final data types to request: $dataTypes")

            val config = ExerciseConfig.builder(ExerciseType.RUNNING)
                .setDataTypes(dataTypes)
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(true)  // Required for LOCATION data
                .build()

            // Register callback first, then start exercise
            exerciseClient.setUpdateCallback(exerciseCallback)
            Log.d(TAG, "Update callback set")

            exerciseClient.startExerciseAsync(config).await()
            Log.d(TAG, "Exercise started successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exercise: ${e.message}", e)
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
}
