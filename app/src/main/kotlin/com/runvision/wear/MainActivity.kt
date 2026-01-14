package com.runvision.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.runvision.wear.ble.RLensConnection
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.service.ExerciseService
import com.runvision.wear.ui.screens.HomeScreen
import com.runvision.wear.ui.screens.RunningScreen
import com.runvision.wear.ui.theme.RunVisionWearTheme
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.launch

/**
 * Main entry point for RunVision Wear OS app
 *
 * Responsibilities:
 * - Permission handling (sensors, location, BLE)
 * - BLE scanning and connection to rLens
 * - Navigation between Home and Running screens
 * - Binds to ExerciseService for background running
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BRIGHT_BRIGHTNESS = 0.9f   // 90% brightness for first 5 seconds
        private const val DIM_BRIGHTNESS = 0.05f   // 5% brightness after delay (very dim)
        private const val BRIGHTNESS_DIM_DELAY_MS = 5_000L   // 5 seconds
    }

    // State for Compose UI (observed from Service)
    private val connectionState = mutableStateOf(RLensConnection.ConnectionState.DISCONNECTED)
    private val metrics = mutableStateOf(RunningMetrics())
    private val isRunning = mutableStateOf(false)
    private val isPaused = mutableStateOf(false)
    private val isAmbient = mutableStateOf(false)

    // Service binding (BLE auto-start logic moved to ExerciseService)
    private var exerciseService: ExerciseService? = null
    private var serviceBound = false
    private var navController: NavHostController? = null

    // Brightness dimming handler
    private val brightnessHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimRunnable = Runnable {
        Log.d(TAG, "Dimming screen to $DIM_BRIGHTNESS after ${BRIGHTNESS_DIM_DELAY_MS}ms")
        setBrightness(DIM_BRIGHTNESS)
    }

    // Ambient Mode support
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            Log.d(TAG, "Entering ambient mode")
            isAmbient.value = true
        }

        override fun onExitAmbient() {
            Log.d(TAG, "Exiting ambient mode")
            isAmbient.value = false
        }

        override fun onUpdateAmbient() {
            Log.d(TAG, "Ambient update")
            // Force recomposition by toggling state
        }
    }
    private lateinit var ambientObserver: AmbientLifecycleObserver

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as ExerciseService.LocalBinder
            exerciseService = binder.getService()
            serviceBound = true

            // Check if already running - navigation will happen in Compose via LaunchedEffect
            if (exerciseService?.isRunning?.value == true) {
                Log.d(TAG, "Service already running, will navigate when navController ready")
                isRunning.value = true  // This triggers navigation in Compose
            }

            // Observe Service's BLE connectionState (Service manages BLE lifecycle)
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.connectionState?.collect { state ->
                        Log.d(TAG, "Service connectionState: $state")
                        connectionState.value = state
                    }
                }
            }

            // Collect metrics from service
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.metrics?.collect { m ->
                        metrics.value = m
                    }
                }
            }

            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.isRunning?.collect { running ->
                        isRunning.value = running
                        // Keep screen on with dimmed brightness during exercise
                        setScreenMode(running)
                    }
                }
            }

            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.isPaused?.collect { paused ->
                        isPaused.value = paused
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            exerciseService = null
            serviceBound = false
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Log permission results (BLE scanning handled by Service on START click)
        if (!permissions.all { it.value }) {
            Log.w(TAG, "Some permissions denied: ${permissions.filter { !it.value }.keys}")
        } else {
            Log.d(TAG, "All permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Initialize Ambient Mode observer
        ambientObserver = AmbientLifecycleObserver(this, ambientCallback)
        lifecycle.addObserver(ambientObserver)

        // BLE is now managed by ExerciseService (see initializeBle())
        // Service context keeps BLE alive even when screen is off

        setContent {
            RunVisionWearTheme {
                val nav = rememberSwipeDismissableNavController()
                navController = nav

                // Navigate to running screen if service is already running
                // This handles activity recreation (screen on/off)
                val currentIsRunning = isRunning.value
                Log.d(TAG, "Compose: currentIsRunning=$currentIsRunning")

                LaunchedEffect(currentIsRunning) {
                    Log.d(TAG, "LaunchedEffect triggered: isRunning=$currentIsRunning")
                    if (currentIsRunning) {
                        // Small delay to ensure navigation is fully initialized
                        kotlinx.coroutines.delay(100)
                        val currentRoute = nav.currentBackStackEntry?.destination?.route
                        Log.d(TAG, "LaunchedEffect: currentRoute=$currentRoute")
                        if (currentRoute == "home" || currentRoute == null) {
                            Log.d(TAG, "Navigating to running screen (service already running)")
                            nav.navigate("running") {
                                popUpTo("home") { inclusive = false }
                            }
                        }
                    }
                }

                SwipeDismissableNavHost(
                    navController = nav,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            connectionState = connectionState.value,
                            onStartClick = {
                                // START button: Service handles scanning and auto-start
                                Log.d(TAG, "START button pressed, starting scan via Service")

                                // If already connected, start immediately
                                if (connectionState.value == RLensConnection.ConnectionState.CONNECTED) {
                                    startRunning()
                                    nav.navigate("running")
                                } else {
                                    // Start foreground service first, then start scanning
                                    // Auto-start will trigger on BLE connection
                                    val intent = Intent(this@MainActivity, ExerciseService::class.java)
                                    startForegroundService(intent)
                                    exerciseService?.startScanning()
                                }
                            }
                        )
                    }
                    composable("running") {
                        // Handle back button/swipe to stop exercise
                        BackHandler {
                            Log.d(TAG, "Back pressed in running screen, stopping exercise")
                            stopRunning()
                            nav.popBackStack()
                        }

                        RunningScreen(
                            metrics = metrics.value,
                            isAmbient = isAmbient.value,
                            isPaused = isPaused.value,
                            onPauseClick = { togglePause() },
                            onStopClick = {
                                stopRunning()
                                nav.popBackStack()
                            },
                            onScreenTouch = { wakeUpScreen() }
                        )
                    }
                }
            }
        }

        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart, serviceBound=$serviceBound")

        // Bind to ExerciseService
        Intent(this, ExerciseService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // If already bound (screen wake), sync state immediately
        exerciseService?.let { service ->
            val running = service.isRunning.value
            Log.d(TAG, "onStart: service already bound, isRunning=$running")
            if (running) {
                isRunning.value = true
            }
            // Immediately sync metrics to avoid showing stale data
            metrics.value = service.metrics.value
            Log.d(TAG, "onStart: synced metrics, time=${metrics.value.elapsedSeconds}s")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        // Never unbind - let service continue in background
        // Service will be unbound when Activity is destroyed
    }

    /**
     * Check and request required permissions
     */
    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "All permissions already granted")
            // BLE scanning now handled by Service on START button click
        } else {
            Log.d(TAG, "Requesting permissions: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Start a new running session
     */
    private fun startRunning() {
        Log.d(TAG, "Starting running session")

        // Start and bind to service (Service manages BLE internally)
        val intent = Intent(this, ExerciseService::class.java)
        startForegroundService(intent)

        // If already bound, start exercise
        exerciseService?.startExercise()
    }

    /**
     * Toggle pause/resume state
     */
    private fun togglePause() {
        exerciseService?.pauseExercise()
    }

    /**
     * Stop the running session
     */
    private fun stopRunning() {
        Log.d(TAG, "Stopping running session")
        // Service manages BLE - stopExercise() will disconnect and stop scanning
        exerciseService?.stopExercise()
    }

    /**
     * Set screen mode for running/idle state
     * - Running: Bright for 10 seconds, then dim (power saving)
     * - Idle: Normal brightness, allow screen to turn off
     */
    private fun setScreenMode(running: Boolean) {
        if (running) {
            Log.d(TAG, "Setting screen mode: ON + BRIGHT (will dim after ${BRIGHTNESS_DIM_DELAY_MS}ms)")
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBrightness(BRIGHT_BRIGHTNESS)
            // Schedule dimming after delay
            brightnessHandler.removeCallbacks(dimRunnable)
            brightnessHandler.postDelayed(dimRunnable, BRIGHTNESS_DIM_DELAY_MS)
        } else {
            Log.d(TAG, "Setting screen mode: NORMAL")
            // Cancel scheduled dimming
            brightnessHandler.removeCallbacks(dimRunnable)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    /**
     * Set screen brightness
     */
    private fun setBrightness(brightness: Float) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
    }

    /**
     * Wake up screen on touch - brighten and restart dim timer
     */
    private fun wakeUpScreen() {
        if (isRunning.value) {
            Log.d(TAG, "Screen touched - waking up brightness")
            setBrightness(BRIGHT_BRIGHTNESS)
            brightnessHandler.removeCallbacks(dimRunnable)
            brightnessHandler.postDelayed(dimRunnable, BRIGHTNESS_DIM_DELAY_MS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy, isRunning=${isRunning.value}")

        // Unbind service (Service continues to manage BLE independently)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // BLE is managed by Service - no cleanup needed here
    }
}
