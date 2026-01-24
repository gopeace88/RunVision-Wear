# Adaptive Pace Algorithm Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** GPS 유무에 따라 적응형으로 동작하는 안정적인 페이스 알고리즘 구현

**Architecture:** 4개의 독립 컴포넌트(PaceSmoother, StopDetector, StrideLengthLearner, AdaptivePaceCalculator)를 생성하고 RunningEngine에 통합. TDD로 각 컴포넌트를 개별 테스트 후 통합.

**Tech Stack:** Kotlin, JUnit, MockK, SharedPreferences

---

## Task 1: PaceSmoother 구현

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/engine/PaceSmoother.kt`
- Create: `app/src/test/kotlin/com/runvision/wear/engine/PaceSmootherTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/runvision/wear/engine/PaceSmootherTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PaceSmootherTest {

    private lateinit var smoother: PaceSmoother

    @Before
    fun setup() {
        smoother = PaceSmoother(windowSize = 5)
    }

    @Test
    fun `addPace returns same value for first input`() {
        val result = smoother.addPace(300)
        assertEquals(300, result)
    }

    @Test
    fun `addPace returns average of multiple values`() {
        smoother.addPace(300)
        smoother.addPace(320)
        val result = smoother.addPace(310)
        assertEquals(310, result) // (300+320+310)/3 = 310
    }

    @Test
    fun `addPace ignores outlier above 150 percent`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.addPace(300)
        val result = smoother.addPace(600) // 200% of avg, should be ignored
        assertEquals(300, result)
    }

    @Test
    fun `addPace ignores outlier below 50 percent`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.addPace(300)
        val result = smoother.addPace(100) // 33% of avg, should be ignored
        assertEquals(300, result)
    }

    @Test
    fun `window respects max size`() {
        repeat(10) { smoother.addPace(100) }
        val result = smoother.addPace(200)
        // Window: [100, 100, 100, 100, 200] -> avg = 120
        assertEquals(120, result)
    }

    @Test
    fun `reset clears buffer`() {
        smoother.addPace(300)
        smoother.addPace(300)
        smoother.reset()
        assertEquals(0, smoother.getSmoothedPace())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.PaceSmootherTest" --info`

Expected: FAIL with "Unresolved reference: PaceSmoother"

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/kotlin/com/runvision/wear/engine/PaceSmoother.kt
package com.runvision.wear.engine

import java.util.ArrayDeque

/**
 * Pace Smoother with moving average and outlier rejection
 *
 * Uses N-sample moving average to reduce noise.
 * Rejects outliers that deviate more than 50% from current average.
 */
class PaceSmoother(private val windowSize: Int = 5) {

    private val paceBuffer = ArrayDeque<Int>(windowSize)

    /**
     * Add a pace value and return the smoothed result
     *
     * @param paceSeconds Raw pace in seconds per km
     * @return Smoothed pace, or the input if buffer is empty
     */
    fun addPace(paceSeconds: Int): Int {
        if (paceSeconds <= 0) return getSmoothedPace()

        // Outlier rejection: ignore if outside ±50% of current average
        if (paceBuffer.isNotEmpty()) {
            val avg = paceBuffer.average()
            if (paceSeconds < avg * 0.5 || paceSeconds > avg * 1.5) {
                return getSmoothedPace()
            }
        }

        paceBuffer.addLast(paceSeconds)
        if (paceBuffer.size > windowSize) {
            paceBuffer.removeFirst()
        }

        return getSmoothedPace()
    }

    /**
     * Get current smoothed pace without adding new value
     */
    fun getSmoothedPace(): Int {
        if (paceBuffer.isEmpty()) return 0
        return paceBuffer.average().toInt()
    }

    /**
     * Reset the smoother, clearing all buffered values
     */
    fun reset() {
        paceBuffer.clear()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.PaceSmootherTest" --info`

Expected: PASS (6 tests)

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/PaceSmoother.kt
git add app/src/test/kotlin/com/runvision/wear/engine/PaceSmootherTest.kt
git commit -m "feat(engine): add PaceSmoother with moving average and outlier rejection"
```

---

## Task 2: StopDetector 구현

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/engine/StopDetector.kt`
- Create: `app/src/test/kotlin/com/runvision/wear/engine/StopDetectorTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/runvision/wear/engine/StopDetectorTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StopDetectorTest {

    private lateinit var detector: StopDetector

    @Before
    fun setup() {
        detector = StopDetector()
    }

    @Test
    fun `initially not stopped`() {
        assertFalse(detector.isStopped())
    }

    @Test
    fun `low cadence triggers stop`() {
        detector.updateCadence(50) // Below 60 threshold
        assertTrue(detector.isStopped())
    }

    @Test
    fun `normal cadence does not trigger stop`() {
        detector.updateCadence(170)
        detector.updateDistanceChange()
        assertFalse(detector.isStopped())
    }

    @Test
    fun `no distance change for 2 seconds triggers stop`() {
        detector.updateCadence(170)
        detector.updateDistanceChange()

        // Simulate 2.5 seconds passing
        Thread.sleep(2500)

        assertTrue(detector.isStopped())
    }

    @Test
    fun `reset clears state`() {
        detector.updateCadence(50)
        assertTrue(detector.isStopped())

        detector.reset()
        detector.updateCadence(170)
        detector.updateDistanceChange()
        assertFalse(detector.isStopped())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.StopDetectorTest" --info`

Expected: FAIL with "Unresolved reference: StopDetector"

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/kotlin/com/runvision/wear/engine/StopDetector.kt
package com.runvision.wear.engine

/**
 * Stop Detector using cadence and distance change
 *
 * Detects when user has stopped running based on:
 * 1. Low cadence (< 60 spm)
 * 2. No distance change for 2+ seconds
 */
class StopDetector {

    private var lastCadence: Int = 0
    private var lastDistanceChangeTime: Long = System.currentTimeMillis()

    companion object {
        const val STOP_CADENCE_THRESHOLD = 60      // spm
        const val STOP_TIME_THRESHOLD_MS = 2000L   // 2 seconds
    }

    /**
     * Update with latest cadence reading
     */
    fun updateCadence(cadence: Int) {
        lastCadence = cadence
    }

    /**
     * Call when distance has changed (GPS or Health Services update)
     */
    fun updateDistanceChange() {
        lastDistanceChangeTime = System.currentTimeMillis()
    }

    /**
     * Check if user is currently stopped
     *
     * @return true if stopped (low cadence OR no distance change)
     */
    fun isStopped(): Boolean {
        val now = System.currentTimeMillis()

        // Condition 1: Low cadence
        val lowCadence = lastCadence < STOP_CADENCE_THRESHOLD

        // Condition 2: No distance change for threshold duration
        val noDistanceChange = (now - lastDistanceChangeTime) > STOP_TIME_THRESHOLD_MS

        return lowCadence || noDistanceChange
    }

    /**
     * Reset detector state
     */
    fun reset() {
        lastCadence = 0
        lastDistanceChangeTime = System.currentTimeMillis()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.StopDetectorTest" --info`

Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/StopDetector.kt
git add app/src/test/kotlin/com/runvision/wear/engine/StopDetectorTest.kt
git commit -m "feat(engine): add StopDetector with cadence and distance-based detection"
```

---

## Task 3: StrideLengthLearner 구현

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/engine/StrideLengthLearner.kt`
- Create: `app/src/test/kotlin/com/runvision/wear/engine/StrideLengthLearnerTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/runvision/wear/engine/StrideLengthLearnerTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StrideLengthLearnerTest {

    private lateinit var learner: StrideLengthLearner

    @Before
    fun setup() {
        learner = StrideLengthLearner()
    }

    @Test
    fun `default stride for cadence 150 is 0_70m`() {
        val stride = learner.getStrideLength(150)
        assertEquals(0.70f, stride, 0.01f)
    }

    @Test
    fun `default stride for cadence 180 is 0_85m`() {
        val stride = learner.getStrideLength(180)
        assertEquals(0.85f, stride, 0.01f)
    }

    @Test
    fun `default stride for cadence 200 is 0_95m`() {
        val stride = learner.getStrideLength(200)
        assertEquals(0.95f, stride, 0.01f)
    }

    @Test
    fun `default stride clamps at low cadence`() {
        val stride = learner.getStrideLength(100)
        assertEquals(0.55f, stride, 0.01f) // 0.70 + (100-150)*0.005 = 0.45, but clamped
    }

    @Test
    fun `learning updates stride after threshold`() {
        // Simulate 600m with 750 steps = 0.80m stride
        learner.updateWithGpsData(600.0, 750)

        val stride = learner.getStrideLength(180)
        assertEquals(0.80f, stride, 0.01f)
    }

    @Test
    fun `learning requires minimum distance`() {
        // Only 400m - not enough
        learner.updateWithGpsData(400.0, 500)

        // Should still use default
        val stride = learner.getStrideLength(180)
        assertEquals(0.85f, stride, 0.01f)
    }

    @Test
    fun `hasLearnedStride returns correct state`() {
        assertFalse(learner.hasLearnedStride())

        learner.updateWithGpsData(600.0, 750)
        assertTrue(learner.hasLearnedStride())
    }

    @Test
    fun `reset clears learned data`() {
        learner.updateWithGpsData(600.0, 750)
        assertTrue(learner.hasLearnedStride())

        learner.reset()
        assertFalse(learner.hasLearnedStride())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.StrideLengthLearnerTest" --info`

Expected: FAIL with "Unresolved reference: StrideLengthLearner"

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/kotlin/com/runvision/wear/engine/StrideLengthLearner.kt
package com.runvision.wear.engine

/**
 * Stride Length Learner
 *
 * Learns personal stride length from GPS data.
 * Falls back to cadence-based estimation when no learned data.
 *
 * Default formula: stride = 0.70 + (cadence - 150) * 0.005
 * Range: 0.55m (cadence 120) to 1.00m (cadence 210)
 */
class StrideLengthLearner {

    private var learnedStrideLength: Float? = null
    private var gpsDistanceAccum: Double = 0.0
    private var stepCountAccum: Long = 0

    companion object {
        const val MIN_DISTANCE_FOR_LEARNING = 500.0  // meters
        const val MIN_STEPS_FOR_LEARNING = 600L
        const val DEFAULT_STRIDE_BASE = 0.70f        // meters
        const val STRIDE_CADENCE_FACTOR = 0.005f
        const val MIN_STRIDE = 0.55f
        const val MAX_STRIDE = 1.00f
    }

    /**
     * Update with GPS distance and step count
     *
     * When enough data is collected, calculates and stores learned stride.
     */
    fun updateWithGpsData(distanceDelta: Double, stepsDelta: Long) {
        if (distanceDelta <= 0 || stepsDelta <= 0) return

        gpsDistanceAccum += distanceDelta
        stepCountAccum += stepsDelta

        // Learn stride when threshold reached
        if (gpsDistanceAccum >= MIN_DISTANCE_FOR_LEARNING &&
            stepCountAccum >= MIN_STEPS_FOR_LEARNING) {
            learnedStrideLength = (gpsDistanceAccum / stepCountAccum).toFloat()
                .coerceIn(MIN_STRIDE, MAX_STRIDE)
        }
    }

    /**
     * Get stride length for given cadence
     *
     * @param cadence Current cadence in steps per minute
     * @return Learned stride if available, otherwise calculated default
     */
    fun getStrideLength(cadence: Int): Float {
        return learnedStrideLength ?: calculateDefaultStride(cadence)
    }

    /**
     * Check if stride has been learned from GPS data
     */
    fun hasLearnedStride(): Boolean = learnedStrideLength != null

    /**
     * Calculate default stride based on cadence
     *
     * Formula: stride = 0.70 + (cadence - 150) * 0.005
     */
    private fun calculateDefaultStride(cadence: Int): Float {
        val adjustment = (cadence - 150) * STRIDE_CADENCE_FACTOR
        return (DEFAULT_STRIDE_BASE + adjustment).coerceIn(MIN_STRIDE, MAX_STRIDE)
    }

    /**
     * Reset all learned data
     */
    fun reset() {
        learnedStrideLength = null
        gpsDistanceAccum = 0.0
        stepCountAccum = 0
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.StrideLengthLearnerTest" --info`

Expected: PASS (8 tests)

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/StrideLengthLearner.kt
git add app/src/test/kotlin/com/runvision/wear/engine/StrideLengthLearnerTest.kt
git commit -m "feat(engine): add StrideLengthLearner with GPS-based learning and default formula"
```

---

## Task 4: AdaptivePaceCalculator 구현

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/engine/AdaptivePaceCalculator.kt`
- Create: `app/src/test/kotlin/com/runvision/wear/engine/AdaptivePaceCalculatorTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/runvision/wear/engine/AdaptivePaceCalculatorTest.kt
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptivePaceCalculatorTest {

    private lateinit var calculator: AdaptivePaceCalculator
    private lateinit var strideLearner: StrideLengthLearner
    private lateinit var smoother: PaceSmoother
    private lateinit var stopDetector: StopDetector

    @Before
    fun setup() {
        strideLearner = StrideLengthLearner()
        smoother = PaceSmoother(windowSize = 5)
        stopDetector = StopDetector()
        calculator = AdaptivePaceCalculator(strideLearner, smoother, stopDetector)
    }

    @Test
    fun `gps mode calculates pace from distance and time`() {
        stopDetector.updateCadence(170)
        stopDetector.updateDistanceChange()

        // 100m in 30 seconds = 3.33 m/s = 5:00/km = 300 seconds
        val pace = calculator.updateWithGpsDistance(100.0, 30.0)
        assertEquals(300, pace, 30) // Allow some tolerance
    }

    @Test
    fun `gps mode returns 0 when stopped`() {
        stopDetector.updateCadence(50) // Stopped

        val pace = calculator.updateWithGpsDistance(100.0, 30.0)
        assertEquals(0, pace)
    }

    @Test
    fun `cadence mode uses stride learner`() {
        stopDetector.updateCadence(180)
        stopDetector.updateDistanceChange()

        // Cadence 180 with default stride 0.85m
        // speed = 180 * 0.85 / 60 = 2.55 m/s
        // pace = 1000 / 2.55 = 392 seconds (6:32/km)
        val pace = calculator.updateWithCadence(180)
        assertTrue(pace in 350..450)
    }

    @Test
    fun `cadence mode returns 0 when stopped`() {
        stopDetector.updateCadence(50)

        val pace = calculator.updateWithCadence(50)
        assertEquals(0, pace)
    }

    @Test
    fun `mode switches from gps to cadence after timeout`() {
        stopDetector.updateCadence(180)

        // First, GPS mode
        calculator.updateWithGpsDistance(100.0, 30.0)
        assertTrue(calculator.isGpsMode())

        // Simulate 6 seconds without GPS
        Thread.sleep(6000)

        calculator.updateWithCadence(180)
        assertFalse(calculator.isGpsMode())
    }

    @Test
    fun `reset clears all state`() {
        calculator.updateWithGpsDistance(100.0, 30.0)
        calculator.reset()

        assertFalse(calculator.isGpsMode())
        assertEquals(0, smoother.getSmoothedPace())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Expected $expected ± $tolerance, but was $actual",
            actual in (expected - tolerance)..(expected + tolerance))
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.AdaptivePaceCalculatorTest" --info`

Expected: FAIL with "Unresolved reference: AdaptivePaceCalculator"

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/kotlin/com/runvision/wear/engine/AdaptivePaceCalculator.kt
package com.runvision.wear.engine

/**
 * Adaptive Pace Calculator
 *
 * Automatically switches between GPS and cadence-based pace calculation.
 * Uses GPS when available, falls back to cadence after timeout.
 */
class AdaptivePaceCalculator(
    private val strideLearner: StrideLengthLearner,
    private val smoother: PaceSmoother,
    private val stopDetector: StopDetector
) {

    private var hasGps: Boolean = false
    private var lastGpsTime: Long = 0

    companion object {
        const val GPS_TIMEOUT_MS = 5000L  // 5 seconds
    }

    /**
     * Update with GPS distance data
     *
     * @param distanceMeters Distance traveled in meters
     * @param deltaTimeSeconds Time elapsed in seconds
     * @return Smoothed pace in seconds per km, or 0 if stopped
     */
    fun updateWithGpsDistance(distanceMeters: Double, deltaTimeSeconds: Double): Int {
        hasGps = true
        lastGpsTime = System.currentTimeMillis()
        stopDetector.updateDistanceChange()

        if (stopDetector.isStopped()) return 0
        if (deltaTimeSeconds <= 0 || distanceMeters <= 0) return smoother.getSmoothedPace()

        // Calculate pace: seconds per km
        val speedMs = distanceMeters / deltaTimeSeconds
        val rawPace = if (speedMs > 0) (1000.0 / speedMs).toInt() else 0

        return smoother.addPace(rawPace)
    }

    /**
     * Update with cadence data (for non-GPS mode)
     *
     * @param cadence Steps per minute
     * @return Smoothed pace in seconds per km, or 0 if stopped
     */
    fun updateWithCadence(cadence: Int): Int {
        stopDetector.updateCadence(cadence)

        if (stopDetector.isStopped()) return 0

        // Check GPS timeout
        val now = System.currentTimeMillis()
        if (hasGps && (now - lastGpsTime) < GPS_TIMEOUT_MS) {
            return smoother.getSmoothedPace()  // Still in GPS mode
        }

        // Switch to cadence mode
        hasGps = false

        if (cadence <= 0) return smoother.getSmoothedPace()

        // Calculate pace from cadence and stride
        val stride = strideLearner.getStrideLength(cadence)
        val speedMs = cadence * stride / 60.0f
        val rawPace = if (speedMs > 0) (1000.0 / speedMs).toInt() else 0

        return smoother.addPace(rawPace)
    }

    /**
     * Check if currently in GPS mode
     */
    fun isGpsMode(): Boolean {
        val now = System.currentTimeMillis()
        return hasGps && (now - lastGpsTime) < GPS_TIMEOUT_MS
    }

    /**
     * Reset calculator state
     */
    fun reset() {
        hasGps = false
        lastGpsTime = 0
        smoother.reset()
        stopDetector.reset()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.AdaptivePaceCalculatorTest" --info`

Expected: PASS (6 tests)

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/AdaptivePaceCalculator.kt
git add app/src/test/kotlin/com/runvision/wear/engine/AdaptivePaceCalculatorTest.kt
git commit -m "feat(engine): add AdaptivePaceCalculator with GPS/cadence mode switching"
```

---

## Task 5: RunningEngine 통합

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/engine/RunningEngine.kt`

**Step 1: Read current implementation**

Review `RunningEngine.kt` to understand integration points.

**Step 2: Modify RunningEngine**

Add new components and update `updateDistance()` and `updateCadence()` methods:

```kotlin
// Changes to RunningEngine.kt

class RunningEngine {
    // ... existing fields ...

    // NEW: Adaptive pace components
    private val strideLearner = StrideLengthLearner()
    private val paceSmoother = PaceSmoother(windowSize = 5)
    private val stopDetector = StopDetector()
    private val adaptivePaceCalc = AdaptivePaceCalculator(
        strideLearner, paceSmoother, stopDetector
    )

    // Track steps for stride learning
    private var totalStepsForLearning: Long = 0
    private var lastStepsForLearning: Long = 0

    // MODIFY: updateDistance()
    fun updateDistance(meters: Double) {
        if (!isRunning) return

        hasHealthServicesDistance = true
        distanceCalculator.setDistance(meters)

        val now = System.currentTimeMillis()
        if (lastHealthDistanceTime > 0 && meters > lastHealthDistance) {
            val deltaDistance = meters - lastHealthDistance
            val deltaTime = (now - lastHealthDistanceTime) / 1000.0

            if (deltaTime > 0.1 && deltaDistance > 0) {
                // Update stride learner with GPS data
                val stepsDelta = totalStepsForLearning - lastStepsForLearning
                if (stepsDelta > 0) {
                    strideLearner.updateWithGpsData(deltaDistance, stepsDelta)
                    lastStepsForLearning = totalStepsForLearning
                }

                // Use adaptive pace calculator
                paceSecondsPerKm = adaptivePaceCalc.updateWithGpsDistance(deltaDistance, deltaTime)
            }
        }

        lastHealthDistance = meters
        lastHealthDistanceTime = now
    }

    // MODIFY: updateCadence()
    fun updateCadence(spm: Int) {
        cadence = spm

        val now = System.currentTimeMillis()
        if (lastCadenceTime > 0 && isRunning && spm > 0) {
            val deltaSeconds = (now - lastCadenceTime) / 1000.0
            val stepsInPeriod = (spm * deltaSeconds / 60.0).toLong()
            totalSteps += stepsInPeriod
            totalStepsForLearning += stepsInPeriod

            // Use adaptive pace calculator for cadence updates
            val cadencePace = adaptivePaceCalc.updateWithCadence(spm)

            // Only use cadence pace if no GPS/Health Services distance
            if (!hasHealthServicesDistance && !hasGpsDistance) {
                paceSecondsPerKm = cadencePace
            }
        }
        lastCadenceTime = now
    }

    // MODIFY: getCurrentMetrics() - remove old pace smoothing logic
    fun getCurrentMetrics(): RunningMetrics {
        return RunningMetrics(
            elapsedSeconds = elapsedSeconds,
            distanceMeters = distanceCalculator.getTotalDistance(),
            paceSecondsPerKm = paceSecondsPerKm,  // Now using adaptive pace
            heartRate = heartRate,
            cadence = cadence,
            latitude = lastLat,
            longitude = lastLon
        )
    }

    // MODIFY: reset()
    fun reset() {
        // ... existing reset code ...
        strideLearner.reset()
        paceSmoother.reset()
        stopDetector.reset()
        adaptivePaceCalc.reset()
        totalStepsForLearning = 0
        lastStepsForLearning = 0
    }
}
```

**Step 3: Build and test**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/RunningEngine.kt
git commit -m "feat(engine): integrate AdaptivePaceCalculator into RunningEngine"
```

---

## Task 6: ExerciseService 경고 수정

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt:224`

**Step 1: Add @Suppress annotation**

```kotlin
// Line 223-227: Add @Suppress
@Deprecated("BLE is now managed by Service. Use startScanning() instead.")
@Suppress("UNUSED_PARAMETER")
fun setRLensConnection(connection: RLensConnection) {
    Log.d(TAG, "setRLensConnection() called but ignored - BLE is managed by Service")
}
```

**Step 2: Build and verify no warnings**

Run: `./gradlew :app:assembleRelease 2>&1 | grep -i warning`

Expected: No output (no warnings)

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt
git commit -m "fix(service): suppress unused parameter warning in deprecated method"
```

---

## Task 7: 최종 빌드 및 테스트

**Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: All tests PASS

**Step 2: Build release AAB**

Run: `./gradlew :app:bundleRelease`

Expected: BUILD SUCCESSFUL, AAB created at `app/build/outputs/bundle/release/app-release.aab`

**Step 3: Verify no warnings**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL with 0 warnings

**Step 4: Final commit**

```bash
git add -A
git commit -m "chore: complete adaptive pace algorithm implementation"
```

---

## Summary

| Task | Component | Status |
|------|-----------|--------|
| 1 | PaceSmoother | Pending |
| 2 | StopDetector | Pending |
| 3 | StrideLengthLearner | Pending |
| 4 | AdaptivePaceCalculator | Pending |
| 5 | RunningEngine 통합 | Pending |
| 6 | ExerciseService 경고 수정 | Pending |
| 7 | 최종 빌드 및 테스트 | Pending |
