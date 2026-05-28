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
import com.runvision.wear.sensor.AltitudeProvider
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
    private lateinit var altitudeProvider: AltitudeProvider

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

    // GPS lock gating (cycling 모드만). Health Services 세션은 시작되어 LOCATION 데이터를
    // 수신하지만, cyclingEngine + altitudeProvider + isRunning은 σ_v≤15m sample 3개
    // 연속 수신될 때까지 지연. UI는 _isWaitingGpsLock 보고 "GPS 검색 중" 표시.
    private val _isWaitingGpsLock = MutableStateFlow(false)
    val isWaitingGpsLock: StateFlow<Boolean> = _isWaitingGpsLock
    @Volatile private var gpsLockCount: Int = 0
    private val gpsLockRequiredSamples = 3
    private val gpsLockSigmaGate = 15.0

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseService = this@ExerciseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ExerciseService created")

        runningEngine = RunningEngine(this)
        cyclingEngine = CyclingEngine()

        // AltitudeProvider: in-app US6735542 two-state loop on raw barometer
        // + Open-Meteo DEM reference. Replaces Health Services ABSOLUTE_ELEVATION
        // (intentionally not subscribed — that path is opaque Google fusion).
        altitudeProvider = AltitudeProvider(this).apply {
            onAltitude = { meters ->
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateAltitude(meters)
                }
            }
            onDiagnostic = { d ->
                Log.d(
                    "Altitude",
                    "H_B=%.2f H_REF=%s mode=%d U=%.2f P_base=%.0f σ=%s src=%s".format(
                        d.hB,
                        d.hRef?.let { "%.2f".format(it) } ?: "null",
                        d.mode, d.u, d.pBasePa,
                        d.sigma?.let { "%.1f".format(it) } ?: "null",
                        d.refSource,
                    )
                )
            }
        }

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
            onGpsForAltitudeUpdate = { lat, lon, alt, sigma ->
                // GPS lock gate (CYCLING only). σ_v ≤ 15m × 3 sample 연속 → activate.
                // Galaxy Watch 6 / Wear OS 6의 Health Services LocationData가
                // verticalPositionErrorMeters 메서드 미제공 → sigma=null. 그럴 땐 valid
                // altitude(GPS lock 자체는 들어오는 신호)만으로 lock count 인정 — 영원히
                // "GPS 검색 중" 갇히는 것보다 실용. 실내 창가도 좌표 잡히면 lock 가능한
                // trade-off는 사용자가 cancel로 회피.
                if (mode == ExerciseMode.CYCLING && _isWaitingGpsLock.value) {
                    // lock gate = GPS 좌표(LOCATION sample)가 들어오는 것 자체. 이 callback이
                    // 호출됐다는 건 Health Services가 GPS fix를 emit했다는 신호.
                    // Galaxy Watch 6는 altitude=Double.MAX_VALUE(sentinel→null) + σ_v 미제공(null)
                    // 이라 alt/sigma 기반 gate는 영원히 false였음. quality(σ_v ≤ 15m)는 baseline
                    // anchor가 별도 처리하므로 lock 자체는 좌표만으로 충분.
                    val validSample = when {
                        sigma != null && sigma > 0.0 -> sigma <= gpsLockSigmaGate
                        else -> true  // σ_v 미제공(Galaxy) → 좌표 fix만으로 lock
                    }
                    if (validSample) {
                        gpsLockCount++
                        Log.d(TAG, "GPS lock progress: $gpsLockCount/$gpsLockRequiredSamples (σ_v=$sigma alt=$alt)")
                        if (gpsLockCount >= gpsLockRequiredSamples) {
                            activateCyclingAfterGpsLock()
                        }
                    } else {
                        // Bad sample resets streak — 3 sample 연속 요구.
                        gpsLockCount = 0
                    }
                }
                // Feeds DEM-cell lookup + fallback reference into AltitudeProvider.
                // CYCLING 전용 — 러닝 경로 불변: 러닝은 고도 미사용이므로 pushGps의 prewarm
                // DEM fetch(Open-Meteo 네트워크)가 매 GPS 샘플마다 헛돌지 않도록 게이트.
                if (mode == ExerciseMode.CYCLING) {
                    altitudeProvider.pushGps(lat, lon, alt, sigma)
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
            // NOTE: altitude no longer flows via ExerciseManager. AltitudeProvider
            // owns the barometer pipeline and pushes via its own onAltitude callback
            // (wired above at the provider construction site).
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

        // AltitudeProvider 정리 — stopExercise를 거치지 않고 onDestroy로 직행하는 경로(시스템 회수
        // 등)에서도 sensor listener·DEM fetch·scope가 누수되지 않도록.
        if (this::altitudeProvider.isInitialized) altitudeProvider.stop()

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

        // CYCLING: GPS lock 까지 baro/altitude/cyclingEngine 시작 지연. Health Services는
        // 시작해서 LOCATION 데이터를 받아야 lock 판정 가능 (chicken-and-egg 해소: session
        // 자체는 시작, user-visible "active"는 lock 후).
        // RUNNING: 트레드밀 자연 대응 — GPS 없이도 즉시 active.
        if (mode == ExerciseMode.CYCLING) {
            gpsLockCount = 0
            _isWaitingGpsLock.value = true
        }

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
                    // CYCLING: cyclingEngine.start() + _isRunning + timer는 GPS lock 후
                    // activateCyclingAfterGpsLock()에서. Health Services LOCATION sample이
                    // 들어와야 lock 판정 가능 — session 자체는 시작하되 user-visible "active"
                    // 는 σ_v ≤ 15m × 3 sample 연속까지 보류.
                } else {
                    Log.w(TAG, "Cycling start failed: BIKING unsupported or HS error")
                    _cyclingStartFailed.value = true
                    _isWaitingGpsLock.value = false
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
     * Cycling 모드 GPS lock 받은 후 user-visible "active" 전환.
     * Health Services session은 이미 startExercise()에서 시작됨. 여기서 cyclingEngine.start +
     * altitudeProvider.start + _isRunning + timer + DB session 생성을 모두 묶어서 처리.
     * 한 번만 실행 (gpsLockCount는 activate 직후 재호출 방지로 충분히 큰 값 유지).
     */
    private fun activateCyclingAfterGpsLock() {
        if (!_isWaitingGpsLock.value) return
        _isWaitingGpsLock.value = false
        Log.d(TAG, "GPS lock acquired — activating cycling")
        val baroStarted = altitudeProvider.start()
        Log.d(TAG, "AltitudeProvider start: baro=$baroStarted")
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
            Log.d(TAG, "Created workout session: $currentSessionId (CYCLING, post-GPS-lock)")
        }
        startTimer()
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

        // Stop barometer sampling first so state persists with the latest values.
        if (this::altitudeProvider.isInitialized) altitudeProvider.stop()
        // Clear any cycling start-failure banner so it doesn't persist on Home
        // after the user backs out of a failed cycling attempt.
        _cyclingStartFailed.value = false
        val finalMetrics = if (mode == ExerciseMode.CYCLING) {
            cyclingEngine.getRLensPayload()  // remapped RunningMetrics: distance+elapsed valid for session summary
        } else {
            runningEngine.getCurrentMetrics()
        }
        if (mode == ExerciseMode.CYCLING) cyclingEngine.stop() else runningEngine.stop()
        _isRunning.value = false
        _isPaused.value = false
        _isWaitingGpsLock.value = false
        gpsLockCount = 0
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
