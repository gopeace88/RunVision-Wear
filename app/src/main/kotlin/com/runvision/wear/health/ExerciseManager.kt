package com.runvision.wear.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import kotlinx.coroutines.guava.await

/**
 * Exercise Manager - Wraps Health Services ExerciseClient
 *
 * Handles exercise session lifecycle and data callbacks
 */
class ExerciseManager(context: Context) {

    companion object {
        private const val TAG = "ExerciseManager"
    }

    private val exerciseClient: ExerciseClient = HealthServices.getClient(context).exerciseClient

    var onHeartRateUpdate: ((Int) -> Unit)? = null
    var onLocationUpdate: ((Double, Double, Long) -> Unit)? = null
    var onStepsUpdate: ((Int) -> Unit)? = null

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            update.latestMetrics.getData(DataType.HEART_RATE_BPM)?.lastOrNull()?.let {
                onHeartRateUpdate?.invoke(it.value.toInt())
            }

            update.latestMetrics.getData(DataType.LOCATION)?.lastOrNull()?.let { location ->
                onLocationUpdate?.invoke(
                    location.value.latitude,
                    location.value.longitude,
                    System.currentTimeMillis()
                )
            }

            update.latestMetrics.getData(DataType.STEPS_PER_MINUTE)?.lastOrNull()?.let {
                onStepsUpdate?.invoke(it.value.toInt())
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
     * Start running exercise session
     */
    suspend fun startExercise() {
        try {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            val runningCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.RUNNING)

            val dataTypes = mutableSetOf<DataType<*, *>>()

            if (DataType.HEART_RATE_BPM in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.HEART_RATE_BPM)
            }
            if (DataType.LOCATION in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.LOCATION)
            }
            if (DataType.STEPS_PER_MINUTE in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.STEPS_PER_MINUTE)
            }
            if (DataType.DISTANCE_TOTAL in runningCapabilities.supportedDataTypes) {
                dataTypes.add(DataType.DISTANCE_TOTAL)
            }

            val config = ExerciseConfig.builder(ExerciseType.RUNNING)
                .setDataTypes(dataTypes)
                .setIsAutoPauseAndResumeEnabled(false)
                .build()

            exerciseClient.setUpdateCallback(exerciseCallback)
            exerciseClient.startExerciseAsync(config).await()

            Log.d(TAG, "Exercise started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exercise", e)
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
            exerciseClient.endExerciseAsync().await()
            exerciseClient.clearUpdateCallbackAsync(exerciseCallback).await()
            Log.d(TAG, "Exercise ended")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end exercise", e)
        }
    }
}
