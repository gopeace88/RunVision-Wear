# Room DB 운동 데이터 로컬 저장 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 워치에서 운동 중 수집되는 모든 샘플 데이터를 Room DB에 저장하여 나중에 폰앱이 가져갈 수 있는 기반 마련

**Architecture:** Health Services로 수집된 데이터가 ExerciseService에서 1) StateFlow로 UI 전송, 2) BLE로 rLens HUD 전송, 3) Room DB로 로컬 저장 - 세 갈래로 분기. 최대 50개 세션 유지, CASCADE 삭제.

**Tech Stack:** Kotlin, Room 2.6.1, KSP 1.9.20-1.0.14, Wear OS

---

## Task 1: Room 의존성 추가

**Files:**
- Modify: `build.gradle.kts` (project-level)
- Modify: `app/build.gradle.kts`

**Step 1: project-level build.gradle.kts에 KSP 플러그인 추가**

```kotlin
// runvision-wear/build.gradle.kts
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}
```

**Step 2: app/build.gradle.kts에 KSP 플러그인 적용**

plugins 블록에 추가:
```kotlin
id("com.google.devtools.ksp")
```

**Step 3: app/build.gradle.kts에 Room 의존성 추가**

dependencies 블록에 추가:
```kotlin
// Room Database (local storage)
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")
```

**Step 4: Gradle Sync 확인**

Run: `./gradlew --refresh-dependencies`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "build: add Room DB and KSP dependencies"
```

---

## Task 2: WorkoutSession Entity 생성

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSession.kt`

**Step 1: WorkoutSession.kt 파일 생성**

```kotlin
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
```

**Step 2: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSession.kt
git commit -m "feat: add WorkoutSession entity"
```

---

## Task 3: WorkoutSample Entity 생성

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSample.kt`

**Step 1: WorkoutSample.kt 파일 생성**

```kotlin
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
    indices = [Index(value = ["sessionId"])]
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
```

**Step 2: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSample.kt
git commit -m "feat: add WorkoutSample entity with CASCADE delete"
```

---

## Task 4: WorkoutDao 인터페이스 생성

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/data/db/WorkoutDao.kt`

**Step 1: WorkoutDao.kt 파일 생성**

```kotlin
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

    @Query("SELECT AVG(heartRate) FROM workout_samples WHERE sessionId = :sessionId AND heartRate > 0")
    suspend fun getAvgHeartRate(sessionId: String): Double?

    @Query("SELECT AVG(paceSecondsPerKm) FROM workout_samples WHERE sessionId = :sessionId AND paceSecondsPerKm > 0")
    suspend fun getAvgPace(sessionId: String): Double?

    @Query("SELECT AVG(cadence) FROM workout_samples WHERE sessionId = :sessionId AND cadence > 0")
    suspend fun getAvgCadence(sessionId: String): Double?
}
```

**Step 2: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/data/db/WorkoutDao.kt
git commit -m "feat: add WorkoutDao with CRUD and cleanup operations"
```

---

## Task 5: WorkoutDatabase 클래스 생성

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/data/db/WorkoutDatabase.kt`

**Step 1: WorkoutDatabase.kt 파일 생성**

```kotlin
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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/data/db/WorkoutDatabase.kt
git commit -m "feat: add WorkoutDatabase singleton"
```

---

## Task 6: ExerciseService에 Room DB 통합

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt`

**Step 1: imports 추가**

파일 상단에 추가:
```kotlin
import com.runvision.wear.data.db.WorkoutDatabase
import com.runvision.wear.data.db.WorkoutDao
import com.runvision.wear.data.db.WorkoutSession
import com.runvision.wear.data.db.WorkoutSample
import java.util.UUID
```

**Step 2: 필드 추가**

클래스 멤버 변수에 추가:
```kotlin
// Room Database
private lateinit var workoutDatabase: WorkoutDatabase
private lateinit var workoutDao: WorkoutDao
private var currentSessionId: String? = null
```

**Step 3: onCreate()에서 DB 초기화**

`runningEngine = RunningEngine()` 다음에 추가:
```kotlin
// Initialize Room Database
workoutDatabase = WorkoutDatabase.getInstance(this)
workoutDao = workoutDatabase.workoutDao()
```

**Step 4: startExercise()에서 세션 생성**

`_isRunning.value = true` 다음에 추가:
```kotlin
// Create new workout session in DB
currentSessionId = UUID.randomUUID().toString()
serviceScope.launch(Dispatchers.IO) {
    workoutDao.insertSession(
        WorkoutSession(
            sessionId = currentSessionId!!,
            startTime = System.currentTimeMillis()
        )
    )
    Log.d(TAG, "Created workout session: $currentSessionId")
}
```

**Step 5: startTimer() 루프에서 샘플 저장**

rLens 전송 후, notification 업데이트 전에 추가:
```kotlin
// Save sample to DB (fire-and-forget, IO thread)
currentSessionId?.let { sessionId ->
    launch(Dispatchers.IO) {
        workoutDao.insertSample(
            WorkoutSample(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                heartRate = currentMetrics.heartRate,
                paceSecondsPerKm = currentMetrics.paceSecondsPerKm,
                cadence = currentMetrics.cadence,
                distanceMeters = currentMetrics.distanceMeters,
                latitude = currentMetrics.latitude,
                longitude = currentMetrics.longitude
            )
        )
    }
}
```

**Step 6: stopExercise()에서 세션 업데이트 및 정리**

`timerJob?.cancel()` 다음에 추가:
```kotlin
// Update session with summary stats and cleanup
currentSessionId?.let { sessionId ->
    serviceScope.launch(Dispatchers.IO) {
        try {
            // Calculate averages from samples
            val avgHr = workoutDao.getAvgHeartRate(sessionId)?.toInt() ?: 0
            val avgPace = workoutDao.getAvgPace(sessionId)?.toInt() ?: 0
            val avgCad = workoutDao.getAvgCadence(sessionId)?.toInt() ?: 0
            val finalMetrics = runningEngine.getCurrentMetrics()

            // Get existing session and update it
            workoutDao.getSession(sessionId)?.let { session ->
                val updatedSession = session.copy(
                    endTime = System.currentTimeMillis(),
                    totalDistanceMeters = finalMetrics.distanceMeters,
                    totalDurationSeconds = finalMetrics.elapsedSeconds,
                    avgHeartRate = avgHr,
                    avgPaceSecondsPerKm = avgPace,
                    avgCadence = avgCad
                )
                workoutDao.updateSession(updatedSession)
                Log.d(TAG, "Updated session: $sessionId")
            }

            // Cleanup: keep only 50 most recent sessions
            workoutDao.keepRecentSessions(50)
            Log.d(TAG, "DB cleanup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update session", e)
        }
    }
}
currentSessionId = null
```

**Step 7: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt
git commit -m "feat: integrate Room DB storage in ExerciseService"
```

---

## Task 7: 빌드 및 설치 테스트

**Step 1: 빌드 확인**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: 워치에 설치**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: Success

**Step 3: 기능 테스트**

1. 앱 실행 → START 버튼
2. 30초 운동 → STOP 버튼
3. DB 파일 확인:

Run: `adb shell "run-as com.runvision.wear ls -la databases/"`
Expected: `workout_database` 파일 존재

**Step 4: 최종 Commit**

```bash
git add -A
git commit -m "test: verify Room DB integration working"
```

---

## Verification Checklist

- [ ] `./gradlew assembleDebug` 성공
- [ ] 앱 설치 및 실행 정상
- [ ] 운동 시작 시 세션 생성 로그
- [ ] 1초마다 샘플 저장 (로그 없음 - fire-and-forget)
- [ ] 운동 종료 시 세션 업데이트 로그
- [ ] DB 파일 생성 확인
