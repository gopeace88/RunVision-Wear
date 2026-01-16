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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    reconnectAttempts = 0
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    exerciseCharacteristic = null
                    onConnectionStateChanged(ConnectionState.DISCONNECTED)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Get Exercise Data characteristic (v1.0.2: 5개 메트릭만 전송)
                val exerciseService = gatt.getService(RLensProtocol.EXERCISE_SERVICE_UUID)
                exerciseCharacteristic = exerciseService?.getCharacteristic(RLensProtocol.EXERCISE_DATA_UUID)

                if (exerciseCharacteristic != null) {
                    Log.d(TAG, "Exercise characteristic found")

                    // Request HIGH priority connection to prevent OS from throttling BLE during screen off
                    val priorityResult = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.d(TAG, "Requested CONNECTION_PRIORITY_HIGH: $priorityResult")

                    onConnectionStateChanged(ConnectionState.CONNECTED)
                } else {
                    Log.e(TAG, "Exercise characteristic not found")
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
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

    /**
     * Connect to rLens device
     */
    fun connect(device: BluetoothDevice) {
        lastDevice = device
        onConnectionStateChanged(ConnectionState.CONNECTING)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Disconnect from rLens
     */
    fun disconnect() {
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
