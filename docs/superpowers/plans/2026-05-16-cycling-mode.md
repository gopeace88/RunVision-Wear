# 사이클 모드 Implementation Plan (runvision-wear)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** runvision-wear Wear OS 앱에 화면 버튼으로 전환하는 사이클 모드를 추가하되 러닝 모드 런타임 동작은 바이트 단위로 불변.

**Architecture:** 순수 JVM 테스트 가능한 코어(`CyclingMetrics`, `CyclingEngine`)를 TDD로 먼저 만들고, 그 위에 *추가만 하는* 공유 접점 배선(`ExerciseManager`/`ExerciseService`/`MainActivity`/`HomeScreen`)을 얹는다. 자전거 BLE 패킷은 `CyclingEngine.getRLensPayload()`가 만든 리매핑 `RunningMetrics`를 **무수정 `RLensConnection.sendMetrics`** 에 흘려보내 runvision-iq 바이트와 동일하게 만든다.

**Tech Stack:** Kotlin, Android (Wear OS), Jetpack Compose (Wear), Health Services, JUnit 4 + MockK, Gradle (`./gradlew :app:testDebugUnitTest`).

**참조 스펙:** `docs/superpowers/specs/2026-05-16-cycling-mode-design.md` (커밋 59e2516 + 정정 9825e09)

**베이스라인 (2026-05-16):** `./gradlew :app:testDebugUnitTest` = **90 tests, 0 fail (green)**. 모든 작업은 이 그린 위에서 시작하며, 회귀는 "이 14개 파일 git diff 0줄 + 90개 기존 테스트 그대로 통과"로 증명한다.

**diff 0 보장 파일 (절대 수정 금지):**
`engine/RunningEngine.kt`, `engine/DistanceCalculator.kt`, `engine/SpeedCalculator.kt`, `engine/PaceCalculator.kt`, `engine/PaceSmoother.kt`, `engine/AdaptivePaceCalculator.kt`, `engine/StrideLengthLearner.kt`, `engine/StopDetector.kt`, `ble/RLensProtocol.kt`, `ble/RLensConnection.kt`, `ble/RLensScanner.kt`, `ui/screens/RunningScreen.kt`, `ui/components/MetricItem.kt`, `data/RunningMetrics.kt`, `data/db/WorkoutSession.kt`
(`SpeedCalculator`·`DistanceCalculator`·`MetricItem`은 **인스턴스화 재사용만** — 파일 수정 0.)

**테스트 커버리지 정직성:** 자동 회귀 증거 = 순수 JVM 테스트(Task 1·2) + 위 14파일 git diff 0. `ExerciseManager`/`ExerciseService`/`MainActivity`/스크린은 기존 유닛 테스트가 **없고** Health Services·Compose 의존이라 순수 JVM 테스트 불가 → Task 4·5·6·7·8은 컴파일 + **전체 90+ 스위트 회귀** + Task 9의 실기기 체크리스트로만 검증한다. 플랜은 이 한계를 숨기지 않는다.

---

### Task 1: CyclingMetrics 데이터 클래스 (순수 JVM, TDD)

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/data/CyclingMetrics.kt`
- Test: `app/src/test/kotlin/com/runvision/wear/data/CyclingMetricsTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

Create `app/src/test/kotlin/com/runvision/wear/data/CyclingMetricsTest.kt`:

```kotlin
package com.runvision.wear.data

import org.junit.Assert.*
import org.junit.Test

class CyclingMetricsTest {

    @Test
    fun `elapsed formatted as m colon ss`() {
        assertEquals("3:05", CyclingMetrics(elapsedSeconds = 185).elapsedFormatted)
        assertEquals("0:00", CyclingMetrics(elapsedSeconds = 0).elapsedFormatted)
    }

    @Test
    fun `speed formatted to one decimal locale-safe`() {
        assertEquals("25.5", CyclingMetrics(speedKmh = 25.55f).speedFormatted)
        assertEquals("30.0", CyclingMetrics(speedKmh = 30.0f).speedFormatted)
        assertEquals("0.0", CyclingMetrics(speedKmh = 0f).speedFormatted)
    }

    @Test
    fun `distance km formatted like running metrics`() {
        assertEquals("5.2", CyclingMetrics(distanceMeters = 5230f).distanceKmFormatted)
        assertEquals("0.0", CyclingMetrics(distanceMeters = 0f).distanceKmFormatted)
    }

    @Test
    fun `altitude formatted as integer meters`() {
        assertEquals("1200", CyclingMetrics(altitudeM = 1200).altitudeFormatted)
        assertEquals("0", CyclingMetrics(altitudeM = 0).altitudeFormatted)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.data.CyclingMetricsTest"`
Expected: **BUILD FAILED**, `compileDebugUnitTestKotlin FAILED` with `Unresolved reference: CyclingMetrics`.

- [ ] **Step 3: 최소 구현**

Create `app/src/main/kotlin/com/runvision/wear/data/CyclingMetrics.kt`:

```kotlin
package com.runvision.wear.data

/**
 * Honest cycling metrics for the watch screen.
 * BLE/HUD slot remapping (speed×60, altitude→cadence slot) is NOT done here —
 * that is CyclingEngine.getRLensPayload()'s job. This class is what the user sees.
 */
data class CyclingMetrics(
    val elapsedSeconds: Int = 0,
    val distanceMeters: Float = 0f,
    val speedKmh: Float = 0f,
    val heartRate: Int = 0,
    val altitudeM: Int = 0
) {
    val elapsedFormatted: String
        get() {
            val min = elapsedSeconds / 60
            val sec = elapsedSeconds % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }

    // Locale-safe 1-decimal (mirrors RunningMetrics.distanceKmFormatted approach;
    // some locales render '.' as ',' with String.format).
    val speedFormatted: String
        get() {
            val intPart = speedKmh.toInt()
            val decPart = ((speedKmh - intPart) * 10).toInt()
            return "$intPart.$decPart"
        }

    val distanceKmFormatted: String
        get() {
            val km = distanceMeters / 1000f
            val intPart = km.toInt()
            val decPart = ((km - intPart) * 10).toInt()
            return "$intPart.$decPart"
        }

    val altitudeFormatted: String
        get() = "$altitudeM"
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.data.CyclingMetricsTest"`
Expected: **BUILD SUCCESSFUL**, 4 tests pass.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/data/CyclingMetrics.kt app/src/test/kotlin/com/runvision/wear/data/CyclingMetricsTest.kt
git commit -m "feat: CyclingMetrics 화면용 데이터 클래스 추가

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: CyclingEngine (순수 JVM, TDD) — 핵심

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/engine/CyclingEngine.kt`
- Test: `app/src/test/kotlin/com/runvision/wear/engine/CyclingEngineTest.kt`
- Reuse (수정 금지): `engine/SpeedCalculator.kt`, `engine/DistanceCalculator.kt`, `data/CyclingMetrics.kt`, `data/RunningMetrics.kt`

- [ ] **Step 1: 실패 테스트 작성**

Create `app/src/test/kotlin/com/runvision/wear/engine/CyclingEngineTest.kt`:

```kotlin
package com.runvision.wear.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CyclingEngineTest {

    private lateinit var engine: CyclingEngine

    @Before
    fun setup() {
        engine = CyclingEngine()
        engine.reset()
        engine.start()
    }

    // ---- velocity ×60 scaling: byte-mirror of runvision-iq CyclingStrategy.mc:35 ----

    @Test
    fun `scaleSpeedToVelocity matches runvision-iq golden vectors`() {
        assertEquals(1530, CyclingEngine.scaleSpeedToVelocity(25.5f))
        assertEquals(1800, CyclingEngine.scaleSpeedToVelocity(30.0f))
        assertEquals(1533, CyclingEngine.scaleSpeedToVelocity(25.55f)) // decimal preserved
        assertEquals(0, CyclingEngine.scaleSpeedToVelocity(0f))
        assertEquals(0, CyclingEngine.scaleSpeedToVelocity(-5f)) // clamp
    }

    // ---- tick ----

    @Test
    fun `tick increments only when running`() {
        engine.tick(); engine.tick()
        assertEquals(2, engine.getCurrentMetrics().elapsedSeconds)
        engine.pause()
        engine.tick()
        assertEquals(2, engine.getCurrentMetrics().elapsedSeconds)
        engine.resume()
        engine.tick()
        assertEquals(3, engine.getCurrentMetrics().elapsedSeconds)
    }

    // ---- honest metrics ----

    @Test
    fun `heart rate altitude distance flow into honest metrics`() {
        engine.updateHeartRate(150)
        engine.updateAltitude(1200.0)
        engine.updateDistance(5000.0)
        val m = engine.getCurrentMetrics()
        assertEquals(150, m.heartRate)
        assertEquals(1200, m.altitudeM)
        assertEquals(5000f, m.distanceMeters, 0.01f)
    }

    @Test
    fun `speed in km per h equals gps m per s times 3point6`() {
        // SpeedCalculator: first point 0, then ~10 m/s over ~100m/10s
        engine.updateGps(37.5663, 126.9779, 1000L)
        engine.updateGps(37.5673, 126.9779, 11000L)
        val kmh = engine.getCurrentMetrics().speedKmh
        // ~10 m/s -> ~36 km/h (wide band: GPS haversine + smoothing)
        assertTrue("speedKmh ~36 but was $kmh", kmh in 18f..54f)
    }

    @Test
    fun `health services distance preferred over gps`() {
        engine.updateDistance(2000.0)               // HS distance arrives
        engine.updateGps(37.5663, 126.9779, 1000L)  // GPS must NOT overwrite
        engine.updateGps(37.5800, 126.9779, 2000L)
        assertEquals(2000f, engine.getCurrentMetrics().distanceMeters, 0.01f)
    }

    @Test
    fun `updates before start are ignored for gps and distance`() {
        val fresh = CyclingEngine()
        fresh.reset()
        fresh.updateGps(37.5663, 126.9779, 1000L)
        fresh.updateDistance(999.0)
        assertEquals(0f, fresh.getCurrentMetrics().distanceMeters, 0.01f)
    }

    // ---- BLE remap payload (drives RunningMetrics through unmodified sendMetrics) ----

    @Test
    fun `getRLensPayload remaps cycling values into RunningMetrics fields`() {
        engine.updateHeartRate(150)
        engine.updateAltitude(1200.0)
        engine.updateDistance(5000.0)
        engine.tick(); engine.tick() // elapsed = 2
        val p = engine.getRLensPayload()
        assertEquals(2, p.elapsedSeconds)              // -> 0x03
        assertEquals(5000f, p.distanceMeters, 0.01f)   // -> 0x06
        assertEquals(150, p.heartRate)                 // -> 0x0B (always HR on Wear)
        assertEquals(1200, p.cadence)                  // altitude -> 0x0E slot
        // 0x07 slot = speed×60; speed is GPS-derived so assert via the pure fn invariant
        assertEquals(
            CyclingEngine.scaleSpeedToVelocity(engine.getCurrentMetrics().speedKmh),
            p.paceSecondsPerKm
        )
    }

    @Test
    fun `reset clears all state`() {
        engine.updateHeartRate(150); engine.updateAltitude(800.0); engine.tick()
        engine.reset()
        val m = engine.getCurrentMetrics()
        assertEquals(0, m.elapsedSeconds)
        assertEquals(0, m.heartRate)
        assertEquals(0, m.altitudeM)
        assertEquals(0f, m.distanceMeters, 0.01f)
        assertFalse(engine.isActive())
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.CyclingEngineTest"`
Expected: **BUILD FAILED**, `Unresolved reference: CyclingEngine`.

- [ ] **Step 3: 최소 구현**

Create `app/src/main/kotlin/com/runvision/wear/engine/CyclingEngine.kt`:

```kotlin
package com.runvision.wear.engine

import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.data.RunningMetrics

/**
 * Cycling Engine — parallel to RunningEngine, ZERO modification to it.
 *
 * Produces two views:
 *  - getCurrentMetrics(): honest CyclingMetrics for the watch screen
 *  - getRLensPayload(): RunningMetrics with fields remapped so the UNMODIFIED
 *    RLensConnection.sendMetrics() emits bytes identical to runvision-iq's
 *    CyclingStrategy (speed×60 -> 0x07, altitude -> 0x0E, HR -> 0x0B).
 *
 * Wear OS watches always have an optical HR sensor active during a session,
 * so runvision-iq's 30s HR-lock / totalAscent fallback is dead code here and
 * is intentionally NOT implemented (spec §2.3).
 *
 * Reuses SpeedCalculator + DistanceCalculator (mode-agnostic, instance-only).
 */
class CyclingEngine {

    private val speedCalculator = SpeedCalculator()
    private val distanceCalculator = DistanceCalculator()

    private var isRunning: Boolean = false
    private var elapsedSeconds: Int = 0
    private var heartRate: Int = 0
    private var altitudeM: Int = 0
    private var speedKmh: Float = 0f
    private var distanceM: Float = 0f
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var hasHealthServicesDistance: Boolean = false

    companion object {
        /**
         * runvision-iq CyclingStrategy.mc:35 byte-mirror.
         * rLens divides 0x07 by 60 to display, so send km/h ×60 (decimals preserved).
         * iq: (speedKmh * 60 + 0.5).toNumber(); encodeUINT32 clamps [0, 2147483647].
         */
        fun scaleSpeedToVelocity(speedKmh: Float): Int =
            ((speedKmh.toDouble() * 60.0) + 0.5).toInt().coerceIn(0, Int.MAX_VALUE)
    }

    fun start() { isRunning = true }
    fun pause() { isRunning = false }
    fun resume() { isRunning = true }
    fun stop() { isRunning = false }
    fun isActive(): Boolean = isRunning

    fun tick() {
        if (isRunning) elapsedSeconds++
    }

    /** HR may arrive before start (MeasureClient); store latest unconditionally — mirrors RunningEngine. */
    fun updateHeartRate(bpm: Int) { heartRate = bpm }

    /** Current altitude in meters (GPS-derived; barometer-less watches are lower accuracy — spec caveat). */
    fun updateAltitude(meters: Double) { altitudeM = meters.toInt() }

    fun updateGps(lat: Double, lon: Double, timestamp: Long) {
        if (!isRunning) return
        lastLat = lat
        lastLon = lon
        val speedMs = speedCalculator.calculateSpeed(lat, lon, timestamp)
        speedKmh = speedMs * 3.6f
        if (!hasHealthServicesDistance) {
            distanceCalculator.addPoint(lat, lon)
            distanceM = distanceCalculator.getTotalDistance()
        }
    }

    /** Health Services sensor-fused distance — preferred over raw GPS (mirrors RunningEngine policy). */
    fun updateDistance(meters: Double) {
        if (!isRunning) return
        hasHealthServicesDistance = true
        distanceM = meters.toFloat()
    }

    fun getCurrentMetrics(): CyclingMetrics = CyclingMetrics(
        elapsedSeconds = elapsedSeconds,
        distanceMeters = distanceM,
        speedKmh = speedKmh,
        heartRate = heartRate,
        altitudeM = altitudeM
    )

    /**
     * Remapped RunningMetrics consumed by the UNMODIFIED RLensConnection.sendMetrics():
     *   createExerciseTimePacket(elapsedSeconds) -> 0x03
     *   createPacePacket(paceSecondsPerKm)       -> 0x07  (= speed×60)
     *   createHeartRatePacket(heartRate)         -> 0x0B
     *   createCadencePacket(cadence)             -> 0x0E  (= altitude m)
     *   createDistancePacket(distanceMeters)     -> 0x06
     */
    fun getRLensPayload(): RunningMetrics = RunningMetrics(
        elapsedSeconds = elapsedSeconds,
        distanceMeters = distanceM,
        paceSecondsPerKm = scaleSpeedToVelocity(speedKmh),
        heartRate = heartRate,
        cadence = altitudeM,
        latitude = lastLat,
        longitude = lastLon
    )

    fun reset() {
        isRunning = false
        elapsedSeconds = 0
        heartRate = 0
        altitudeM = 0
        speedKmh = 0f
        distanceM = 0f
        lastLat = 0.0
        lastLon = 0.0
        hasHealthServicesDistance = false
        speedCalculator.reset()
        distanceCalculator.reset()
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.engine.CyclingEngineTest"`
Expected: **BUILD SUCCESSFUL**, 9 tests pass.

- [ ] **Step 5: 전체 회귀 확인**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**. (베이스라인 90 + 신규 13 = 103 tests, 0 fail.)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/engine/CyclingEngine.kt app/src/test/kotlin/com/runvision/wear/engine/CyclingEngineTest.kt
git commit -m "feat: CyclingEngine — 정직한 메트릭 + iq 바이트 미러 리매핑 페이로드

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: ExerciseMode enum + ExerciseManager 추가 변경 (컴파일 검증)

> 이 Task는 Health Services 의존이라 순수 JVM 유닛 테스트 불가. 검증 = 컴파일 + 전체 스위트 회귀(기존 90 그대로). 기능 검증은 Task 9 실기기.

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/service/ExerciseMode.kt`
- Modify: `app/src/main/kotlin/com/runvision/wear/health/ExerciseManager.kt`

- [ ] **Step 1: ExerciseMode enum 생성**

Create `app/src/main/kotlin/com/runvision/wear/service/ExerciseMode.kt`:

```kotlin
package com.runvision.wear.service

/** Exercise mode. Default RUNNING everywhere keeps the running path byte-identical. */
enum class ExerciseMode { RUNNING, CYCLING }
```

- [ ] **Step 2: ExerciseManager — altitude 콜백 추가 (additive)**

In `app/src/main/kotlin/com/runvision/wear/health/ExerciseManager.kt`, find (line 36-37):

```kotlin
    var onDistanceUpdate: ((Double) -> Unit)? = null  // Distance in meters
    var onStepsDeltaUpdate: ((Long) -> Unit)? = null  // Real step deltas for stride learning
```

Replace with (add one new nullable callback — existing ones untouched):

```kotlin
    var onDistanceUpdate: ((Double) -> Unit)? = null  // Distance in meters
    var onStepsDeltaUpdate: ((Long) -> Unit)? = null  // Real step deltas for stride learning
    var onAltitudeUpdate: ((Double) -> Unit)? = null  // Current altitude (cycling only; running never references this)
```

- [ ] **Step 3: ExerciseManager — LOCATION 블록에서 altitude 추출 (additive, onLocationUpdate 무변경)**

Find (line 62-69):

```kotlin
            update.latestMetrics.getData(DataType.LOCATION)?.lastOrNull()?.let { location ->
                Log.d(TAG, "Location: ${location.value.latitude}, ${location.value.longitude}")
                onLocationUpdate?.invoke(
                    location.value.latitude,
                    location.value.longitude,
                    System.currentTimeMillis()
                )
            }
```

Replace with (existing onLocationUpdate call byte-identical; altitude added after it):

```kotlin
            update.latestMetrics.getData(DataType.LOCATION)?.lastOrNull()?.let { location ->
                Log.d(TAG, "Location: ${location.value.latitude}, ${location.value.longitude}")
                onLocationUpdate?.invoke(
                    location.value.latitude,
                    location.value.longitude,
                    System.currentTimeMillis()
                )
                // Cycling altitude. SDK sentinel for "no altitude" is a non-physical
                // value (e.g. Double.MAX_VALUE); a finite plausible-range check is
                // robust across health-services-client versions (constant name varies).
                val alt = location.value.altitude
                if (alt.isFinite() && alt > -1000.0 && alt < 10000.0) {
                    onAltitudeUpdate?.invoke(alt)
                }
            }
```

- [ ] **Step 4: ExerciseManager — startExercise 타입 파라미터화 (additive, 기본값=RUNNING)**

Find (line 144-147):

```kotlin
    /**
     * Start running exercise session
     */
    suspend fun startExercise() {
```

Replace with:

```kotlin
    /**
     * Start exercise session.
     * @param exerciseType RUNNING (default — running path byte-identical) or BIKING.
     */
    suspend fun startExercise(exerciseType: ExerciseType = ExerciseType.RUNNING) {
```

Then find (line 169-171):

```kotlin
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            val runningCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.RUNNING)
            Log.d(TAG, "Supported data types: ${runningCapabilities.supportedDataTypes}")
```

Replace with:

```kotlin
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            val runningCapabilities = capabilities.getExerciseTypeCapabilities(exerciseType)
            Log.d(TAG, "Supported data types ($exerciseType): ${runningCapabilities.supportedDataTypes}")
```

Then find (line 198):

```kotlin
            val config = ExerciseConfig.builder(ExerciseType.RUNNING)
```

Replace with:

```kotlin
            val config = ExerciseConfig.builder(exerciseType)
```

> Note: `androidx.health.services.client.data.ExerciseType` is already imported via `import androidx.health.services.client.data.*` (line 11). No new import needed. The data-type selection logic (HR/LOCATION/STEPS/DISTANCE) is left as-is — capability-gated, harmless for BIKING.

- [ ] **Step 5: 컴파일 + 전체 회귀 확인**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**. 103 tests, 0 fail (no new tests; proves the additive ExerciseManager change didn't break compilation or the existing 90 + Task 1/2's 13).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseMode.kt app/src/main/kotlin/com/runvision/wear/health/ExerciseManager.kt
git commit -m "feat: ExerciseMode enum + ExerciseManager 타입 파라미터화·altitude 콜백 (additive)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: ExerciseService — 모드 분기 배선 (additive, 러닝 경로 바이트 동일)

> 검증 = 컴파일 + 전체 스위트 회귀. 기능은 Task 9 실기기. **러닝 분기(`else`)는 현재 코드와 한 글자도 다르지 않게 유지**한다.

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt`

- [ ] **Step 1: import 추가**

Find (line 28-29):

```kotlin
import com.runvision.wear.engine.RunningEngine
import com.runvision.wear.health.ExerciseManager
```

Replace with:

```kotlin
import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.engine.CyclingEngine
import com.runvision.wear.engine.RunningEngine
import com.runvision.wear.health.ExerciseManager
import androidx.health.services.client.data.ExerciseType
```

- [ ] **Step 2: 필드 추가 (cyclingEngine, mode, _cyclingMetrics)**

Find (line 60-61):

```kotlin
    private lateinit var runningEngine: RunningEngine
    private lateinit var exerciseManager: ExerciseManager
```

Replace with:

```kotlin
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
```

Find (line 89-90):

```kotlin
    private val _metrics = MutableStateFlow(RunningMetrics())
    val metrics: StateFlow<RunningMetrics> = _metrics
```

Replace with:

```kotlin
    private val _metrics = MutableStateFlow(RunningMetrics())
    val metrics: StateFlow<RunningMetrics> = _metrics

    private val _cyclingMetrics = MutableStateFlow(CyclingMetrics())
    val cyclingMetrics: StateFlow<CyclingMetrics> = _cyclingMetrics
```

- [ ] **Step 3: onCreate — cyclingEngine 생성 + 콜백 모드 디스패치**

Find (line 108):

```kotlin
        runningEngine = RunningEngine(this)
```

Replace with:

```kotlin
        runningEngine = RunningEngine(this)
        cyclingEngine = CyclingEngine()
```

Find the whole callback block (line 114-137):

```kotlin
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
            onStepsDeltaUpdate = { steps ->
                Log.d(TAG, "Step delta: $steps")
                runningEngine.updateStepsDelta(steps)
            }
        }
```

Replace with (RUNNING branch identical to original calls; CYCLING branch added):

```kotlin
        exerciseManager = ExerciseManager(this).apply {
            onHeartRateUpdate = { hr ->
                Log.d(TAG, "HR update: $hr")
                if (mode == ExerciseMode.CYCLING) {
                    cyclingEngine.updateHeartRate(hr)
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
```

- [ ] **Step 4: startExercise() — 모드 분기**

Find (line 304-338, the whole `startExercise()` body). Replace the section from `runningEngine.reset()` (line 315) through `startTimer()` (line 337). Find:

```kotlin
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
```

Replace with:

```kotlin
        if (mode == ExerciseMode.CYCLING) {
            cyclingEngine.reset()
            cyclingEngine.start()
        } else {
            runningEngine.reset()
            runningEngine.start()
        }
        _isRunning.value = true

        // Create new workout session in DB
        currentSessionId = UUID.randomUUID().toString()
        val sessionType = if (mode == ExerciseMode.CYCLING) "CYCLING" else "RUNNING"
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
            exerciseManager.startExercise(
                if (mode == ExerciseMode.CYCLING) ExerciseType.BIKING else ExerciseType.RUNNING
            )
        }

        // Start 1Hz timer
        startTimer()
```

> The running branch is behaviorally identical: `WorkoutSession(sessionId, startTime, exerciseType="RUNNING")` equals the old `WorkoutSession(sessionId, startTime)` because `exerciseType` already defaults to `"RUNNING"` (WorkoutSession.kt:20). `exerciseManager.startExercise(ExerciseType.RUNNING)` equals old `startExercise()` (default param).

- [ ] **Step 5: pauseExercise() / stopExercise() — 모드 분기**

Find `pauseExercise()` (line 343-357):

```kotlin
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
```

Replace with:

```kotlin
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
```

Find in `stopExercise()` (line 364-365):

```kotlin
        val finalMetrics = runningEngine.getCurrentMetrics()
        runningEngine.stop()
```

Replace with:

```kotlin
        val finalMetrics = if (mode == ExerciseMode.CYCLING) {
            cyclingEngine.getRLensPayload()  // remapped RunningMetrics: distance+elapsed valid for session summary
        } else {
            runningEngine.getCurrentMetrics()
        }
        if (mode == ExerciseMode.CYCLING) cyclingEngine.stop() else runningEngine.stop()
```

> `stopExercise` only reads `finalMetrics.distanceMeters` and `finalMetrics.elapsedSeconds` (line 383-384) for the session summary — both correct in the cycling payload. `avgPace`/`avgCadence` come from DB samples (next step writes them as 0 for cycling), so no garbage. Running branch identical.

- [ ] **Step 6: startTimer() — 모드 분기 (러닝 루프 바이트 동일, 사이클 2초·정직 UI·DB 0)**

Find the timer body inside `startTimer()` (line 436-475), from `runningEngine.tick()` through `updateNotification(currentMetrics)`:

```kotlin
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
```

Replace with (RUNNING branch is the original code verbatim; CYCLING is parallel):

```kotlin
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
```

> `updateNotification(payload)` for cycling reuses the existing function (takes `RunningMetrics`); it only renders elapsed + HR (line 577), both valid in the payload. No new code path in updateNotification.

- [ ] **Step 7: 컴파일 + 전체 회귀 확인**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 103 tests, 0 fail. (No new tests — proves additive wiring compiles and the diff-0 engine/protocol tests still pass.)

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt
git commit -m "feat: ExerciseService 모드 분기 — 사이클 엔진·2초 전송·정직 UI·DB (러닝 경로 불변)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: HomeScreen — 달리기/자전거 버튼 (additive, 컴파일 검증)

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt`

- [ ] **Step 1: 시그니처 + 버튼 2개로 교체**

Replace the entire body of `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt` with:

```kotlin
package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.runvision.wear.ble.RLensConnection

@Composable
fun HomeScreen(
    connectionState: RLensConnection.ConnectionState,
    onRunClick: () -> Unit,
    onCycleClick: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        }
    ) {
        ScalingLazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            item {
                Text(
                    text = "RunVision",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Button(
                    onClick = onRunClick,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(horizontal = 8.dp),
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Text(
                        text = "달리기",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Button(
                    onClick = onCycleClick,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(horizontal = 8.dp),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text(
                        text = "자전거",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                val (statusText, statusColor) = when (connectionState) {
                    RLensConnection.ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                    RLensConnection.ConnectionState.CONNECTING -> "Connecting.." to Color(0xFFFF9800)
                    RLensConnection.ConnectionState.RECONNECTING -> "Reconnecting.." to Color(0xFFFF9800)
                    RLensConnection.ConnectionState.SCANNING -> "Scanning.." to Color(0xFF2196F3)
                    RLensConnection.ConnectionState.NOT_FOUND -> "Not Found" to Color(0xFFF44336)
                    RLensConnection.ConnectionState.DISCONNECTED -> "READY" to Color.Gray
                }
                Text(
                    text = statusText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}
```

> This will not compile until Task 6 updates the `HomeScreen(...)` call site in MainActivity (signature changed `onStartClick` → `onRunClick` + `onCycleClick`). That is expected and handled in Task 6; do not commit this task alone — commit together with Task 6 (Step where instructed).

- [ ] **Step 2: 커밋은 Task 6 이후 합쳐서** — see Task 6 Step 4.

---

### Task 6: MainActivity — 사이클 라우트 + 모드 타이밍 (additive, 컴파일 검증)

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/MainActivity.kt`

- [ ] **Step 1: import + cyclingMetrics 상태 추가**

Find (line 27-33):

```kotlin
import com.runvision.wear.ble.RLensConnection
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.service.ExerciseService
import com.runvision.wear.ui.screens.HomeScreen
import com.runvision.wear.ui.screens.RunningScreen
import com.runvision.wear.ui.theme.RunVisionWearTheme
import androidx.wear.ambient.AmbientLifecycleObserver
```

Replace with:

```kotlin
import com.runvision.wear.ble.RLensConnection
import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.service.ExerciseMode
import com.runvision.wear.service.ExerciseService
import com.runvision.wear.ui.screens.CyclingScreen
import com.runvision.wear.ui.screens.HomeScreen
import com.runvision.wear.ui.screens.RunningScreen
import com.runvision.wear.ui.theme.RunVisionWearTheme
import androidx.wear.ambient.AmbientLifecycleObserver
```

Find (line 56):

```kotlin
    private val metrics = mutableStateOf(RunningMetrics())
```

Replace with:

```kotlin
    private val metrics = mutableStateOf(RunningMetrics())
    private val cyclingMetrics = mutableStateOf(CyclingMetrics())
```

- [ ] **Step 2: 서비스 cyclingMetrics 수집 (additive collector)**

Find (line 115-122):

```kotlin
            // Collect metrics from service
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.metrics?.collect { m ->
                        metrics.value = m
                    }
                }
            }
```

Replace with:

```kotlin
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
                    exerciseService?.cyclingMetrics?.collect { m ->
                        cyclingMetrics.value = m
                    }
                }
            }
```

- [ ] **Step 3: HomeScreen 호출부 + cycling 라우트**

Find (line 211-251):

```kotlin
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
```

Replace with (running branch logic byte-identical, just gated by `setMode(RUNNING)` to clear any stale CYCLING from a prior session on the same service instance):

```kotlin
                    composable("home") {
                        HomeScreen(
                            connectionState = connectionState.value,
                            onRunClick = {
                                Log.d(TAG, "달리기 pressed, starting scan via Service")
                                // Reset mode in case a prior cycling session left it CYCLING
                                exerciseService?.setMode(ExerciseMode.RUNNING)
                                if (connectionState.value == RLensConnection.ConnectionState.CONNECTED) {
                                    startRunning()
                                    nav.navigate("running")
                                } else {
                                    val intent = Intent(this@MainActivity, ExerciseService::class.java)
                                    startForegroundService(intent)
                                    exerciseService?.startScanning()
                                }
                            },
                            onCycleClick = {
                                Log.d(TAG, "자전거 pressed, starting scan via Service")
                                // setMode MUST happen before scan/startExercise: the BLE
                                // CONNECTED callback auto-calls startExercise() which reads mode.
                                exerciseService?.setMode(ExerciseMode.CYCLING)
                                if (connectionState.value == RLensConnection.ConnectionState.CONNECTED) {
                                    startRunning()
                                    nav.navigate("cycling")
                                } else {
                                    val intent = Intent(this@MainActivity, ExerciseService::class.java)
                                    startForegroundService(intent)
                                    exerciseService?.startScanning()
                                }
                            }
                        )
                    }
                    composable("running") {
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
                    composable("cycling") {
                        BackHandler {
                            Log.d(TAG, "Back pressed in cycling screen, stopping exercise")
                            stopRunning()
                            nav.popBackStack()
                        }

                        CyclingScreen(
                            metrics = cyclingMetrics.value,
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
```

> `startRunning()`/`stopRunning()`/`togglePause()` are mode-agnostic (they call `exerciseService?.startExercise()` etc., and the service branches on `mode`). Reusing them for cycling is correct — no new MainActivity methods. The LaunchedEffect auto-nav (line 191-205) targets `"running"` only; that path is for activity-recreation while a session is live and is acceptable to leave running-only for Phase 1 (cycling re-entry after screen-off navigates home then user re-taps; documented limitation).

- [ ] **Step 4: 컴파일 + 전체 회귀 확인 (Task 5 + 7 함께 — CyclingScreen 필요)**

> CyclingScreen이 아직 없으므로 이 시점 컴파일은 실패한다. Task 7(CyclingScreen) 완료 후 함께 검증·커밋한다. Task 7로 진행.

---

### Task 7: CyclingScreen + Task 5·6 통합 검증·커밋

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/ui/screens/CyclingScreen.kt`

- [ ] **Step 1: CyclingScreen 생성 (RunningScreen 미러, 정직 라벨, 기존 아이콘 재사용)**

Create `app/src/main/kotlin/com/runvision/wear/ui/screens/CyclingScreen.kt`:

```kotlin
package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.runvision.wear.R
import com.runvision.wear.data.CyclingMetrics
import com.runvision.wear.ui.components.MetricItem
import com.runvision.wear.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CyclingScreen(
    metrics: CyclingMetrics,
    isAmbient: Boolean = false,
    isPaused: Boolean = false,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onScreenTouch: () -> Unit = {}
) {
    if (isAmbient) {
        AmbientCyclingScreen(metrics)
    } else {
        InteractiveCyclingScreen(metrics, isPaused, onPauseClick, onStopClick, onScreenTouch)
    }
}

@Composable
private fun AmbientCyclingScreen(metrics: CyclingMetrics) {
    val scrollState = rememberScrollState()

    Scaffold(
        positionIndicator = { PositionIndicator(scrollState = scrollState) }
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = metrics.elapsedFormatted,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(text = "♥", fontSize = 16.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${metrics.heartRate}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${metrics.speedFormatted} km/h",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun InteractiveCyclingScreen(
    metrics: CyclingMetrics,
    isPaused: Boolean,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onScreenTouch: () -> Unit
) {
    val currentTime = remember(metrics.elapsedSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onScreenTouch() })
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 12.dp)
                .padding(bottom = 44.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentTime,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(text = " │ ", fontSize = 18.sp, color = Color.Gray, maxLines = 1)
                Text(
                    text = if (isPaused) "⏸ ${metrics.elapsedFormatted}" else metrics.elapsedFormatted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaused) Color(0xFFFFEB3B) else Color(0xFF00FF00),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Speed (km/h) — honest label; BLE remap (speed×60→0x07) is in CyclingEngine
                    MetricItem(
                        icon = painterResource(R.drawable.ic_runner),
                        value = metrics.speedFormatted,
                        color = CyanPace,
                        modifier = Modifier.weight(1f)
                    )
                    // Altitude (m)
                    MetricItem(
                        icon = painterResource(R.drawable.ic_shoe),
                        value = metrics.altitudeFormatted,
                        color = GreenCadence,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricItem(
                        icon = painterResource(R.drawable.ic_route),
                        value = metrics.distanceKmFormatted,
                        color = OrangeDistance,
                        modifier = Modifier.weight(1f)
                    )
                    MetricItem(
                        icon = painterResource(R.drawable.ic_heart),
                        value = "${metrics.heartRate}",
                        color = RedHeart,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CompactButton(
                onClick = onPauseClick,
                colors = if (isPaused) {
                    ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF4CAF50))
                } else {
                    ButtonDefaults.primaryButtonColors()
                }
            ) {
                Icon(
                    painter = painterResource(
                        if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
                    ),
                    contentDescription = if (isPaused) "Resume" else "Pause"
                )
            }
            CompactButton(
                onClick = onStopClick,
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = "Stop"
                )
            }
        }
    }
}
```

> Icons/colors reuse existing `R.drawable.*` and theme colors (`CyanPace`, `GreenCadence`, `OrangeDistance`, `RedHeart` — confirmed used in RunningScreen.kt). No new resources (YAGNI). `ic_shoe` is reused as the altitude icon — acceptable placeholder; spec mandated no new icon resources.

- [ ] **Step 2: 컴파일 확인 (Task 5+6+7 통합)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: **BUILD SUCCESSFUL** (HomeScreen new signature, MainActivity cycling route, CyclingScreen all resolve together).

- [ ] **Step 3: 전체 회귀 확인**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 103 tests, 0 fail.

- [ ] **Step 4: 커밋 (Task 5+6+7 한 커밋 — 시그니처 변경이 상호 의존)**

```bash
git add app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt \
        app/src/main/kotlin/com/runvision/wear/MainActivity.kt \
        app/src/main/kotlin/com/runvision/wear/ui/screens/CyclingScreen.kt
git commit -m "feat: HomeScreen 달리기/자전거 버튼 + cycling 라우트·화면·모드 타이밍

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: 최종 회귀 증명 + 빌드 검증 + 실기기 체크리스트

**Files:** (검증만 — 코드 변경 없음)

- [ ] **Step 1: 러닝 회귀 = diff-0 14파일 증명**

Run:

```bash
git diff --stat 59e2516~1 HEAD -- \
  app/src/main/kotlin/com/runvision/wear/engine/RunningEngine.kt \
  app/src/main/kotlin/com/runvision/wear/engine/DistanceCalculator.kt \
  app/src/main/kotlin/com/runvision/wear/engine/SpeedCalculator.kt \
  app/src/main/kotlin/com/runvision/wear/engine/PaceCalculator.kt \
  app/src/main/kotlin/com/runvision/wear/engine/PaceSmoother.kt \
  app/src/main/kotlin/com/runvision/wear/engine/AdaptivePaceCalculator.kt \
  app/src/main/kotlin/com/runvision/wear/engine/StrideLengthLearner.kt \
  app/src/main/kotlin/com/runvision/wear/engine/StopDetector.kt \
  app/src/main/kotlin/com/runvision/wear/ble/RLensProtocol.kt \
  app/src/main/kotlin/com/runvision/wear/ble/RLensConnection.kt \
  app/src/main/kotlin/com/runvision/wear/ble/RLensScanner.kt \
  app/src/main/kotlin/com/runvision/wear/ui/screens/RunningScreen.kt \
  app/src/main/kotlin/com/runvision/wear/ui/components/MetricItem.kt \
  app/src/main/kotlin/com/runvision/wear/data/RunningMetrics.kt \
  app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSession.kt
```

Expected: **empty output** (zero changes to any running-critical file). If anything prints, the running-side-effect-zero constraint is violated — STOP and revert the offending change.

- [ ] **Step 2: 전체 테스트 그린**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 103 tests, 0 fail (baseline 90 untouched + 13 new).

- [ ] **Step 3: 앱 빌드 검증**

Run: `./gradlew :app:assembleDebug`
Expected: **BUILD SUCCESSFUL** (APK compiles end-to-end).

- [ ] **Step 4: 실기기 검증 체크리스트 (자동 테스트가 못 잡는 부분 — 스펙 §5.4)**

수동 확인 (실 워치 + rLens):
- [ ] 달리기 시작 → 변경 전 빌드와 BLE 패킷 바이트 동일 (logcat `Sending: HR=...` 비교) — 러닝 사이드이펙트 0 확인
- [ ] 자전거 시작 → rLens HUD에 속도(소수점 보존)/거리/HR/고도 표시, **2초** 주기 갱신 (logcat `Cycling: speed=...`)
- [ ] 자전거 → 종료(back/stop) → 다시 달리기 시작 → 러닝 정상 (stale CYCLING 모드 안 남음)
- [ ] 자전거 세션 DB: `exerciseType="CYCLING"`, avgPace/avgCadence = 0 (쓰레기값 아님)
- [ ] 이미 CONNECTED 상태에서 자전거 탭 → 사이클 화면으로 진입, 러닝으로 안 샘

- [ ] **Step 5: 회귀 증명 결과를 커밋 메시지에 남기지 않음** — 코드 변경이 없으므로 커밋 없음. 결과를 실행자가 사용자에게 보고.

---

## Self-Review (writing-plans skill 체크리스트)

**1. Spec coverage:**
- §2.1 슬롯 매핑 → Task 2 `getRLensPayload()` + Task 4 Step 6 send
- §2.2 속도 ×60 → Task 2 `scaleSpeedToVelocity` 골든 벡터 테스트
- §2.3 totalAscent 제외 → Task 2 (구현 안 함, 주석 명시)
- §2.4 전송 2초 → Task 4 Step 6 `tickCount % 2`
- §3.2 공유 접점 추가만 → Task 3·4·6 (러닝 분기 verbatim)
- §3.3 신규 파일 → Task 1(CyclingMetrics)·2(CyclingEngine)·3(ExerciseMode)·7(CyclingScreen)
- §3.4 diff-0 14파일 → Task 8 Step 1 git diff 증명
- §5 테스트 → Task 1·2 JVM, Task 8 회귀/실기기
- §6 versionCode 미변경 → 어떤 Task도 build.gradle.kts 안 건드림 ✓
- 갭 없음.

**2. Placeholder scan:** 모든 코드 블록 완전(실제 Kotlin). "TBD"/"적절히"/"비슷하게" 없음. 모든 gradle 명령 + 기대 출력 명시. ✓

**3. Type consistency:** `CyclingMetrics`(elapsedSeconds/distanceMeters:Float/speedKmh:Float/heartRate/altitudeM) — Task 1 정의, Task 2·7에서 동일 사용. `CyclingEngine.scaleSpeedToVelocity` companion — Task 2 정의·테스트 동일. `ExerciseMode.RUNNING/CYCLING` — Task 3 정의, Task 4·6 동일. `getRLensPayload(): RunningMetrics` 필드(paceSecondsPerKm/cadence/heartRate/distanceMeters/elapsedSeconds) — `RLensConnection.sendMetrics`가 읽는 필드와 일치(RLensConnection.kt:165-169 확인). `HomeScreen(onRunClick,onCycleClick)` — Task 5 정의, Task 6 호출 일치. `CyclingScreen(metrics,isAmbient,isPaused,onPauseClick,onStopClick,onScreenTouch)` — Task 7 정의, Task 6 호출 일치. ✓
