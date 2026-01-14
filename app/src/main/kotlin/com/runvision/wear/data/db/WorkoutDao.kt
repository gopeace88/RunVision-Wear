package com.runvision.wear.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for Workout data
 */
@Dao
interface WorkoutDao {

    // ==================== Session Operations ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSession)

    @Update
    suspend fun updateSession(session: WorkoutSession)

    @Query("SELECT * FROM workout_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): WorkoutSession?

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<WorkoutSession>

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int): List<WorkoutSession>

    @Query("DELETE FROM workout_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    // ==================== Sample Operations ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: WorkoutSample)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<WorkoutSample>)

    @Query("SELECT * FROM workout_samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSamplesForSession(sessionId: String): List<WorkoutSample>

    @Query("SELECT COUNT(*) FROM workout_samples WHERE sessionId = :sessionId")
    suspend fun getSampleCount(sessionId: String): Int

    // ==================== Cleanup Operations ====================

    /**
     * Keep only the most recent N sessions.
     * Deletes all sessions except the newest 'keepCount' sessions.
     * Samples are automatically deleted via CASCADE.
     */
    @Query("""
        DELETE FROM workout_sessions
        WHERE sessionId NOT IN (
            SELECT sessionId FROM workout_sessions
            ORDER BY startTime DESC
            LIMIT :keepCount
        )
    """)
    suspend fun keepRecentSessions(keepCount: Int = 50)

    // ==================== Statistics ====================

    @Query("SELECT COUNT(*) FROM workout_sessions")
    suspend fun getSessionCount(): Int

    @Query("SELECT COUNT(*) FROM workout_samples")
    suspend fun getTotalSampleCount(): Int

    /**
     * Calculate average heart rate for a session
     */
    @Query("SELECT AVG(heartRate) FROM workout_samples WHERE sessionId = :sessionId AND heartRate > 0")
    suspend fun getAvgHeartRate(sessionId: String): Double?

    /**
     * Calculate average pace for a session
     */
    @Query("SELECT AVG(paceSecondsPerKm) FROM workout_samples WHERE sessionId = :sessionId AND paceSecondsPerKm > 0")
    suspend fun getAvgPace(sessionId: String): Double?

    /**
     * Calculate average cadence for a session
     */
    @Query("SELECT AVG(cadence) FROM workout_samples WHERE sessionId = :sessionId AND cadence > 0")
    suspend fun getAvgCadence(sessionId: String): Double?
}
