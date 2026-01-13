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
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private var gatt: BluetoothGatt? = null
    private var exerciseCharacteristic: BluetoothGattCharacteristic? = null
    private var lastDevice: BluetoothDevice? = null

    private val writeQueue = ArrayDeque<ByteArray>()
    private var isWriting = false

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
                val service = gatt.getService(RLensProtocol.EXERCISE_SERVICE_UUID)
                exerciseCharacteristic = service?.getCharacteristic(RLensProtocol.EXERCISE_DATA_UUID)

                if (exerciseCharacteristic != null) {
                    Log.d(TAG, "Exercise characteristic found")
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
    }

    /**
     * Send running metrics to rLens
     */
    fun sendMetrics(metrics: RunningMetrics) {
        if (exerciseCharacteristic == null || isWriting) return

        writeQueue.clear()
        writeQueue.add(RLensProtocol.createPacePacket(metrics.paceSecondsPerKm))
        writeQueue.add(RLensProtocol.createHeartRatePacket(metrics.heartRate))
        writeQueue.add(RLensProtocol.createCadencePacket(metrics.cadence))
        writeQueue.add(RLensProtocol.createDistancePacket(metrics.distanceMeters.toInt()))
        writeQueue.add(RLensProtocol.createElapsedTimePacket(metrics.elapsedSeconds))

        processWriteQueue()
    }

    private fun processWriteQueue() {
        if (isWriting || writeQueue.isEmpty()) return

        val packet = writeQueue.removeFirst()
        exerciseCharacteristic?.let { char ->
            char.value = packet
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            isWriting = true
            gatt?.writeCharacteristic(char)
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
