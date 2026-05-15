# RunVision-Wear 사이클 모드 설계

> 작성일: 2026-05-16
> 상태: 설계 완료, 사용자 검토 대기
> 참조 설계: `runvision-iq/Docs/superpowers/specs/2026-05-15-cycling-mode-design.md`
> 참조 구현: `runvision-iq/source/CyclingStrategy.mc`, `runvision-iq/source/ILensProtocol.mc`

---

## 1. 목적과 범위

Wear OS 앱(`runvision-wear`)에 **사이클 모드**를 추가한다. 화면의 달리기/자전거 버튼으로 모드를 선택하면 자전거에 적합한 메트릭을 rLens HUD에 전송한다.

**핵심 제약**

1. **러닝 모드 사이드이펙트 0.** 러닝 런타임 동작은 바이트 단위로 동일해야 한다.
2. **공유 접점은 "추가만" 허용.** 러닝이 쓰는 파일이라도, 기본값이 러닝이고 러닝 경로가 무변경인 *추가* 변경은 허용(runvision-iq가 택한 방식).
3. **runvision-iq 바이트 미러.** rLens 펌웨어는 슬롯별 라벨이 고정. Wear 자전거 패킷의 바이트가 runvision-iq 자전거 패킷과 동일해야 HUD가 동일하게 보인다.
4. **별도 브랜치** `feat/cycling-mode` → 검증 후 `main` 머지.

**범위 밖 (별도 작업)**

- 타일 서비스(`RunVisionTileService`) 사이클 대응
- `versionCode` 증가 — 폰 앱과 `applicationId` 공유(versionCode 공유). 기능 브랜치에서 건드리지 않고 **릴리즈 시점에 양쪽 최신값 확인 후 별도 결정**.
- runvision-iq의 `totalAscent` 폴백 — Wear 비적용(§2.3 근거)

---

## 2. 메트릭 매핑

### 2.1 슬롯 매핑 (runvision-iq `CyclingStrategy.buildPackets` 미러)

| 슬롯 ID | 러닝 모드 값 | 사이클 모드 값 | 인코딩 |
|---|---|---|---|
| `0x03` EXERCISE_TIME | elapsedSeconds | elapsedSeconds (동일) | u32 LE, 초 |
| `0x06` DISTANCE | distance (m) | distance (m) (동일) | u32 LE, 미터 |
| `0x07` VELOCITY | paceSeconds (sec/km) | **speedKmh × 60 (반올림)** | u32 LE |
| `0x0B` HEART_RATE | hr (bpm) | **hr (bpm) — 항상** | u32 LE |
| `0x0E` CADENCE | cadenceSpm | **altitudeM (현재 GPS 고도, m)** | u32 LE |

### 2.2 속도 ×60 스케일링 (바이트 미러 핵심)

rLens 펌웨어는 `0x07` 슬롯 값을 ÷60하여 표시한다(페이스 sec→min 변환용 슬롯). 따라서 km/h에 ×60을 미리 곱해 보내면 소수점까지 보존된 km/h가 HUD에 표시된다.

runvision-iq `CyclingStrategy.mc:35`:
```
velocityScaled = (values.speedKmh * 60 + 0.5).toNumber()
```

Wear 복제 공식 (Kotlin, 바이트 동일 보장):
```kotlin
val velocityScaled = ((speedKmh * 60.0) + 0.5).toInt().coerceIn(0, Int.MAX_VALUE)
RLensProtocol.createPacket(RLensProtocol.METRIC_VELOCITY, velocityScaled)  // 0x07
```
- `+0.5 후 toInt()` = round-half-up(양수). iq의 `(x+0.5).toNumber()`와 동일. 음수 속도 없음.
- `coerceIn(0, Int.MAX_VALUE)` = iq `encodeUINT32` clamp `[0, 2147483647]`과 동일.
- `speedKmh`는 Health Services `SPEED`(m/s)×3.6 우선, GPS 델타 폴백. 소수점은 ×60 트릭으로 보존.

### 2.3 totalAscent 폴백 제외 근거 (Chesterton's Fence)

runvision-iq가 `0x0B`에 "HR 없으면 totalAscent" 30초 락을 둔 이유: Garmin **Edge 시리즈(자전거 거치형, 광혈류 HR 센서 없음)**를 같은 코드로 커버하기 위함.

Wear에는 그 기기 클래스가 없다. 이 앱은 손목 워치 전용이고 Wear OS 워치는 광혈류 HR 표준 장착 + 운동 세션 중 항상 활성. 따라서 iq의 `_hrEverSeen`은 30초 시점에 **항상 true** → `0x0B`는 **항상 HR**. 폴백 분기는 Wear에서 도달 불가능한 죽은 코드이므로 구현하지 않는다.

**미러 무모순:** iq도 HR이 정상인 케이스에선 `0x0B`에 HR을 보낸다. Wear는 그 케이스만 발생 → 바이트 동일 = HUD 동일. 폴백 제거는 결정 번복이 아니라 죽은 분기 제거.

HR 일시 드롭아웃 처리: 러닝 모드와 **동일하게** 처리(0 또는 직전값 — 러닝 코드 패턴 그대로). 사이클 전용 분기 없음.

### 2.4 전송 주기

| 모드 | 주기 | 근거 |
|---|---|---|
| 러닝 | 5초 (현행 유지) | 변경 없음 |
| 사이클 | **2초** | runvision-iq `CyclingStrategy.getTransmitIntervalSeconds()=2`. 25km/h는 5초에 35m 이동 → 더 잦은 갱신 필요 |

`ExerciseService` 전송 게이트(`tickCount % N == 0`)를 모드별 `N`으로 분기. 러닝 N=5 무변경, 사이클 N=2.

---

## 3. 아키텍처

### 3.1 모드 진입

```
HomeScreen
  ├─ [달리기] 버튼 → nav "running" → ExerciseService(RUNNING)  ← 현행 경로 0 변경
  └─ [자전거] 버튼 → nav "cycling" → ExerciseService(CYCLING)  ← 신규
```

`MainActivity` `SwipeDismissableNavHost`에 `"cycling"` 라우트 추가. `"running"` 라우트·핸들러 무변경.

### 3.2 공유 접점 — "추가만" 변경 (러닝 경로 무변경)

| 파일 | 추가 내용 | 러닝 영향 |
|---|---|---|
| `ui/screens/HomeScreen.kt` | 자전거 버튼 1개. 기존 START(달리기) 그대로 | 없음 |
| `MainActivity.kt` | `"cycling"` 라우트 + 자전거 버튼 핸들러 | 없음 (`"running"` 무변경) |
| `health/ExerciseManager.kt` | (a) `startExercise(type: ExerciseType = ExerciseType.RUNNING)` 파라미터화, 사이클 시 `ExerciseType.BIKING` (b) **신규** `onAltitudeUpdate: ((Double)->Unit)?` 콜백 + 기존 LOCATION 블록에서 `location.value.altitude` 추출(기존 `onLocationUpdate` 라인 무변경) | 기본값=RUNNING, 신규 콜백 미참조 → 동일 |
| `service/ExerciseService.kt` | (a) `setMode()`/모드 보관 (b) 콜백→엔진 디스패치 `if(CYCLING) cyclingEngine else runningEngine` (c) 전송 게이트 `N = if(CYCLING) 2 else 5` (d) `startExercise/pauseExercise/stopExercise` 모드 분기 | 러닝 분기 바이트 동일 |

> **⚠ 모드 타이밍 (버그 표면):** `initializeBle()`가 onCreate에서 캡처한 람다는 BLE CONNECTED 시 `startExercise()`를 호출하고, `startExercise()`는 `mode`를 읽는다. 따라서 `setMode(CYCLING)`는 **스캔 시작 전**에 완료돼야 한다(스테일 스캔의 in-flight connect가 러닝 세션을 트리거하는 것 방지). 두 경로 모두 plan에서 명시적 단계로: ① cold: onCycleClick→`setMode`→`startForegroundService`→`startScanning`, ② already-CONNECTED: onCycleClick→`setMode`→`startExercise`→nav.
| `data/db/WorkoutSession.kt` | 세션 생성 시 `exerciseType="CYCLING"` 기록 | 필드 이미 존재(기본 `"RUNNING"`), 스키마 무변경 |

### 3.3 신규 파일 (병렬 스택)

> **정정 (2026-05-16, 실코드 확인 후):** 최초 §3.3은 `ble/CyclingPacketMapper.kt`가 ByteArray 패킷을 생성한다고 했으나, `RLensConnection`은 `sendMetrics(RunningMetrics)` 단일 경로만 노출하며 내부에서 `createPacePacket(metrics.paceSecondsPerKm)`→`0x07`, `createCadencePacket(metrics.cadence)`→`0x0E` 식으로 슬롯을 채운다. 따라서 **자전거 값을 `RunningMetrics` 필드에 리매핑**해 **무수정 `sendMetrics`로 흘려보내면** 바이트가 iq와 정확히 일치한다. → `ble/CyclingPacketMapper.kt` 불필요, `RLensConnection.kt`·`RunningMetrics.kt` **진짜 diff 0**.

- **`data/CyclingMetrics.kt`** — 워치 화면용 **정직한** 데이터 클래스: `elapsedSeconds`, `distanceMeters: Float`, `speedKmh: Float`, `heartRate: Int`, `altitudeM: Int` + 포매터(`elapsedFormatted`, `speedFormatted`(소수 1자리), `distanceKmFormatted`, `altitudeFormatted`). 순수 JVM 테스트 가능.
- **`engine/CyclingEngine.kt`** — Health Services `BIKING` 콜백 소비(HR, GPS lat/lon/ts, HS distance m, altitude m). 산출 두 가지:
  - `getCurrentMetrics(): CyclingMetrics` — 화면용 정직한 값. `speedKmh` = 재사용 `SpeedCalculator`(m/s)×3.6, distance = HS distance 우선·GPS(`DistanceCalculator`) 폴백, altitude/hr = 최신값, elapsed = `tick()`.
  - `getRLensPayload(): RunningMetrics` — BLE/DB용 **리매핑** DTO: `paceSecondsPerKm = ((speedKmh*60.0)+0.5).toInt().coerceIn(0, Int.MAX_VALUE)` (§2.2), `cadence = altitudeM`, `heartRate = hr`, `distanceMeters = distanceM`, `elapsedSeconds = elapsed`.
  - 메서드: `start/pause/resume/stop/tick/reset/isActive/updateHeartRate/updateGps/updateDistance/updateAltitude`. 페이스 스무더·스트라이드 러너·정지 감지·cadence·stepsDelta **없음**. 30초 락·totalAscent **없음**(§2.3).
- **`service/ExerciseMode.kt`** — `enum class ExerciseMode { RUNNING, CYCLING }`.
- **`ui/screens/CyclingScreen.kt`** — 2×2 + Ambient 변형(Wear 품질 가이드 준수, `RunningScreen` 레이아웃 미러), 인자 `CyclingMetrics`. **워치 화면은 정직한 라벨**(슬롯 리매핑은 BLE 계층에만): Speed(km/h) / Distance(km) / HR(bpm) / Altitude(m) + Elapsed. 일시정지/정지/탭-밝기 UX 동일. 아이콘은 기존 drawable 재사용(신규 리소스 없음).

### 3.4 미변경 파일 (러닝 핵심 = diff 0줄)

`engine/RunningEngine.kt`, `engine/DistanceCalculator.kt`, `engine/SpeedCalculator.kt`, `engine/PaceCalculator.kt`, `engine/PaceSmoother.kt`, `engine/AdaptivePaceCalculator.kt`, `engine/StrideLengthLearner.kt`, `engine/StopDetector.kt`, `ble/RLensProtocol.kt`, `ble/RLensConnection.kt`, `ble/RLensScanner.kt`, `ui/screens/RunningScreen.kt`, `ui/components/MetricItem.kt`, `data/RunningMetrics.kt`, `data/db/WorkoutSession.kt`.

- `RLensConnection.kt`·`RunningMetrics.kt` diff 0 보장 메커니즘: 사이클은 `CyclingEngine.getRLensPayload()`가 만든 리매핑 `RunningMetrics`를 **기존 `sendMetrics`** 에 그대로 넘긴다. `SpeedCalculator`·`DistanceCalculator`·`MetricItem`은 read-only **재사용**(인스턴스화만, 수정 0).
- **DB 의미 주의:** 리매핑 DTO를 그대로 `WorkoutSample`에 쓰면 `getAvgPace`가 avg(speed×60) 쓰레기값을 계산한다. 사이클 분기 DB 샘플은 `paceSecondsPerKm=0, cadence=0, heartRate=hr, distanceMeters=distanceM`로 기록(평균 pace/cadence 자연히 0). 세션 `exerciseType="CYCLING"`.

---

## 4. BLE 바이트 미러 — 골든 벡터

`RLensProtocol.createPacket`(Wear)과 `ILensProtocol.encodeUINT32`(iq)는 LE u32 + clamp `[0, 2147483647]`로 **이미 동일**. 아래 벡터로 패리티 테스트:

| 입력 | 슬롯 | velocityScaled/값 | 패킷 바이트 (hex) |
|---|---|---|---|
| speed 25.55 km/h | 0x07 | round(25.55×60)=1533=0x05FD | `07 FD 05 00 00` |
| speed 30.0 km/h | 0x07 | 1800=0x0708 | `07 08 07 00 00` |
| altitude 1200 m | 0x0E | 1200=0x04B0 | `0E B0 04 00 00` |
| HR 150 bpm | 0x0B | 150=0x96 | `0B 96 00 00 00` |
| distance 5000 m | 0x06 | 5000=0x1388 | `06 88 13 00 00` |
| elapsed 600 s | 0x03 | 600=0x0258 | `03 58 02 00 00` |

---

## 5. 테스트 전략

### 5.1 러닝 회귀 방지 (최우선)

1. **git diff 증거:** §3.4 미변경 파일 목록이 PR diff에서 0줄 변경임을 확인.
2. **러닝 BLE 바이트 동등:** 러닝 경로로 같은 메트릭 입력 시 변경 전/후 패킷 바이트 동일(기존 테스트 + 회귀 케이스).
3. **전송 게이트:** 러닝 모드 N=5 유지 확인.

### 5.2 CyclingEngine 유닛 (JUnit)

- `speedKmh` = HS SPEED(m/s)×3.6, GPS 폴백 경로
- ×60 스케일링 반올림: 25.55 → 1533, 30.0 → 1800 (§4)
- distance/altitude/HR/elapsed 산출 정확성
- HR 일시 null → 러닝과 동일 처리(0/직전값), 모드 불변

### 5.3 패킷 패리티 (골든 벡터)

`CyclingPacketMapper` 출력이 §4 표 바이트와 정확히 일치. (runvision-iq `CyclingStrategy` 인코딩 미러 보증)

### 5.4 실기기 검증

- 러닝 액티비티: 변경 전 빌드와 BLE 패킷 바이트 비교 (사이드이펙트 0 확인)
- 사이클 액티비티: rLens HUD에 속도(소수점 포함)/거리/HR/고도 정상 표시 + 2초 주기 갱신 확인
- 모드 전환: 자전거 종료 → 달리기 시작 시 러닝 동작 정상

---

## 6. 마이그레이션 및 호환성

- **기존 사용자:** 달리기만 쓰면 어떤 변화도 없음. `exerciseType` 미선택/달리기 = 기존과 동일.
- **rLens 펌웨어:** 변경 불필요. 메트릭 ID·인코딩 그대로, 값 의미만 워치 앱이 재해석.
- **versionCode:** 폰 앱과 공유(`com.runvision.runvision`). 기능 브랜치에서 미변경. 릴리즈 시 양쪽 최신 versionCode 확인 후 더 높은 값 +1 (별도 절차).

---

## 7. 미결 사항 (실코드 확인 후 해소)

- ✅ **elapsed/일시정지:** 러닝은 `sendMetrics`의 `createExerciseTimePacket(elapsed)`→`0x03` 사용(`RLensConnection.kt:165`), Current Time characteristic 미사용. 일시정지는 서비스 레벨(`exerciseManager.pause/resume` + `engine.pause/resume`). 사이클은 동일 패턴을 모드 분기로 재사용.
- ✅ **속도 소스:** HS `SPEED` 의존 없음. 재사용 `SpeedCalculator`(GPS lat/lon/ts → m/s) ×3.6. `ExerciseManager`는 타입 파라미터화 + altitude 콜백만 추가.
- **테스트 커버리지 정직성 (plan에 명시):** 자동 회귀 증거 = 순수 JVM 테스트(`CyclingMetrics`/`CyclingEngine`/`RLensProtocol`/기존 calculator) + git diff 0. `ExerciseService`/`ExerciseManager`/`MainActivity`/스크린은 **기존 유닛 테스트 없음** → §5.4 실기기 검증으로만 회귀 확인.
- **베이스라인 (2026-05-16):** `./gradlew :app:testDebugUnitTest` = 90 tests, 0 fail. 모든 신규 작업은 이 그린 베이스라인 위에서 진행.

---
