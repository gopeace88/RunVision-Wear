# 사이클 모드 F1/F2 견고성 보완 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Codex 적대적 리뷰 결함 F1(HS BIKING 시작 실패 시 유령 세션)·F2(콜드스타트 모드 유실)를 러닝 경로 무영향으로 보완한다.

**Architecture:** 버그 취약한 신규 로직 2개(`parseExerciseMode`, `cyclingHomeOverride`)를 Android·Compose 비의존 순수 함수로 분리해 진짜 TDD. 나머지는 추가만/사이클 분기 한정 배선(러닝 분기 verbatim). F1=사전 capability 게이트(자전거 버튼 비활성)+런타임 HS 성공 게이트(성공 후에만 세션/타이머)+별도 StateFlow→HomeScreen 텍스트. F2=`onStartCommand` Intent extra 커맨드 경로(extra 없으면 현재와 바이트 동일).

**Tech Stack:** Kotlin, Android Wear OS, Health Services, Jetpack Compose (Wear), JUnit4+MockK, `./gradlew :app:testDebugUnitTest`.

**참조 스펙:** `docs/superpowers/specs/2026-05-16-cycling-mode-f1f2-remediation-design.md` (커밋 928280f)

**베이스라인:** `feat/cycling-mode` HEAD `928280f`(직전 코드 HEAD `acebd8b`), `./gradlew :app:testDebugUnitTest` = **102 tests, 0 fail**. 회귀 = 보호파일 git diff 0 + 102 그대로.

**diff-0 보호 파일 (절대 수정 금지):** `engine/RunningEngine.kt`, `engine/DistanceCalculator.kt`, `engine/SpeedCalculator.kt`, `engine/PaceCalculator.kt`, `engine/PaceSmoother.kt`, `engine/AdaptivePaceCalculator.kt`, `engine/StrideLengthLearner.kt`, `engine/StopDetector.kt`, `ble/RLensProtocol.kt`, `ble/RLensConnection.kt`(특히 `ConnectionState` enum), `ble/RLensScanner.kt`, `ui/screens/RunningScreen.kt`, `ui/components/MetricItem.kt`, `data/RunningMetrics.kt`, `data/db/WorkoutSession.kt`, `engine/CyclingEngine.kt`, `data/CyclingMetrics.kt`, `ui/screens/CyclingScreen.kt`.

**테스트 커버리지 정직성:** 진짜 자동 TDD = Task 1·2 순수 함수만. Task 3·4·5는 Health Services/Compose/Activity 의존이라 기존에도 유닛 테스트 없음 → 컴파일 + 102 회귀 + Task 6 수동 체크리스트로만 검증. 플랜은 이 한계를 숨기지 않는다.

---

### Task 1: parseExerciseMode 순수 함수 (TDD)

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/service/ExerciseMode.kt`
- Test: `app/src/test/kotlin/com/runvision/wear/service/ExerciseModeTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

Create `app/src/test/kotlin/com/runvision/wear/service/ExerciseModeTest.kt`:

```kotlin
package com.runvision.wear.service

import org.junit.Assert.*
import org.junit.Test

class ExerciseModeTest {

    @Test
    fun `parses exact enum names`() {
        assertEquals(ExerciseMode.CYCLING, parseExerciseMode("CYCLING"))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("RUNNING"))
    }

    @Test
    fun `null defaults to RUNNING (safe)`() {
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode(null))
    }

    @Test
    fun `unknown or malformed defaults to RUNNING (safe)`() {
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("xyz"))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode(""))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("cycling")) // case-sensitive by design
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.service.ExerciseModeTest"`
Expected: **BUILD FAILED**, `Unresolved reference: parseExerciseMode`.

- [ ] **Step 3: 구현 — ExerciseMode.kt 끝에 함수 추가**

The current file content is exactly:

```kotlin
package com.runvision.wear.service

import androidx.health.services.client.data.ExerciseType

/** Exercise mode chosen by the user on HomeScreen. */
enum class ExerciseMode { RUNNING, CYCLING }

/**
 * Bridge to the Health Services SDK type. Co-located with the enum so the
 * RUNNING↔RUNNING / CYCLING↔BIKING mapping lives in exactly one place.
 */
fun ExerciseMode.toExerciseType(): ExerciseType =
    when (this) {
        ExerciseMode.RUNNING -> ExerciseType.RUNNING
        ExerciseMode.CYCLING -> ExerciseType.BIKING
    }
```

Append (at end of file, after the `toExerciseType` function — do not modify existing lines):

```kotlin

/**
 * Safe parse for the Intent EXTRA_MODE string. Unknown/null → RUNNING so a
 * malformed or absent command can never accidentally select cycling.
 * Case-sensitive: only exact enum names ("RUNNING"/"CYCLING") match.
 */
fun parseExerciseMode(name: String?): ExerciseMode =
    ExerciseMode.values().firstOrNull { it.name == name } ?: ExerciseMode.RUNNING
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.service.ExerciseModeTest"`
Expected: **BUILD SUCCESSFUL**, all tests pass (3 test methods).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseMode.kt app/src/test/kotlin/com/runvision/wear/service/ExerciseModeTest.kt
git commit -m "feat: parseExerciseMode 안전 파서 (F2 Intent 커맨드용, 순수)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: cyclingHomeOverride 순수 함수 + HomeOverride (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeStatus.kt`
- Test: `app/src/test/kotlin/com/runvision/wear/ui/screens/HomeStatusTest.kt`

> 설계 의도: HomeScreen 상태 텍스트의 *우선순위 결정*(미지원 > 시작실패 > 연결상태)만 분리. Compose `Color`/`RLensConnection` 의존 없이 순수 enum 반환 → JVM TDD. 기존 connectionState→(텍스트,색) 6분기 매핑은 HomeScreen 안에 그대로 둔다(override가 null일 때만 사용).

- [ ] **Step 1: 실패 테스트 작성**

Create `app/src/test/kotlin/com/runvision/wear/ui/screens/HomeStatusTest.kt`:

```kotlin
package com.runvision.wear.ui.screens

import org.junit.Assert.*
import org.junit.Test

class HomeStatusTest {

    @Test
    fun `unsupported takes top priority`() {
        // unsupported wins even if startFailed also true
        assertEquals(HomeOverride.UNSUPPORTED, cyclingHomeOverride(cyclingSupported = false, cyclingStartFailed = true))
        assertEquals(HomeOverride.UNSUPPORTED, cyclingHomeOverride(cyclingSupported = false, cyclingStartFailed = false))
    }

    @Test
    fun `start failed when supported`() {
        assertEquals(HomeOverride.START_FAILED, cyclingHomeOverride(cyclingSupported = true, cyclingStartFailed = true))
    }

    @Test
    fun `no override in the normal case`() {
        assertNull(cyclingHomeOverride(cyclingSupported = true, cyclingStartFailed = false))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.ui.screens.HomeStatusTest"`
Expected: **BUILD FAILED**, `Unresolved reference: HomeOverride` / `cyclingHomeOverride`.

- [ ] **Step 3: 구현**

Create `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeStatus.kt`:

```kotlin
package com.runvision.wear.ui.screens

/**
 * Which override message (if any) the HomeScreen status line should show for
 * cycling. Pure, Android/Compose-free, independently testable.
 *
 * Priority: capability (unsupported device) > runtime start failure >
 * none (fall back to the existing connectionState mapping).
 */
enum class HomeOverride { UNSUPPORTED, START_FAILED }

fun cyclingHomeOverride(cyclingSupported: Boolean, cyclingStartFailed: Boolean): HomeOverride? =
    when {
        !cyclingSupported -> HomeOverride.UNSUPPORTED
        cyclingStartFailed -> HomeOverride.START_FAILED
        else -> null
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.runvision.wear.ui.screens.HomeStatusTest"`
Expected: **BUILD SUCCESSFUL**, 3 test methods pass.

- [ ] **Step 5: 전체 회귀**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 102 baseline + Task1·2 신규 테스트, 0 fail. (실제 합계는 보고.)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/ui/screens/HomeStatus.kt app/src/test/kotlin/com/runvision/wear/ui/screens/HomeStatusTest.kt
git commit -m "feat: cyclingHomeOverride 상태 우선순위 순수 함수 (F1 표면화용)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: ExerciseManager — capability 조회 + startExercise Boolean 반환 (additive)

> 검증 = 컴파일 + 102 회귀(신규 테스트 없음). Health Services는 유닛 테스트 불가. 러닝 콜러는 반환 무시 → 바이트 동일.

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/health/ExerciseManager.kt`

- [ ] **Step 1: isExerciseTypeSupported 추가**

Find this exact block (the start of `startExercise`):

```kotlin
    /**
     * Start exercise session.
     * @param exerciseType RUNNING (default — running path byte-identical) or BIKING.
     */
    suspend fun startExercise(exerciseType: ExerciseType = ExerciseType.RUNNING) {
```

Replace with (adds the capability query method before, and changes the return type to `Boolean`):

```kotlin
    /**
     * Whether Health Services on this device supports the given exercise type.
     * Optimistic on query failure (returns true) so the runtime start gate
     * remains the backstop rather than false-disabling on a transient error.
     */
    suspend fun isExerciseTypeSupported(exerciseType: ExerciseType): Boolean {
        return try {
            exerciseType in exerciseClient.getCapabilitiesAsync().await().supportedExerciseTypes
        } catch (e: Exception) {
            Log.e(TAG, "Capability query failed for $exerciseType: ${e.message}", e)
            true
        }
    }

    /**
     * Start exercise session.
     * @param exerciseType RUNNING (default — running path byte-identical) or BIKING.
     * @return true if Health Services accepted the session start, false on failure.
     *         Running callers ignore the return value (behaviour unchanged).
     */
    suspend fun startExercise(exerciseType: ExerciseType = ExerciseType.RUNNING): Boolean {
```

> Verify `androidx.health.services.client.data.ExerciseCapabilities.supportedExerciseTypes` resolves (the `getCapabilitiesAsync().await()` result type). It is already used in this file via `getExerciseTypeCapabilities(...)`. If `supportedExerciseTypes` does not compile, STOP and report BLOCKED with the compiler error and the available members (do NOT guess an alternative API).

- [ ] **Step 2: 성공 경로 return true**

Find this exact block:

```kotlin
            exerciseClient.startExerciseAsync(config).await()
            Log.d(TAG, "Exercise started successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exercise: ${e.message}", e)
        }
    }
```

Replace with:

```kotlin
            exerciseClient.startExerciseAsync(config).await()
            Log.d(TAG, "Exercise started successfully!")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exercise: ${e.message}", e)
            return false
        }
    }
```

> No import change (`ExerciseType` already imported via `androidx.health.services.client.data.*`). The only caller today is `ExerciseService` `serviceScope.launch { exerciseManager.startExercise(mode.toExerciseType()) }` which ignores the return — running behaviour byte-identical. Task 4 makes the cycling branch consume the Boolean.

- [ ] **Step 3: 컴파일 + 회귀**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 102 + Task1·2 신규, 0 fail (no new tests in Task 3; proves additive change compiles & nothing broke).

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/health/ExerciseManager.kt
git commit -m "feat: ExerciseManager isExerciseTypeSupported + startExercise Boolean 반환 (additive)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: ExerciseService — Intent 커맨드 + 사이클 HS 성공 게이트 (additive, 러닝 verbatim)

> 검증 = 컴파일 + 102 회귀. 러닝 분기는 현재 코드와 한 글자도 다르지 않게 유지.

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt`

- [ ] **Step 1: companion 에 EXTRA 상수 추가**

Find:

```kotlin
        // MAC binding: prevents connecting to a nearby stranger's rLens
        const val PREFS_NAME = "runvision_prefs"
        const val KEY_RLENS_ADDRESS = "rlens_mac_address"
    }
```

Replace with:

```kotlin
        // MAC binding: prevents connecting to a nearby stranger's rLens
        const val PREFS_NAME = "runvision_prefs"
        const val KEY_RLENS_ADDRESS = "rlens_mac_address"

        // F2: durable mode/start command via startForegroundService Intent
        const val EXTRA_MODE = "extra_mode"          // ExerciseMode.name
        const val EXTRA_CMD = "extra_cmd"
        const val CMD_START_SCAN = "cmd_start_scan"
    }
```

- [ ] **Step 2: cyclingSupported / cyclingStartFailed StateFlow 추가**

Find:

```kotlin
    private val _cyclingMetrics = MutableStateFlow(CyclingMetrics())
    val cyclingMetrics: StateFlow<CyclingMetrics> = _cyclingMetrics
```

Replace with:

```kotlin
    private val _cyclingMetrics = MutableStateFlow(CyclingMetrics())
    val cyclingMetrics: StateFlow<CyclingMetrics> = _cyclingMetrics

    // F1: capability (false = device has no BIKING support) + runtime start failure
    private val _cyclingSupported = MutableStateFlow(true)
    val cyclingSupported: StateFlow<Boolean> = _cyclingSupported
    private val _cyclingStartFailed = MutableStateFlow(false)
    val cyclingStartFailed: StateFlow<Boolean> = _cyclingStartFailed
```

- [ ] **Step 3: onCreate 에서 BIKING 지원 1회 조회**

Find this exact block (end of `onCreate`):

```kotlin
        // Start immediate heart rate measurement (before exercise starts)
        serviceScope.launch {
            exerciseManager.startMeasuringHeartRate()
        }
    }
```

Replace with:

```kotlin
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
```

> `ExerciseMode` and `toExerciseType()` are same-package (`com.runvision.wear.service`) — no import. If the `serviceScope.launch { exerciseManager.startMeasuringHeartRate() }` block is not found exactly, STOP and report NEEDS_CONTEXT with the actual end of `onCreate`.

- [ ] **Step 4: onStartCommand — Intent 커맨드 파싱 (추가만)**

Find this exact block:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // Acquire wake lock immediately when service starts foreground
        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_STICKY
    }
```

Replace with:

```kotlin
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
```

> `parseExerciseMode` is same-package (Task 1) — no import. `RLensConnection` is already imported in this file.

- [ ] **Step 5: startExercise — 사이클 분기 HS 성공 게이트 (러닝 분기 verbatim)**

Find this exact block (post-foreground body of `startExercise()`):

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
            exerciseManager.startExercise(mode.toExerciseType())
        }

        // Start 1Hz timer
        startTimer()
    }
```

Replace with (RUNNING branch is the original lines verbatim; CYCLING branch is HS-gated):

```kotlin
        if (mode == ExerciseMode.CYCLING) {
            // F1: gate session/UI/timer on a successful Health Services BIKING start.
            _cyclingStartFailed.value = false
            serviceScope.launch {
                val ok = exerciseManager.startExercise(mode.toExerciseType())
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
```

> RUNNING-branch equivalence: when `mode != CYCLING`, the executed lines are identical to the original (engine reset/start → `_isRunning=true` → `WorkoutSession(exerciseType=sessionType)` where `sessionType` is `"RUNNING"` exactly as the original `if(CYCLING)"CYCLING" else "RUNNING"` resolved for running → `serviceScope.launch { exerciseManager.startExercise(mode.toExerciseType()) }` (return ignored) → `startTimer()`). No running-path runtime change.

- [ ] **Step 6: 컴파일 + 회귀**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 102 + Task1·2 신규, 0 fail (no new tests; additive wiring compiles, diff-0 engine/protocol tests pass).

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/kotlin/com/runvision/wear/service/ExerciseService.kt
git commit -m "feat: ExerciseService Intent 커맨드 경로 + 사이클 HS 성공 게이트 (러닝 verbatim)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: MainActivity + HomeScreen — Intent extra·상태 collect·버튼 비활성 (1 커밋)

> HomeScreen 시그니처가 바뀌어 MainActivity 호출부와 동시 변경해야 컴파일 → 한 커밋. 검증 = 컴파일 + 102 회귀.

**Files:**
- Modify: `app/src/main/kotlin/com/runvision/wear/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt`

- [ ] **Step 1: MainActivity — cyclingSupported/cyclingStartFailed 상태 필드**

Find:

```kotlin
    private val metrics = mutableStateOf(RunningMetrics())
    private val cyclingMetrics = mutableStateOf(CyclingMetrics())
```

Replace with:

```kotlin
    private val metrics = mutableStateOf(RunningMetrics())
    private val cyclingMetrics = mutableStateOf(CyclingMetrics())
    private val cyclingSupported = mutableStateOf(true)
    private val cyclingStartFailed = mutableStateOf(false)
```

- [ ] **Step 2: MainActivity — 두 StateFlow collect (추가만)**

Find:

```kotlin
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.cyclingMetrics?.collect { m ->
                        cyclingMetrics.value = m
                    }
                }
            }
```

Replace with:

```kotlin
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.cyclingMetrics?.collect { m ->
                        cyclingMetrics.value = m
                    }
                }
            }
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.cyclingSupported?.collect { s ->
                        cyclingSupported.value = s
                    }
                }
            }
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    exerciseService?.cyclingStartFailed?.collect { f ->
                        cyclingStartFailed.value = f
                    }
                }
            }
```

- [ ] **Step 3: MainActivity — onStart 동기화 (추가만)**

Find:

```kotlin
            // Immediately sync metrics to avoid showing stale data
            metrics.value = service.metrics.value
            cyclingMetrics.value = service.cyclingMetrics.value
            Log.d(TAG, "onStart: synced metrics, time=${metrics.value.elapsedSeconds}s")
```

Replace with:

```kotlin
            // Immediately sync metrics to avoid showing stale data
            metrics.value = service.metrics.value
            cyclingMetrics.value = service.cyclingMetrics.value
            cyclingSupported.value = service.cyclingSupported.value
            cyclingStartFailed.value = service.cyclingStartFailed.value
            Log.d(TAG, "onStart: synced metrics, time=${metrics.value.elapsedSeconds}s")
```

- [ ] **Step 4: MainActivity — onCycleClick 비연결 분기 Intent 커맨드화**

Find this exact block:

```kotlin
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
```

Replace with (only the cycling `else` branch changes — drop direct `startScanning()`, add Intent extras; CONNECTED branch unchanged):

```kotlin
                                if (connectionState.value == RLensConnection.ConnectionState.CONNECTED) {
                                    startRunning()
                                    nav.navigate("cycling")
                                } else {
                                    // F2: deliver mode + scan command durably via Intent
                                    // (binder may be null pre-bind). onStartCommand owns
                                    // the single scan trigger to avoid double-scan.
                                    val intent = Intent(this@MainActivity, ExerciseService::class.java)
                                        .putExtra(ExerciseService.EXTRA_MODE, ExerciseMode.CYCLING.name)
                                        .putExtra(ExerciseService.EXTRA_CMD, ExerciseService.CMD_START_SCAN)
                                    startForegroundService(intent)
                                }
                            }
                        )
                    }
                    composable("running") {
```

> `exerciseService?.setMode(ExerciseMode.CYCLING)` on the line just above the `if` is **kept unchanged** (fast in-process path when already bound; idempotent with the Intent path). `onRunClick` is **not touched** (no extras → `onStartCommand` byte-identical; keeps its `exerciseService?.startScanning()`).

- [ ] **Step 5: MainActivity — HomeScreen 호출에 인자 2개 전달**

Find:

```kotlin
                    composable("home") {
                        HomeScreen(
                            connectionState = connectionState.value,
                            onRunClick = {
```

Replace with:

```kotlin
                    composable("home") {
                        HomeScreen(
                            connectionState = connectionState.value,
                            cyclingSupported = cyclingSupported.value,
                            cyclingStartFailed = cyclingStartFailed.value,
                            onRunClick = {
```

- [ ] **Step 6: HomeScreen — 인자 추가, 자전거 버튼 비활성, 상태 우선순위**

Replace the ENTIRE contents of `app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt` with:

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
    cyclingSupported: Boolean = true,
    cyclingStartFailed: Boolean = false,
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
                    enabled = cyclingSupported,
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
                val (statusText, statusColor) = when (cyclingHomeOverride(cyclingSupported, cyclingStartFailed)) {
                    HomeOverride.UNSUPPORTED -> "자전거 미지원" to Color.Gray
                    HomeOverride.START_FAILED -> "자전거 시작 실패" to Color(0xFFF44336)
                    null -> when (connectionState) {
                        RLensConnection.ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                        RLensConnection.ConnectionState.CONNECTING -> "Connecting.." to Color(0xFFFF9800)
                        RLensConnection.ConnectionState.RECONNECTING -> "Reconnecting.." to Color(0xFFFF9800)
                        RLensConnection.ConnectionState.SCANNING -> "Scanning.." to Color(0xFF2196F3)
                        RLensConnection.ConnectionState.NOT_FOUND -> "Not Found" to Color(0xFFF44336)
                        RLensConnection.ConnectionState.DISCONNECTED -> "READY" to Color.Gray
                    }
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

> `cyclingHomeOverride` / `HomeOverride` are same-package (`ui.screens`, Task 2) — no import. The 6-way `connectionState` mapping is the original logic, now nested under the `null` (no-override) case — connection text/colors unchanged. `달리기` button untouched.

- [ ] **Step 7: 컴파일 + 회귀**

Run: `./gradlew :app:compileDebugKotlin` → **BUILD SUCCESSFUL**.
Run: `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, 102 + Task1·2 신규, 0 fail.

- [ ] **Step 8: 커밋 (MainActivity + HomeScreen 1 커밋)**

```bash
git add app/src/main/kotlin/com/runvision/wear/MainActivity.kt app/src/main/kotlin/com/runvision/wear/ui/screens/HomeScreen.kt
git commit -m "feat: 콜드스타트 Intent 커맨드 + 자전거 미지원/실패 표면화 (HomeScreen)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 최종 회귀 증명 + 빌드 검증

**Files:** 검증만 (코드 변경 없음)

- [ ] **Step 1: 러닝 사이드이펙트 0 — 보호 18파일 diff 0 증명**

Run:

```bash
git -C /home/jhkim/00.Projects/00.RunVision/runvision-wear diff --stat main..HEAD -- \
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
  app/src/main/kotlin/com/runvision/wear/data/db/WorkoutSession.kt \
  app/src/main/kotlin/com/runvision/wear/engine/CyclingEngine.kt \
  app/src/main/kotlin/com/runvision/wear/data/CyclingMetrics.kt \
  app/src/main/kotlin/com/runvision/wear/ui/screens/CyclingScreen.kt
```

Expected: **empty output**. Any line printed = CRITICAL FAILURE — STOP, report.

- [ ] **Step 2: 전체 테스트 그린**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **BUILD SUCCESSFUL**, 102 baseline + Task1·2 신규 (실제 합계 보고), 0 fail.

- [ ] **Step 3: APK 빌드**

Run: `./gradlew :app:assembleDebug`
Expected: **BUILD SUCCESSFUL** (Compose/Activity/Service 전체 링크 — 유닛 미커버 영역 컴파일 보증). 타임아웃 ~420000ms.

- [ ] **Step 4: 실기기 수동 체크리스트 (자동 미커버 — 보고만)**

- [ ] BIKING 미지원 기기/시뮬: 자전거 버튼 비활성 + "자전거 미지원", 세션/타이머 미생성
- [ ] BIKING 지원 + 시작 강제 실패: "자전거 시작 실패", `_isRunning` false, DB에 사이클 세션 없음
- [ ] 콜드스타트(앱 신규 실행 직후 자전거 즉시 탭): 모드 CYCLING으로 스캔/시작, 데드탭/러닝 전락 없음
- [ ] 정상 사이클(지원 기기): 기존대로 동작 + 2초 전송
- [ ] 러닝 회귀: 달리기 시작/일시정지/정지 기존과 동일

---

## Self-Review (writing-plans 체크리스트)

**1. Spec coverage:**
- 스펙 §2 F2 Intent 경로 → Task 4 Step1·4(상수·onStartCommand) + Task 5 Step4(onCycleClick) + Task 1(parseExerciseMode). ✓
- 스펙 §3.1 사전 게이트 → Task 3(isExerciseTypeSupported) + Task 4 Step3(onCreate 조회) + Task 5 Step6(버튼 enabled). ✓
- 스펙 §3.2 런타임 게이트 → Task 3(Boolean) + Task 4 Step5(사이클 분기 게이트, 러닝 verbatim). ✓
- 스펙 §3.3 실패 표면 → Task 2(cyclingHomeOverride) + Task 4 Step2(StateFlow) + Task 5 Step2·6. ✓
- 스펙 §4 영향 파일/러닝 불변 → Task 6 Step1 diff-0 증명. ✓
- 스펙 §5 테스트 정직성 → 헤더 + Task6 명시. ✓
- 갭 없음.

**2. Placeholder scan:** 모든 코드 블록 실제 Kotlin·실제 명령·기대 출력. "TBD/적절히" 없음. `supportedExerciseTypes` API는 "컴파일 검증, 미확인 시 BLOCKED" 명시(원본 스펙과 동일 패턴, placeholder 아님). ✓

**3. Type consistency:** `parseExerciseMode(String?):ExerciseMode`(Task1 정의, Task4 Step4 사용 일치). `cyclingHomeOverride(Boolean,Boolean):HomeOverride?`+`HomeOverride{UNSUPPORTED,START_FAILED}`(Task2 정의, Task5 Step6 사용 일치). `EXTRA_MODE/EXTRA_CMD/CMD_START_SCAN`(Task4 Step1 정의, Task5 Step4 사용 일치). `isExerciseTypeSupported(ExerciseType):Boolean`·`startExercise(...):Boolean`(Task3 정의, Task4 Step3·5 사용 일치). `cyclingSupported`/`cyclingStartFailed` StateFlow(Task4 Step2 정의, Task5 Step2·3 collect 일치). HomeScreen 신규 시그니처(Task5 Step6 정의, Step5 호출 일치). ✓
