package com.runvision.wear.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database for storing workout sessions and samples
 */
@Database(
    entities = [WorkoutSession::class, WorkoutSample::class],
    version = 1,
    exportSchema = false
)
abstract class WorkoutDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao

    companion object {
        private const val DATABASE_NAME = "workout_database"

        @Volatile
        private var INSTANCE: WorkoutDatabase? = null

        fun getInstance(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): WorkoutDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                WorkoutDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()  // Simple migration for v1
                .build()
        }
    }
}
