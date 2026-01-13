package com.runvision.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.runvision.wear.ble.RLensConnection
import com.runvision.wear.ble.RLensScanner
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.engine.RunningEngine
import com.runvision.wear.health.ExerciseManager
import com.runvision.wear.ui.screens.HomeScreen
import com.runvision.wear.ui.screens.RunningScreen
import com.runvision.wear.ui.theme.RunVisionWearTheme
import kotlinx.coroutines.*

/**
 * Main entry point for RunVision Wear OS app
 *
 * Responsibilities:
 * - Permission handling (sensors, location, BLE)
 * - BLE scanning and connection to rLens
 * - Navigation between Home and Running screens
 * - Timer loop for metrics updates (1Hz)
 * - State management for UI
 */
class MainActivity : ComponentActivity() {

    private val runningEngine = RunningEngine()
    private lateinit var rLensScanner: RLensScanner
    private lateinit var rLensConnection: RLensConnection
    private lateinit var exerciseManager: ExerciseManager

    // State for Compose UI
    private val connectionState = mutableStateOf(RLensConnection.ConnectionState.DISCONNECTED)
    private val metrics = mutableStateOf(RunningMetrics())
    private val isRunning = mutableStateOf(false)

    private var timerJob: Job? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startScanning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize BLE components
        rLensScanner = RLensScanner(this) { device ->
            rLensConnection.connect(device)
        }

        rLensConnection = RLensConnection(this) { state ->
            connectionState.value = state
        }

        // Initialize Health Services
        exerciseManager = ExerciseManager(this).apply {
            onHeartRateUpdate = { hr ->
                runningEngine.updateHeartRate(hr)
            }
            onLocationUpdate = { lat, lon, timestamp ->
                runningEngine.updateGps(lat, lon, timestamp)
            }
            onStepsUpdate = { steps ->
                runningEngine.updateCadence(steps)
            }
        }

        setContent {
            RunVisionWearTheme {
                val navController = rememberSwipeDismissableNavController()

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            connectionState = connectionState.value,
                            onStartClick = {
                                startRunning()
                                navController.navigate("running")
                            }
                        )
                    }
                    composable("running") {
                        RunningScreen(
                            metrics = metrics.value,
                            onPauseClick = { togglePause() },
                            onStopClick = {
                                stopRunning()
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }

        checkPermissions()
    }

    /**
     * Check and request required permissions
     */
    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startScanning()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Start BLE scanning for rLens devices
     */
    private fun startScanning() {
        rLensScanner.startScan()
    }

    /**
     * Start a new running session
     */
    private fun startRunning() {
        runningEngine.reset()
        runningEngine.start()
        isRunning.value = true
        startTimer()

        // Start Health Services exercise
        CoroutineScope(Dispatchers.Main).launch {
            exerciseManager.startExercise()
        }
    }

    /**
     * Toggle pause/resume state
     */
    private fun togglePause() {
        CoroutineScope(Dispatchers.Main).launch {
            if (runningEngine.isActive()) {
                runningEngine.pause()
                exerciseManager.pauseExercise()
            } else {
                runningEngine.resume()
                exerciseManager.resumeExercise()
            }
        }
    }

    /**
     * Stop the running session
     */
    private fun stopRunning() {
        runningEngine.stop()
        isRunning.value = false
        timerJob?.cancel()

        // End Health Services exercise (auto-syncs to Samsung Health)
        CoroutineScope(Dispatchers.Main).launch {
            exerciseManager.endExercise()
        }
    }

    /**
     * Start the 1Hz timer loop for metrics updates
     *
     * Every second:
     * 1. Tick the running engine (increment elapsed time)
     * 2. Get updated metrics
     * 3. Send metrics to rLens if connected
     */
    private fun startTimer() {
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
                runningEngine.tick()
                metrics.value = runningEngine.getCurrentMetrics()

                // Send to rLens if connected
                if (rLensConnection.isConnected()) {
                    rLensConnection.sendMetrics(metrics.value)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        rLensScanner.stopScan()
        rLensConnection.disconnect()
    }
}
