package com.runvision.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.runvision.wear.data.RunningMetrics
import java.util.ArrayDeque

/**
 * BLE GATT Connection to rLens
 *
 * Handles connection, service discovery, and write queue
 * Reference: runvision-iq/source/RunVisionIQView.mc:570-680
 */
@SuppressLint("MissingPermission")
class RLensConnection(
    private val context: Context,
    private val onConnectionStateChanged: (ConnectionState) -> Unit
) {
    companion object {
        private const val TAG = "RLensConnection"
        private const val RECONNECT_FAST_INTERVAL = 5000L
        private const val RECONNECT_SLOW_INTERVAL = 60000L
        private const val RECONNECT_FAST_MAX = 5
    }

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        NOT_FOUND,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private var gatt: BluetoothGatt? = null
    // @Volatile 필수: GATT callback은 Binder thread에서 실행되고, isConnected()는 Main thread에서 호출됨
    @Volatile private var exerciseCharacteristic: BluetoothGattCharacteristic? = null
    private var universalSubsCharacteristic: BluetoothGattCharacteristic? = null
    private var lastDevice: BluetoothDevice? = null
    private var initializationStep = 0  // Track Universal Subscriptions init progress

    // Write queue entry: pair of (characteristic, data)
    private data class WriteEntry(val characteristic: BluetoothGattCharacteristic, val data: ByteArray)
    private val writeQueue = ArrayDeque<WriteEntry>()
    private var isWriting = false
    private var lastWriteTime = 0L
    private val WRITE_TIMEOUT_MS = 2000L  // Reset isWriting if no callback in 2 seconds

    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())

    // 의도적 disconnect 표시. disconnect() 후 늦게 도착하는 STATE_DISCONNECTED 콜백이
    // scheduleReconnect()로 좀비 재연결을 거는 것 방지.
    @Volatile private var intentionalDisconnect = false

    // Threading 모델: 모든 GATT 콜백 본문은 handler(main looper)로 post되어 메인 스레드에서만
    // 실행됨. 따라서 writeQueue/isWriting/reconnectAttempts/lastWriteTime 및 onConnectionStateChanged
    // 호출은 (sendMetrics/processWriteQueue도 main에서 호출되므로) 단일 스레드로 직렬화됨 — 별도 락 불필요.

    private val gattCallback = object : BluetoothGattCallback() {
        // 모든 콜백 본문을 handler.post로 main looper에서 실행 → 공유 상태 단일 스레드 직렬화.
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                // stale GATT 가드: connect()가 옛 gatt를 close/교체한 뒤 늦게 도착한 옛 인스턴스
                // 콜백이 새 연결 상태를 건드리는 것 차단(false disconnect/reconnect 루프 방지).
                if (gatt !== this@RLensConnection.gatt) {
                    Log.d(TAG, "Ignoring stale GATT callback (onConnectionStateChange)")
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Connected to GATT server")
                            // reconnectAttempts 리셋은 여기가 아니라 exercise characteristic 발견 시점.
                            // discoverServices()가 false면 콜백이 보장되지 않아 CONNECTING 고착 →
                            // 동일 복구 경로(disconnect→재연결)로 보냄.
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "discoverServices() initiation failed — recovering")
                                gatt.disconnect()
                            }
                        } else {
                            // status≠SUCCESS인 CONNECTED = 실패한 연결. 깨진 링크에서 discover 금지.
                            Log.e(TAG, "STATE_CONNECTED but status=$status — failed connect, recovering")
                            gatt.disconnect()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from GATT server (status=$status)")
                        exerciseCharacteristic = null
                        if (intentionalDisconnect) {
                            // 앱이 의도적으로 끊음 → 재연결 금지(좀비 재연결 방지).
                            Log.d(TAG, "Intentional disconnect — skip reconnect")
                            return@post
                        }
                        onConnectionStateChanged(ConnectionState.DISCONNECTED)
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (gatt !== this@RLensConnection.gatt) {
                    Log.d(TAG, "Ignoring stale GATT callback (onServicesDiscovered)")
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Get Exercise Data characteristic (v1.0.2: 5개 메트릭만 전송)
                    val exerciseService = gatt.getService(RLensProtocol.EXERCISE_SERVICE_UUID)
                    exerciseCharacteristic = exerciseService?.getCharacteristic(RLensProtocol.EXERCISE_DATA_UUID)

                    if (exerciseCharacteristic != null) {
                        Log.d(TAG, "Exercise characteristic found")
                        // 연결이 실제로 사용 가능해진 시점에만 백오프 카운터 리셋.
                        reconnectAttempts = 0

                        // LOW_POWER: 100~500ms 간격 → 5초 전송 주기에서 rLens 라디오 절감 최대화
                        val priorityResult = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                        Log.d(TAG, "Requested CONNECTION_PRIORITY_LOW_POWER: $priorityResult")

                        onConnectionStateChanged(ConnectionState.CONNECTED)
                    } else {
                        Log.e(TAG, "Exercise characteristic not found — recovering")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Service discovery failed: $status — recovering")
                    gatt.disconnect()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (gatt !== this@RLensConnection.gatt) {
                    Log.d(TAG, "Ignoring stale GATT callback (onCharacteristicWrite)")
                    return@post
                }
                val elapsed = System.currentTimeMillis() - lastWriteTime
                Log.d(TAG, "onCharacteristicWrite: status=$status, elapsed=${elapsed}ms, queueSize=${writeQueue.size}")

                // Exercise metrics callback: 다음 항목 처리
                isWriting = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    processWriteQueue()
                } else {
                    Log.e(TAG, "Write failed: $status")
                    writeQueue.clear()
                }
            }
        }
    }

    /**
     * Connect to rLens device
     */
    fun connect(device: BluetoothDevice) {
        // connect()는 스캐너 binder 스레드(onScanResult→onDeviceFound)와 main(재연결)에서 모두
        // 호출됨. 본문을 main looper로 옮겨 lastDevice/gatt 필드 쓰기를 단일 스레드로 통일
        // ("모든 GATT 상태 변경은 main" 불변식 완성).
        handler.post {
            lastDevice = device
            intentionalDisconnect = false   // 새 연결: 이후 drop은 재연결 대상
            onConnectionStateChanged(ConnectionState.CONNECTING)
            // 재연결 경로(scheduleReconnect→connect)는 STATE_DISCONNECTED 후에도 gatt를 닫지 않아
            // 매 재연결마다 BluetoothGatt 핸들이 누수됨(Android ~30개 한계 → status 133). 새 인스턴스
            // 생성 직전 이전 것을 close. close→재할당 순서라 방금 만든 인스턴스를 닫을 위험 없음.
            // disconnect() 후엔 gatt=null이라 이중 close도 발생하지 않음.
            gatt?.close()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /**
     * Disconnect from rLens
     */
    fun disconnect() {
        intentionalDisconnect = true    // 늦게 오는 STATE_DISCONNECTED 콜백의 재연결 차단
        lastDevice = null               // 재연결 lambda의 대상 제거(이중 방어)
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        exerciseCharacteristic = null
        // Reset write state
        isWriting = false
        writeQueue.clear()
    }

    /**
     * Send running metrics to rLens
     * v1.0.2: 5개 메트릭만 전송 (Sport Time, Pace, HR, Cadence, Distance)
     */
    fun sendMetrics(metrics: RunningMetrics) {
        val exerciseChar = exerciseCharacteristic
        if (exerciseChar == null) {
            Log.w(TAG, "Cannot send: not connected")
            return
        }

        // Check for write timeout - reset if callback was not received
        val timeSinceLastWrite = System.currentTimeMillis() - lastWriteTime
        if (isWriting && timeSinceLastWrite > WRITE_TIMEOUT_MS) {
            Log.w(TAG, "Write timeout detected ($timeSinceLastWrite ms), resetting flags")
            isWriting = false
            writeQueue.clear()
        }

        if (isWriting) {
            Log.d(TAG, "Skip send: busy (isWriting=$isWriting, elapsed=${System.currentTimeMillis() - lastWriteTime}ms)")
            return
        }

        Log.d(TAG, "Sending: HR=${metrics.heartRate}, Pace=${metrics.paceSecondsPerKm}, Cad=${metrics.cadence}, Dist=${metrics.distanceMeters.toInt()}, Time=${metrics.elapsedSeconds}s")

        // ★ v1.0.2: 5개 메트릭만 전송 (Current Time 제거, Sport Time 사용)
        writeQueue.clear()
        writeQueue.add(WriteEntry(exerciseChar, RLensProtocol.createExerciseTimePacket(metrics.elapsedSeconds)))  // ⭐ Sport Time (0x03)
        writeQueue.add(WriteEntry(exerciseChar, RLensProtocol.createPacePacket(metrics.paceSecondsPerKm)))        // Pace
        writeQueue.add(WriteEntry(exerciseChar, RLensProtocol.createHeartRatePacket(metrics.heartRate)))          // Heart Rate
        writeQueue.add(WriteEntry(exerciseChar, RLensProtocol.createCadencePacket(metrics.cadence)))              // Cadence
        writeQueue.add(WriteEntry(exerciseChar, RLensProtocol.createDistancePacket(metrics.distanceMeters.toInt()))) // Distance

        // 바로 queue 처리 시작
        processWriteQueue()
    }

    private fun processWriteQueue() {
        if (isWriting || writeQueue.isEmpty()) {
            Log.d(TAG, "processWriteQueue: isWriting=$isWriting, queueSize=${writeQueue.size}")
            return
        }

        val entry = writeQueue.removeFirst()
        entry.characteristic.value = entry.data
        entry.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        isWriting = true
        lastWriteTime = System.currentTimeMillis()

        Log.d(TAG, "Writing ${entry.data.size} bytes, remaining=${writeQueue.size}")
        val result = gatt?.writeCharacteristic(entry.characteristic)
        if (result != true) {
            Log.e(TAG, "writeCharacteristic returned false, resetting")
            isWriting = false
            writeQueue.clear()
        } else {
            Log.d(TAG, "writeCharacteristic initiated successfully")
        }
    }

    /**
     * Schedule reconnection attempt
     * Strategy: 1-5 attempts at 5s interval, then 60s interval
     */
    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = if (reconnectAttempts <= RECONNECT_FAST_MAX) {
            RECONNECT_FAST_INTERVAL
        } else {
            RECONNECT_SLOW_INTERVAL
        }

        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        onConnectionStateChanged(ConnectionState.RECONNECTING)

        handler.postDelayed({
            lastDevice?.let { device ->
                Log.d(TAG, "Attempting reconnect $reconnectAttempts")
                connect(device)
            }
        }, delay)
    }

    fun isConnected(): Boolean = exerciseCharacteristic != null
}
