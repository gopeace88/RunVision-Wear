# Adaptive Pace Algorithm Design

**Date**: 2026-01-24
**Status**: Approved
**Author**: Claude + User

## Overview

runvision-wear의 페이스 알고리즘 개선. GPS 유무에 따라 적응형으로 동작하며, 스무딩과 정지 감지를 통해 안정적인 페이스 표시.

## Problem Statement

현재 문제점:
1. 페이스가 급변함 (3:00 → 8:00 → 4:00)
2. Garmin 워치와 일관되게 차이남
3. 정지 시에도 페이스 표시됨
4. 달리는 중 페이스가 0으로 떨어짐

추가 경고:
- ExerciseService:224 - unused parameter 경고

## Solution Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    RunningEngine                         │
├─────────────────────────────────────────────────────────┤
│  ┌──────────────┐    ┌──────────────┐                   │
│  │ GPS 모드     │    │ Non-GPS 모드 │                   │
│  │ Health Svc   │    │ Cadence 기반 │                   │
│  │ 거리 delta   │    │ 보폭 추정    │                   │
│  └──────┬───────┘    └──────┬───────┘                   │
│         └────────┬───────────┘                           │
│                  ▼                                       │
│         ┌────────────────┐                               │
│         │ PaceSmoother   │ ← 이동평균 + 이상치 제거      │
│         └────────┬───────┘                               │
│                  ▼                                       │
│         ┌────────────────┐                               │
│         │ StopDetector   │ ← cadence + delta 기반       │
│         └────────┬───────┘                               │
│                  ▼                                       │
│            최종 페이스 출력                              │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              StrideLengthLearner                         │
├─────────────────────────────────────────────────────────┤
│ • GPS 있을 때: 실제거리 ÷ 걸음수 → 보폭 학습            │
│ • 저장: SharedPreferences                                │
│ • 학습값 없으면: 기본 공식 사용                         │
│   stride = 0.70 + (cadence - 150) × 0.005               │
└─────────────────────────────────────────────────────────┘
```

## New Components

### 1. StrideLengthLearner

GPS 기반 개인 보폭 학습.

```kotlin
class StrideLengthLearner(context: Context) {
    companion object {
        const val MIN_DISTANCE_FOR_LEARNING = 500.0  // meters
        const val MIN_STEPS_FOR_LEARNING = 600L
        const val DEFAULT_STRIDE_BASE = 0.70f        // meters
        const val STRIDE_CADENCE_FACTOR = 0.005f
    }

    fun updateWithGpsData(distanceDelta: Double, stepsDelta: Long)
    fun getStrideLength(cadence: Int): Float
    fun saveToStorage()
    fun loadFromStorage()
}
```

**기본 공식**: `stride = 0.70 + (cadence - 150) × 0.005`

| Cadence | 보폭 | 페이스 |
|---------|------|--------|
| 150 spm | 0.70m | 6:21/km |
| 170 spm | 0.80m | 5:31/km |
| 180 spm | 0.85m | 5:08/km |
| 200 spm | 0.95m | 4:23/km |

### 2. PaceSmoother

5샘플 이동평균 + 이상치 제거.

```kotlin
class PaceSmoother(private val windowSize: Int = 5) {
    fun addPace(paceSeconds: Int): Int
    fun getSmoothedPace(): Int
    fun reset()
}
```

**이상치 기준**: 현재 평균의 ±50% 벗어나면 무시

### 3. StopDetector

cadence + 거리변화 기반 정지 감지.

```kotlin
class StopDetector {
    companion object {
        const val STOP_CADENCE_THRESHOLD = 60      // 60 spm 이하
        const val STOP_TIME_THRESHOLD_MS = 2000L   // 2초간 변화 없음
    }

    fun updateCadence(cadence: Int)
    fun updateDistanceChange()
    fun isStopped(): Boolean
}
```

### 4. AdaptivePaceCalculator

GPS/Non-GPS 모드 자동 전환.

```kotlin
class AdaptivePaceCalculator(
    strideLearner: StrideLengthLearner,
    smoother: PaceSmoother,
    stopDetector: StopDetector
) {
    companion object {
        const val GPS_TIMEOUT_MS = 5000L  // 5초 타임아웃
    }

    fun updateWithGpsDistance(distanceMeters: Double, deltaTime: Double): Int
    fun updateWithCadence(cadence: Int): Int
    fun reset()
}
```

## Modified Components

### RunningEngine

- 새 컴포넌트 통합
- `updateDistance()`: 보폭 학습 + 적응형 페이스
- `updateCadence()`: Non-GPS 모드 페이스 계산

### ExerciseService

- Line 224: `@Suppress("UNUSED_PARAMETER")` 추가

## File Structure

```
app/src/main/kotlin/com/runvision/wear/engine/
├── PaceCalculator.kt          (기존 유지)
├── SpeedCalculator.kt         (기존 유지)
├── DistanceCalculator.kt      (기존 유지)
├── RunningEngine.kt           (수정)
├── StrideLengthLearner.kt     (신규)
├── PaceSmoother.kt            (신규)
├── StopDetector.kt            (신규)
└── AdaptivePaceCalculator.kt  (신규)
```

## Testing Strategy

1. **Unit Tests**: 각 컴포넌트 개별 테스트
2. **Integration Test**: RunningEngine 통합 테스트
3. **실기기 테스트**: Wear OS 기기에서 실제 러닝 테스트

## Success Criteria

- [ ] 페이스 급변 없음 (±30초 이내 변동)
- [ ] 정지 시 0 표시 (2초 이내)
- [ ] GPS 없을 때도 합리적인 페이스 표시
- [ ] 빌드 경고 0개
