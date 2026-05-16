package com.runvision.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
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
import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.engine.CyclingEngine
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

        // MAC binding: prevents connecting to a nearby stranger's rLens
        const val PREFS_NAME = "runvision_prefs"
        const val KEY_RLENS_ADDRESS = "rlens_mac_address"

        // F2: durable mode/start command via startForegroundService Intent
        const val EXTRA_MODE = "extra_mode"          // ExerciseMode.name
        const val EXTRA_CMD = "extra_cmd"
        const val CMD_START_SCAN = "cmd_start_scan"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var runningEngine: RunningEngine
    private lateinit var cyclingEngine: CyclingEngine
    private lateinit var exerciseManager: ExerciseManager

    // @Volatile: setMode() runs on main thread; mode is read in startExercise()
    // which is auto-invoked from the BLE GATT callback (binder thread).
    @Volatile private var mode: ExerciseMode = ExerciseMode.RUNNING

    fun setMode(m: ExerciseMode) {
        Log.d(TAG, "setMode: $m")
        mode = m
    }

    /** Current active mode (read-only). Used by MainActivity to pick the screen on re-entry. */
    val activeMode: ExerciseMode get() = mode

    // Room Database
    private lateinit var workoutDatabase: WorkoutDatabase
    private lateinit var workoutDao: WorkoutDao
    private var currentSessionId: String? = null

    // BLE 컴포넌트 - Service에서 직접 관리 (Activity lifecycle과 분리)
    private var rLensScanner: RLensScanner? = null
    private var rLensConnection: RLensConnection? = null

    // 2-retry + new device registration mode
    private var scanAttemptCount = 0
    private var isInNewDeviceMode = false
    private var currentTargetAddress: String? = null

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

    private val _cyclingMetrics = MutableStateFlow(CyclingMetrics())
    val cyclingMetrics: StateFlow<CyclingMetrics> = _cyclingMetrics

    // F1: capability (false = device has no BIKING support) + runtime start failure
    private val _cyclingSupported = MutableStateFlow(true)
    val cyclingSupported: StateFlow<Boolean> = _cyclingSupported
    private val _cyclingStartFailed = MutableStateFlow(false)
    val cyclingStartFailed: StateFlow<Boolean> = _cyclingStartFailed

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

        runningEngine = RunningEngine(this)
        cyclingEngine = CyclingEngine()

        // Initialize Room Database
        workoutDatabase = WorkoutDatabase.getInstance(this)
        workoutDao = workoutDatabase.workoutDao()

        exerciseManager = ExerciseManager(this).apply {
            onHeartRateUpdate = { hr ->
                Log.d(TAG, "HR update: $hr")
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateHeartRate(hr)
                    // Immediate UI update — parity with running path's low-latency HR
                    _cyclingMetrics.value = _cyclingMetrics.value.copy(heartRate = hr)
                } else {
                    runningEngine.updateHeartRate(hr)
                    // Update metrics immediately for UI
                    _metrics.value = _metrics.value.copy(heartRate = hr)
                }
            }
            onLocationUpdate = { lat, lon, timestamp ->
                Log.d(TAG, "GPS update: $lat, $lon")
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateGps(lat, lon, timestamp)
                } else {
                    runningEngine.updateGps(lat, lon, timestamp)
                }
            }
            onStepsUpdate = { steps ->
                Log.d(TAG, "Cadence update: $steps")
                if (mode != ExerciseMode.CYCLING) {
                    runningEngine.updateCadence(steps)
                }
            }
            onDistanceUpdate = { meters ->
                Log.d(TAG, "Distance update: $meters m")
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateDistance(meters)
                } else {
                    runningEngine.updateDistance(meters)
                }
            }
            onStepsDeltaUpdate = { steps ->
                Log.d(TAG, "Step delta: $steps")
                if (mode != ExerciseMode.CYCLING) {
                    runningEngine.updateStepsDelta(steps)
                }
            }
            onAltitudeUpdate = { meters ->
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateAltitude(meters)
                }
            }
        }

        createNotificationChannel()

        // BLE 초기화 - Service context로 생성하여 Activity lifecycle과 분리
        initializeBle()

        // Start immediate heart rate measurement (before exercise starts)
        serviceScope.launch {
            exerciseManager.startMeasuringHeartRate()
        }

        // F1 pre-flight: query once whether this device supports BIKING.
        // Default stays true until known, so the runtime gate is the backstop.
        serviceScope.launch {
            _cyclingSupported.value =
                exerciseManager.isExerciseTypeSupported(ExerciseMode.CYCLING.toExerciseType())
        }
    }

    /**
     * BLE 컴포넌트 초기화
     * Service context로 생성하여 화면 꺼져도 BLE 연결 유지
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun createRLensScanner(targetAddress: String?): RLensScanner {
        currentTargetAddress = targetAddress
        val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return RLensScanner(
            context = this,
            targetAddress = targetAddress,
            onDeviceFound = { device ->
                Log.d(TAG, "Found rLens device: ${device.name} (${device.address})")
                if (targetAddress == null) {
                    // First run or new device mode: save the discovered device
                    prefs.edit().putString(KEY_RLENS_ADDRESS, device.address).apply()
                    Log.d(TAG, "Registered rLens: ${device.address}")
                }
                scanTimeoutJob?.cancel()
                scanAttemptCount = 0
                isInNewDeviceMode = false
                rLensConnection?.connect(device)
            }
        )
    }

    private fun initializeBle() {
        Log.d(TAG, "Initializing BLE components with Service context")

        val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val savedAddress = prefs.getString(KEY_RLENS_ADDRESS, null)
        Log.d(TAG, "Saved rLens address: ${savedAddress ?: "none (first run)"}")

        rLensScanner = createRLensScanner(savedAddress)

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
    private val SCAN_TIMEOUT_MS = 3000L  // BLE 광고 주기 100~250ms, 3초면 충분

    /**
     * BLE 스캔 시작
     *
     * 재시도 전략:
     * - 저장된 기기: 3초×2 → 미발견 시 새 기기 등록 모드
     * - 새 기기 등록 모드: 3초×2 → 미발견 시 NOT_FOUND
     * - 최초 실행(저장 기기 없음): 3초×2 → 미발견 시 NOT_FOUND
     */
    fun startScanning() {
        val attempt = scanAttemptCount + 1
        val mode = if (isInNewDeviceMode) "new-device" else "saved(${currentTargetAddress ?: "any"})"
        Log.d(TAG, "BLE scan start — attempt $attempt, mode=$mode")
        _connectionState.value = RLensConnection.ConnectionState.SCANNING
        rLensScanner?.startScan()

        scanTimeoutJob?.cancel()
        scanTimeoutJob = serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value != RLensConnection.ConnectionState.SCANNING) return@launch

            rLensScanner?.stopScan()
            scanAttemptCount++
            Log.w(TAG, "Scan timeout (attempt $scanAttemptCount)")

            val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

            when {
                // 저장된 기기 2회 실패 → 새 기기 등록 모드 진입
                !isInNewDeviceMode && currentTargetAddress != null && scanAttemptCount >= 2 -> {
                    Log.w(TAG, "Saved device not found — entering new device registration mode")
                    prefs.edit().remove(KEY_RLENS_ADDRESS).apply()
                    isInNewDeviceMode = true
                    scanAttemptCount = 0
                    rLensScanner = createRLensScanner(null)
                    startScanning()
                }
                // 새 기기 등록 모드 2회 실패 → NOT_FOUND
                isInNewDeviceMode && scanAttemptCount >= 2 -> {
                    Log.w(TAG, "New device not found after 2 attempts")
                    scanAttemptCount = 0
                    isInNewDeviceMode = false
                    _connectionState.value = RLensConnection.ConnectionState.NOT_FOUND
                }
                // 그 외 (1차 실패, 또는 최초 실행 1차 실패) → 재시도
                else -> {
                    Log.d(TAG, "Retrying scan...")
                    startScanning()
                }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // F2: durable mode/start delivered via Intent (survives the pre-bind window).
        // Absent extras (running path & every existing caller) → no-op → byte-identical.
        intent?.getStringExtra(EXTRA_MODE)?.let { setMode(parseExerciseMode(it)) }
        if (intent?.getStringExtra(EXTRA_CMD) == CMD_START_SCAN &&
            (_connectionState.value == RLensConnection.ConnectionState.DISCONNECTED ||
                _connectionState.value == RLensConnection.ConnectionState.NOT_FOUND)) {
            Log.d(TAG, "onStartCommand: CMD_START_SCAN")
            startScanning()
        }

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
    @Suppress("UNUSED_PARAMETER")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (mode == ExerciseMode.CYCLING) {
            // F1: gate session/UI/timer on a successful Health Services BIKING start.
            _cyclingStartFailed.value = false
            serviceScope.launch {
                // Cycling branch is BIKING by definition — use the constant, not the
                // @Volatile `mode`, so a concurrent setMode() can't race the HS start.
                val ok = exerciseManager.startExercise(ExerciseMode.CYCLING.toExerciseType())
                if (ok) {
                    cyclingEngine.reset()
                    cyclingEngine.start()
                    _isRunning.value = true
                    currentSessionId = UUID.randomUUID().toString()
                    serviceScope.launch(Dispatchers.IO) {
                        workoutDao.insertSession(
                            WorkoutSession(
                                sessionId = currentSessionId!!,
                                startTime = System.currentTimeMillis(),
                                exerciseType = "CYCLING"
                            )
                        )
                        Log.d(TAG, "Created workout session: $currentSessionId (CYCLING)")
                    }
                    startTimer()
                } else {
                    Log.w(TAG, "Cycling start failed: BIKING unsupported or HS error")
                    _cyclingStartFailed.value = true
                    // _isRunning stays false; no session row, no timer → no ghost session.
                    // Service is left foreground/idle; user retries or backs out
                    // (BackHandler → stopExercise) per spec §3.2.
                }
            }
        } else {
            runningEngine.reset()
            runningEngine.start()
            _isRunning.value = true

            // Create new workout session in DB
            currentSessionId = UUID.randomUUID().toString()
            val sessionType = "RUNNING"
            serviceScope.launch(Dispatchers.IO) {
                workoutDao.insertSession(
                    WorkoutSession(
                        sessionId = currentSessionId!!,
                        startTime = System.currentTimeMillis(),
                        exerciseType = sessionType
                    )
                )
                Log.d(TAG, "Created workout session: $currentSessionId ($sessionType)")
            }

            // Start Health Services
            serviceScope.launch {
                exerciseManager.startExercise(mode.toExerciseType())
            }

            // Start 1Hz timer
            startTimer()
        }
    }

    /**
     * Pause exercise
     */
    fun pauseExercise() {
        serviceScope.launch {
            val engineActive =
                if (mode == ExerciseMode.CYCLING) cyclingEngine.isActive() else runningEngine.isActive()
            if (engineActive) {
                Log.d(TAG, "Pausing exercise")
                if (mode == ExerciseMode.CYCLING) cyclingEngine.pause() else runningEngine.pause()
                exerciseManager.pauseExercise()
                _isPaused.value = true
            } else {
                Log.d(TAG, "Resuming exercise")
                if (mode == ExerciseMode.CYCLING) cyclingEngine.resume() else runningEngine.resume()
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
        val finalMetrics = if (mode == ExerciseMode.CYCLING) {
            cyclingEngine.getRLensPayload()  // remapped RunningMetrics: distance+elapsed valid for session summary
        } else {
            runningEngine.getCurrentMetrics()
        }
        if (mode == ExerciseMode.CYCLING) cyclingEngine.stop() else runningEngine.stop()
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

                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.tick()
                    val honest = cyclingEngine.getCurrentMetrics()
                    _cyclingMetrics.value = honest
                    val payload = cyclingEngine.getRLensPayload()

                    Log.d(TAG, "Cycling: speed=${honest.speedKmh}km/h, alt=${honest.altitudeM}m, HR=${honest.heartRate}, Time=${honest.elapsedSeconds}s")

                    // Cycling sends every 2s (runvision-iq CyclingStrategy interval; fast speed changes)
                    if (tickCount % 2 == 0) {
                        rLensConnection?.let { conn ->
                            if (conn.isConnected()) conn.sendMetrics(payload)
                            else Log.w(TAG, "rLens NOT connected, skipping send")
                        } ?: Log.w(TAG, "rLensConnection is NULL")
                    }

                    // DB sample: store honest cycling values. pace/cadence = 0 so
                    // getAvgPace/getAvgCadence don't compute avg(speed×60)/avg(altitude) garbage.
                    currentSessionId?.let { sessionId ->
                        launch(Dispatchers.IO) {
                            workoutDao.insertSample(
                                WorkoutSample(
                                    sessionId = sessionId,
                                    timestamp = System.currentTimeMillis(),
                                    heartRate = honest.heartRate,
                                    paceSecondsPerKm = 0,
                                    cadence = 0,
                                    distanceMeters = honest.distanceMeters,
                                    latitude = payload.latitude,
                                    longitude = payload.longitude
                                )
                            )
                        }
                    }

                    updateNotification(payload)
                } else {
                    runningEngine.tick()
                    val currentMetrics = runningEngine.getCurrentMetrics()
                    _metrics.value = currentMetrics

                    Log.d(TAG, "Metrics: HR=${currentMetrics.heartRate}, Pace=${currentMetrics.paceSecondsPerKm}, Time=${currentMetrics.elapsedSeconds}s")

                    // Send to rLens if connected (5초마다 전송 - 배터리 절감)
                    if (tickCount % 5 == 0) {
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
                    }

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
