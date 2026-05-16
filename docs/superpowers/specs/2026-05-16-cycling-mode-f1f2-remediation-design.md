# 사이클 모드 견고성 보완 설계 (F1/F2 — Codex 적대적 리뷰 대응)

> 작성일: 2026-05-16
> 상태: 설계 완료, 사용자 검토 대기
> 기반 스펙: `docs/superpowers/specs/2026-05-16-cycling-mode-design.md` (커밋 59e2516 + 정정 9825e09)
> 기반 구현: `feat/cycling-mode` (HEAD acebd8b — 사이클 모드 5 커밋)
> 트리거: Codex 적대적 리뷰 (verdict: needs-attention)

---

## 1. 목적과 범위

사이클 모드 구현의 견고성 결함 2건을 보완한다. **기능 추가가 아니라 실패 경로 보강.**

- **F1 [High]**: HS `ExerciseType.BIKING` 시작이 실패/미지원이어도 앱이 사이클 운동 "진행 중"으로 표시하고 유령 DB 세션을 남긴다.
- **F2 [Medium]**: 콜드스타트 직후(서비스 미바인딩) 자전거 탭 시 `setMode(CYCLING)`/스캔 명령이 nullable 바인더 호출이라 드롭 → 모드 유실(러닝으로 전락 또는 데드탭).

**핵심 제약**

1. **러닝 경로 런타임 바이트 동일** — 기존 사이클 스펙의 제1제약 유지. 모든 변경은 *사이클 분기 한정* 또는 *additive(러닝 미참조/기본값/반환 무시)*.
2. `feat/cycling-mode` 브랜치 위 증분 작업 (별도 브랜치 아님).
3. **diff-0 보호 파일 불변**: `engine/RunningEngine.kt`, `engine/*Calculator*`, `ble/RLensProtocol.kt`, `ble/RLensConnection.kt`(특히 `ConnectionState` enum — 값 추가 금지), `ble/RLensScanner.kt`, `ui/screens/RunningScreen.kt`, `ui/components/MetricItem.kt`, `data/RunningMetrics.kt`, `data/db/WorkoutSession.kt`.

**범위 밖**: 트레드밀 러닝 A/B 관찰 항목(앞서 "수정 포인트 아님"으로 종결, 재오픈 안 함). 러닝 시작 플로우의 대칭 게이트(제약 위반이라 비채택).

---

## 2. F2 — Intent 커맨드 경로 (콜드스타트 견고)

### 2.1 메커니즘

`onStartCommand()`는 현재 `intent`를 완전히 무시한다(foreground + START_STICKY). 콜드스타트 시 `bindService()` 완료 전이라 `MainActivity`의 `exerciseService?` 바인더 호출이 드롭된다. → 모드/스캔 명령을 **Intent로 서비스에 직접 전달**한다 (Intent는 미바인딩 구간에도 `onStartCommand`로 확실히 도달).

### 2.2 변경

`ExerciseService` companion 상수:
```kotlin
const val EXTRA_MODE = "extra_mode"          // "RUNNING" | "CYCLING"
const val EXTRA_CMD = "extra_cmd"            // 명령 식별
const val CMD_START_SCAN = "cmd_start_scan"  // 스캔 시작 요청
```

`ExerciseService.onStartCommand(intent, flags, startId)` — **추가만**, 기존 foreground/STICKY 로직 앞/뒤 보존:
```
intent?.getStringExtra(EXTRA_MODE)?.let { setMode(parseExerciseMode(it)) }
if (intent?.getStringExtra(EXTRA_CMD) == CMD_START_SCAN
        && _connectionState.value in [DISCONNECTED, NOT_FOUND]) {
    startScanning()
}
```
- `parseExerciseMode(s)`: `ExerciseMode.values().firstOrNull{ it.name==s } ?: ExerciseMode.RUNNING` — **순수 함수, JVM 테스트 대상**. 알 수 없는 값/널은 RUNNING 안전 기본.
- **extra 없으면**(=러닝 경로 및 기존 모든 호출) `EXTRA_MODE`/`EXTRA_CMD` 모두 null → 두 블록 모두 no-op → `onStartCommand` 동작이 현재와 **바이트 동일**.

`MainActivity.onCycleClick` 비연결(else) 분기만 변경:
```kotlin
val intent = Intent(this@MainActivity, ExerciseService::class.java)
    .putExtra(ExerciseService.EXTRA_MODE, ExerciseMode.CYCLING.name)
    .putExtra(ExerciseService.EXTRA_CMD, ExerciseService.CMD_START_SCAN)
startForegroundService(intent)
// 기존 exerciseService?.startScanning() 직접호출 제거 — 스캔 트리거를 onStartCommand로 단일화(이중 스캔 방지)
```
`exerciseService?.setMode(ExerciseMode.CYCLING)`는 바인딩 시 빠른 인프로세스 경로로 **유지**(idempotent; Intent 경로와 중복돼도 setMode는 멱등).
CONNECTED 빠른 경로(`if (CONNECTED) { startRunning(); nav("cycling") }`)는 구조 무변경(이미 바인딩 상태).

`MainActivity.onRunClick` — **무변경**(Intent에 extra 안 실음 → `onStartCommand` 원본 경로 → 러닝 바이트 동일). 러닝의 `exerciseService?.startScanning()` 직접호출도 그대로 둠.

### 2.3 결과
콜드스타트에서 자전거 탭 → `startForegroundService`가 서비스 생성 + Intent를 `onStartCommand`에 전달 → 모드 CYCLING 확정 + 스캔 시작. 바인더 준비 여부와 무관하게 견고. 데드탭/러닝 전락 제거.

---

## 3. F1 — 사전 게이트 + 런타임 게이트 (유령 세션 방지)

### 3.1 사전 게이트 (미지원 기기 원천 차단)

`ExerciseManager`:
```kotlin
suspend fun isExerciseTypeSupported(type: ExerciseType): Boolean =
    try { type in exerciseClient.getCapabilitiesAsync().await().supportedExerciseTypes }
    catch (e: Exception) { Log.e(...); true }   // 조회 실패 시 낙관적 → 런타임 게이트가 백스톱
```
> 구현 시 `ExerciseCapabilities.supportedExerciseTypes` API 정확성 확인 (이전 `ExerciseType.BIKING`처럼 jar/javap 또는 컴파일로 검증; 미확인 시 BLOCKED 보고, 추측 금지).

`ExerciseService`:
- `private val _cyclingSupported = MutableStateFlow(true)` → `val cyclingSupported: StateFlow<Boolean>` (기본 true: 조회 전 false-비활성 방지).
- onCreate에 추가: `serviceScope.launch { _cyclingSupported.value = exerciseManager.isExerciseTypeSupported(ExerciseType.BIKING) }` (기존 `startMeasuringHeartRate` launch와 병렬, 러닝 경로 미참조).

`HomeScreen`: 인자 `cyclingSupported: Boolean = true` 추가. false면 자전거 `Button(enabled=false)` + 상태 텍스트 "자전거 미지원". 달리기 버튼/동작 무변경.

### 3.2 런타임 게이트 (지원 기기 일시 실패 포착)

`ExerciseManager.startExercise(exerciseType: ExerciseType = ExerciseType.RUNNING): Boolean`
- 성공(`startExerciseAsync().await()` 정상) → `true`
- catch → `Log.e` 후 `false` (기존 로깅 유지, 반환만 추가)
- **러닝 콜러는 반환을 무시** → 러닝 바이트 동일 (기본 파라미터 + 반환 무시; 시그니처에 반환 추가는 호출부 무변경으로 호환).

현재 `startExercise()`는 선형 구조에 엔진/sessionType만 모드 분기돼 있고 `serviceScope.launch { exerciseManager.startExercise(mode.toExerciseType()) }` 한 줄을 공유한다. F1은 **사이클 경로만** 게이트 비동기 블록으로 분리하고, **러닝이 실제로 실행하는 라인 시퀀스는 현재와 동일**하게 둔다:
- **러닝(mode≠CYCLING) = 현재 본문 verbatim**: `runningEngine.reset/start` → `_isRunning=true` → `WorkoutSession(exerciseType=sessionType="RUNNING")` insert → `serviceScope.launch { exerciseManager.startExercise(mode.toExerciseType()) }`(= RUNNING, 반환 무시) → `startTimer()`. 라인 불변.
- **사이클(mode==CYCLING) = HS 성공 게이트** (현재의 비게이트 사이클 라인들을 아래 블록으로 치환):
```kotlin
_cyclingStartFailed.value = false
serviceScope.launch {
    val ok = exerciseManager.startExercise(ExerciseType.BIKING)
    if (ok) {
        cyclingEngine.reset(); cyclingEngine.start()
        _isRunning.value = true
        currentSessionId = UUID.randomUUID().toString()
        workoutDao.insertSession(WorkoutSession(currentSessionId!!, now, exerciseType="CYCLING"))   // IO
        startTimer()
    } else {
        _cyclingStartFailed.value = true
        // _isRunning 은 false 유지, 세션/타이머 미생성 → 유령 세션 불가
    }
}
```
포어그라운드 승격(`startForeground`)·웨이크락은 기존처럼 메서드 진입부에서 수행(러닝과 공유). 사이클은 **HS 성공 전까지 `_isRunning`/세션/타이머 0**.

**사이클 시작 실패 시 서비스 상태 (모호성 제거 — 명시 결정):** 자동 stop/cleanup 하지 **않는다**. 서비스는 foreground·idle 상태로 남고 HomeScreen에 "자전거 시작 실패" 표시. 사용자는 (a) 자전거 재탭으로 재시도, 또는 (b) 뒤로가기/홈 → 기존 `stopRunning()`→`stopExercise()` 경로가 BLE·웨이크락·foreground를 정리. 자동 종료를 넣지 않는 이유: 재시도 UX 보존 + stopExercise는 러닝과 공유 경로라 실패 분기에서 자동 호출 시 부작용 검증 부담 → 사용자 주도 정리가 더 안전·단순.

### 3.3 실패 표면화 (HomeScreen 상태 텍스트 재사용)

`RLensConnection.ConnectionState` enum은 보호 파일이므로 **값 추가 금지**. 대신 `ExerciseService`에 `_cyclingStartFailed: MutableStateFlow<Boolean>` 추가 → `cyclingStartFailed: StateFlow<Boolean>`. 새 운동 시작/홈 복귀 시 false 리셋, 사이클 HS 실패 시 true.

`MainActivity`: `cyclingSupported`, `cyclingStartFailed` collect (기존 metrics collector와 동일 패턴, 추가만) → HomeScreen에 전달.

`HomeScreen` 상태 텍스트 **우선순위 (순수 함수 추출 → JVM 테스트 대상)**:
```
homeStatus(cyclingSupported, cyclingStartFailed, connectionState) -> (text, color):
  !cyclingSupported  -> "자전거 미지원"  (회색)
  cyclingStartFailed -> "자전거 시작 실패" (빨강)
  else               -> 기존 connectionState → (text,color) 매핑 그대로
```
기존 connectionState 매핑 로직은 이 순수 함수 안으로 이동(동일 출력) — HomeScreen은 우리가 Task 5에서 새로 쓴 비보호 파일이므로 리팩터 가능.

---

## 4. 영향 파일 / 러닝 불변 보장

| 파일 | 변경 (전부 additive/사이클 한정) | 러닝 런타임 |
|---|---|---|
| `health/ExerciseManager.kt` | `isExerciseTypeSupported()` 신규; `startExercise()` 반환 `Boolean` | 동일 (기본 파라미터, 반환 무시) |
| `service/ExerciseService.kt` | `onStartCommand` intent 파싱(추가); `startExercise()` 사이클 게이트(러닝 분기 verbatim); `_cyclingSupported`/`_cyclingStartFailed` StateFlow; EXTRA 상수; onCreate capability 조회 launch | 러닝 분기 verbatim, no-extra 경로 동일 |
| `MainActivity.kt` | onCycleClick Intent extra + 직접 startScanning 제거; 상태 2종 collect; HomeScreen 인자 | onRunClick 무변경 |
| `ui/screens/HomeScreen.kt` | `cyclingSupported` 인자, 자전거 버튼 비활성, 상태 우선순위(순수 함수) | 달리기 버튼/동작 동일 |
| 신규 | `parseExerciseMode`·`homeStatus` 순수 함수 + JUnit 테스트 | — |

**불변 (git diff 0):** §1.3 보호 파일 전체. 검증은 기존과 동일 — 보호 파일 `git diff main..HEAD` 빈 출력 + 기존 102 테스트 그대로 통과.

---

## 5. 테스트 전략 (정직성 유지)

**자동 (JVM 유닛, TDD):**
- `parseExerciseMode`: "CYCLING"→CYCLING, "RUNNING"→RUNNING, "xyz"/null→RUNNING(안전 기본).
- `homeStatus` 우선순위: 미지원 우선 > 시작실패 > connectionState 6종 매핑 동치(기존 텍스트/색 보존 회귀).

**자동 (회귀):** 보호 파일 diff-0 증명 + 기존 102 테스트 불변.

**수동 (실기기 — 자동 불가 영역, 정직히 명시):**
- BIKING 미지원(시뮬/해당 기기): 자전거 버튼 비활성 + "자전거 미지원", 세션/타이머 미생성.
- BIKING 지원 + 시작 강제 실패: "자전거 시작 실패", `_isRunning` false 유지, DB에 사이클 세션 없음.
- 콜드스타트(앱 신규 실행 직후 자전거 즉시 탭): 모드 CYCLING으로 스캔/시작 정상, 데드탭/러닝 전락 없음.
- 정상 사이클/러닝 회귀: 기존 동작 동일.

`ExerciseService`/`ExerciseManager`/`MainActivity`는 기존에도 유닛 테스트 없음 — 본 보완도 컴파일 + 위 수동 검증으로만 커버됨을 숨기지 않는다. 신규 *순수 함수*만 진짜 TDD 대상.

---

## 6. 미결/결정됨

- ✅ F1 범위 = C(사전+런타임). F1 실패 표면 = HomeScreen 상태 텍스트(보호 enum 미사용, 별도 StateFlow). F2 = Intent 커맨드 경로.
- 구현 시 확인: `ExerciseCapabilities.supportedExerciseTypes` API 명칭(컴파일/javap 검증, 추측 금지). 미확인 시 BLOCKED 보고.

---
