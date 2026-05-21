package com.runvision.wear.engine

import java.util.ArrayDeque

/**
 * GPS Speed Calculator with moving average smoothing
 *
 * Uses 5-sample moving average to reduce GPS noise
 */
class SpeedCalculator(private val bufferSize: Int = 5) {

    companion object {
        // GPS 좌표가 이 distance 이내로만 변하면 stationary(정지)로 판정 → instantSpeed=0.
        // 도심 GPS multipath noise floor ~1-3m. 2m가 너무 작은 보행 false-positive와 노이즈 간 절충.
        private const val STATIONARY_DISTANCE_M = 2.0
        // Smoothed speed가 이 미만이면 0으로 표시 (cycling 모드 — 1km/h 미만은 정지).
        private const val SPEED_DEAD_ZONE_KMH = 1.0f
        private const val SPEED_DEAD_ZONE_MS = SPEED_DEAD_ZONE_KMH / 3.6f
    }

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimestamp: Long = 0
    private val speedBuffer = ArrayDeque<Float>(bufferSize)

    /**
     * Calculate smoothed speed from GPS point
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param timestamp Timestamp in milliseconds
     * @return Smoothed speed in m/s
     */
    fun calculateSpeed(lat: Double, lon: Double, timestamp: Long): Float {
        val lastLatVal = lastLat
        val lastLonVal = lastLon

        if (lastLatVal == null || lastLonVal == null) {
            lastLat = lat
            lastLon = lon
            lastTimestamp = timestamp
            return 0f
        }

        val distanceMeters = DistanceCalculator.haversineDistance(
            lastLatVal, lastLonVal, lat, lon
        )
        val timeDeltaSec = (timestamp - lastTimestamp) / 1000f

        lastLat = lat
        lastLon = lon
        lastTimestamp = timestamp

        if (timeDeltaSec <= 0) return getSmoothedSpeed()

        // Stationary detection: 좌표 변화가 GPS noise floor 이내면 정지로 판정.
        // GPS jitter를 그대로 instantSpeed로 변환하면 정지 상태에서도 2-6km/h 표시되는 버그.
        val instantSpeed = if (distanceMeters < STATIONARY_DISTANCE_M) 0f
                           else (distanceMeters / timeDeltaSec).toFloat()

        // Add to moving average buffer
        speedBuffer.addLast(instantSpeed)
        if (speedBuffer.size > bufferSize) {
            speedBuffer.removeFirst()
        }

        return getSmoothedSpeed()
    }

    fun getSmoothedSpeed(): Float {
        if (speedBuffer.isEmpty()) return 0f
        val smoothed = speedBuffer.average().toFloat()
        // Dead zone: 1km/h 미만 (smoothed)은 정지로 표시 — buffer 부분 잔여 noise 흡수.
        return if (smoothed < SPEED_DEAD_ZONE_MS) 0f else smoothed
    }

    fun reset() {
        lastLat = null
        lastLon = null
        lastTimestamp = 0
        speedBuffer.clear()
    }
}
