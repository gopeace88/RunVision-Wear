package com.runvision.wear.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Workout Sample Entity
 *
 * Represents a single data point collected every second during a workout.
 * Linked to WorkoutSession via sessionId (CASCADE delete).
 */
@Entity(
    tableName = "workout_samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]  // Index for faster queries
)
data class WorkoutSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: String,              // FK to WorkoutSession
    val timestamp: Long,                // epoch millis

    val heartRate: Int,                 // bpm
    val paceSecondsPerKm: Int,          // sec/km
    val cadence: Int,                   // steps per minute
    val distanceMeters: Float,          // cumulative distance

    val latitude: Double,               // GPS
    val longitude: Double               // GPS
)
