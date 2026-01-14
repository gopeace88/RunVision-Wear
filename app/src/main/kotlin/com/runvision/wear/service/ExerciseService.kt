package com.runvision.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.LocusIdCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.runvision.wear.MainActivity
import com.runvision.wear.R
import com.runvision.wear.ble.RLensConnection
import com.runvision.wear.ble.RLensScanner
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.data.db.WorkoutDatabase
import com.runvision.wear.data.db.WorkoutDao
import com.runvision.wear.data.db.WorkoutSession
import com.runvision.wear.data.db.WorkoutSample
import com.runvision.wear.engine.RunningEngine
import com.runvision.wear.health.ExerciseManager
import kotlinx.coroutines.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service for running exercise
 *
 * Keeps running even when screen is off or app is in background.
 * Manages:
 * - Health Services exercise session
 * - BLE connection to rLens
 * - Metrics updates (1Hz timer)
 */
class ExerciseService : Service() {

    companion object {
        private const val TAG = "ExerciseService"
        private const val NOTIFICATION_ID = 1
        // Changed channel ID to force new channel with correct priority
        private const val CHANNEL_ID = "exercise_channel_v2"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var runningEngine: RunningEngine
    private lateinit var exerciseManager: ExerciseManager

    // Room Database
    private lateinit var workoutDatabase: WorkoutDatabase
    private lateinit var workoutDao: WorkoutDao
    private var currentSessionId: String? = null

    // BLE 컴포넌트 - Service에서 직접 관리 (Activity lifecycle과 분리)
    private var rLensScanner: RLensScanner? = null
    private var rLensConnection: RLensConnection? = null

    // BLE 연결 상태 (UI 관찰용)
    private val _connectionState = MutableStateFlow(RLensConnection.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RLensConnection.ConnectionState> = _connectionState

    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Notification components - reuse for consistent Ongoing Activity
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notificationPendingIntent: PendingIntent? = null

    // Observable state for UI
    private val _metrics = MutableStateFlow(RunningMetrics())
    val metrics: StateFlow<RunningMetrics> = _metrics

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseService = this@ExerciseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ExerciseService created")

        runningEngine = RunningEngine()

        // Initialize Room Database
        workoutDatabase = WorkoutDatabase.getInstance(this)
        workoutDao = workoutDatabase.workoutDao()

        exerciseManager = ExerciseManager(this).apply {
            onHeartRateUpdate = { hr ->
                Log.d(TAG, "HR update: $hr")
                runningEngine.updateHeartRate(hr)
                // Update metrics immediately for UI
                _metrics.value = _metrics.value.copy(heartRate = hr)
            }
            onLocationUpdate = { lat, lon, timestamp ->
                Log.d(TAG, "GPS update: $lat, $lon")
                runningEngine.updateGps(lat, lon, timestamp)
            }
            onStepsUpdate = { steps ->
                Log.d(TAG, "Cadence update: $steps")
                runningEngine.updateCadence(steps)
            }
            onDistanceUpdate = { meters ->
                Log.d(TAG, "Distance update: $meters m")
                runningEngine.updateDistance(meters)
            }
        }

        createNotificationChannel()

        // BLE 초기화 - Service context로 생성하여 Activity lifecycle과 분리
        initializeBle()

        // Start immediate heart rate measurement (before exercise starts)
        serviceScope.launch {
            exerciseManager.startMeasuringHeartRate()
        }
    }

    /**
     * BLE 컴포넌트 초기화
     * Service context로 생성하여 화면 꺼져도 BLE 연결 유지
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun initializeBle() {
        Log.d(TAG, "Initializing BLE components with Service context")

        rLensScanner = RLensScanner(this) { device ->
            Log.d(TAG, "Found rLens device: ${device.name}")
            scanTimeoutJob?.cancel()  // Cancel timeout - device found
            rLensConnection?.connect(device)
        }

        rLensConnection = RLensConnection(this) { state ->
            Log.d(TAG, "BLE connection state: $state")
            _connectionState.value = state

            // 연결 완료 시 자동으로 운동 시작
            if (state == RLensConnection.ConnectionState.CONNECTED && !_isRunning.value) {
                Log.d(TAG, "Auto-starting exercise on BLE connection")
                startExercise()
            }
        }
    }

    // Scan timeout job
    private var scanTimeoutJob: Job? = null
    private val SCAN_TIMEOUT_MS = 10000L  // 10 seconds

    /**
     * BLE 스캔 시작
     * 10초 타임아웃 후 NOT_FOUND 상태로 전환
     */
    fun startScanning() {
        Log.d(TAG, "Starting BLE scan")
        _connectionState.value = RLensConnection.ConnectionState.SCANNING
        rLensScanner?.startScan()

        // Start timeout
        scanTimeoutJob?.cancel()
        scanTimeoutJob = serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value == RLensConnection.ConnectionState.SCANNING) {
                Log.w(TAG, "Scan timeout - device not found")
                rLensScanner?.stopScan()
                _connectionState.value = RLensConnection.ConnectionState.NOT_FOUND
            }
        }
    }

    /**
     * BLE 스캔 중지
     */
    fun stopScanning() {
        Log.d(TAG, "Stopping BLE scan")
        scanTimeoutJob?.cancel()
        rLensScanner?.stopScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // Acquire wake lock immediately when service starts foreground
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ExerciseService destroyed")
        serviceScope.cancel()
        timerJob?.cancel()

        // BLE 정리
        rLensScanner?.stopScan()
        rLensScanner = null
        rLensConnection?.disconnect()
        rLensConnection = null

        // Release wake lock
        releaseWakeLock()
    }

    /**
     * @deprecated BLE connection is now managed internally by Service
     * This method is kept for backwards compatibility but does nothing
     */
    @Deprecated("BLE is now managed by Service. Use startScanning() instead.")
    fun setRLensConnection(connection: RLensConnection) {
        Log.d(TAG, "setRLensConnection() called but ignored - BLE is managed by Service")
        // Do nothing - BLE is now managed internally
    }

    /**
     * Start running exercise
     */
    fun startExercise() {
        Log.d(TAG, "Starting exercise from service")

        // Ensure foreground service is started (needed for auto-start from BLE callback)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, createNotification())

        runningEngine.reset()
        runningEngine.start()
        _isRunning.value = true

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

        // Start Health Services
        serviceScope.launch {
            exerciseManager.startExercise()
        }

        // Start 1Hz timer
        startTimer()
    }

    /**
     * Pause exercise
     */
    fun pauseExercise() {
        serviceScope.launch {
            if (runningEngine.isActive()) {
                Log.d(TAG, "Pausing exercise")
                runningEngine.pause()
                exerciseManager.pauseExercise()
                _isPaused.value = true
            } else {
                Log.d(TAG, "Resuming exercise")
                runningEngine.resume()
                exerciseManager.resumeExercise()
                _isPaused.value = false
            }
        }
    }

    /**
     * Stop exercise
     */
    fun stopExercise() {
        Log.d(TAG, "Stopping exercise from service")
        val finalMetrics = runningEngine.getCurrentMetrics()
        runningEngine.stop()
        _isRunning.value = false
        _isPaused.value = false
        timerJob?.cancel()

        // Update session with summary stats and cleanup
        currentSessionId?.let { sessionId ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // Calculate averages from samples
                    val avgHr = workoutDao.getAvgHeartRate(sessionId)?.toInt() ?: 0
                    val avgPace = workoutDao.getAvgPace(sessionId)?.toInt() ?: 0
                    val avgCad = workoutDao.getAvgCadence(sessionId)?.toInt() ?: 0

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
                        Log.d(TAG, "Updated session: $sessionId, duration=${finalMetrics.elapsedSeconds}s, distance=${finalMetrics.distanceMeters}m")
                    }

                    // Cleanup: keep only 50 most recent sessions
                    workoutDao.keepRecentSessions(50)
                    val sessionCount = workoutDao.getSessionCount()
                    val sampleCount = workoutDao.getTotalSampleCount()
                    Log.d(TAG, "DB stats: $sessionCount sessions, $sampleCount samples")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update session", e)
                }
            }
        }
        currentSessionId = null

        serviceScope.launch {
            exerciseManager.endExercise()
        }

        // BLE 정리
        rLensScanner?.stopScan()
        rLensConnection?.disconnect()
        _connectionState.value = RLensConnection.ConnectionState.DISCONNECTED

        // Release wake lock
        releaseWakeLock()

        // Stop foreground and stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer() {
        timerJob?.cancel()
        Log.d(TAG, "Starting timer loop")
        timerJob = serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                delay(1000)
                tickCount++

                // === DIAGNOSTIC LOGGING ===
                Log.d(TAG, "=== TICK $tickCount ===")
                Log.d(TAG, "WakeLock held: ${wakeLock?.isHeld}")

                runningEngine.tick()
                val currentMetrics = runningEngine.getCurrentMetrics()
                _metrics.value = currentMetrics

                Log.d(TAG, "Metrics: HR=${currentMetrics.heartRate}, Pace=${currentMetrics.paceSecondsPerKm}, Time=${currentMetrics.elapsedSeconds}s")

                // Send to rLens if connected
                rLensConnection?.let { conn ->
                    val connected = conn.isConnected()
                    Log.d(TAG, "rLens connected: $connected")
                    if (connected) {
                        Log.d(TAG, "Calling sendMetrics...")
                        conn.sendMetrics(currentMetrics)
                    } else {
                        Log.w(TAG, "rLens NOT connected, skipping send")
                    }
                } ?: Log.w(TAG, "rLensConnection is NULL")

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

                // Update notification with current metrics
                updateNotification(currentMetrics)
            }
            Log.w(TAG, "Timer loop EXITED! isActive=$isActive")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running Exercise",
            NotificationManager.IMPORTANCE_HIGH  // Maximum priority to prevent process kill
        ).apply {
            description = "Active running exercise"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RunVision::ExerciseWakeLock"
            ).apply {
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * Get or create the PendingIntent (reused for consistency)
     */
    private fun getNotificationPendingIntent(): PendingIntent {
        return notificationPendingIntent ?: PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).also { notificationPendingIntent = it }
    }

    /**
     * Get or create the NotificationBuilder (reused for consistent Ongoing Activity)
     */
    private fun getNotificationBuilder(): NotificationCompat.Builder {
        return notificationBuilder ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_runner)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setLocusId(LocusIdCompat("runvision_exercise"))  // Helps system recognize this as exercise
            .setContentIntent(getNotificationPendingIntent())
            .also { notificationBuilder = it }
    }

    private fun createNotification(): Notification {
        val builder = getNotificationBuilder()
            .setContentTitle("RunVision")
            .setContentText("Running...")

        val pendingIntent = getNotificationPendingIntent()

        // Create Ongoing Activity - shows on watch face for quick return
        val ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_runner)
            .setTouchIntent(pendingIntent)
            .setLocusId(LocusIdCompat("runvision_exercise"))
            .setStatus(
                Status.Builder()
                    .addTemplate("Running #time#")
                    .addPart("time", Status.StopwatchPart(System.currentTimeMillis()))
                    .build()
            )
            .build()

        ongoingActivity.apply(this)

        return builder.build()
    }

    private fun updateNotification(metrics: RunningMetrics) {
        val builder = getNotificationBuilder()
            .setContentTitle("RunVision")
            .setContentText("${formatTime(metrics.elapsedSeconds)} | ♥${metrics.heartRate}")

        val pendingIntent = getNotificationPendingIntent()

        // Update Ongoing Activity status (reuse same builder)
        val ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_runner)
            .setTouchIntent(pendingIntent)
            .setLocusId(LocusIdCompat("runvision_exercise"))
            .setStatus(
                Status.Builder()
                    .addTemplate("#time# ♥#hr#")
                    .addPart("time", Status.TextPart(formatTime(metrics.elapsedSeconds)))
                    .addPart("hr", Status.TextPart("${metrics.heartRate}"))
                    .build()
            )
            .build()

        ongoingActivity.apply(this)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}
