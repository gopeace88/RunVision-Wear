package com.runvision.wear.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Workout Session Entity
 *
 * Represents a single workout session (1 run = 1 record).
 * Summary statistics are calculated and updated when the session ends.
 */
@Entity(tableName = "workout_sessions")
data class WorkoutSession(
    @PrimaryKey
    val sessionId: String,              // UUID

    val startTime: Long,                // epoch millis
    val endTime: Long? = null,          // null = in progress

    val exerciseType: String = "RUNNING",

    // Summary statistics (calculated on session end)
    val totalDistanceMeters: Float = 0f,
    val totalDurationSeconds: Int = 0,
    val avgHeartRate: Int = 0,
    val avgPaceSecondsPerKm: Int = 0,
    val avgCadence: Int = 0
)
