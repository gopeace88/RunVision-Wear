package com.runvision.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * BLE Scanner for rLens devices
 *
 * Filters devices by name containing "ilens" or "rlens" (case-insensitive)
 * Also checks raw scan record bytes for device name (some devices advertise there)
 *
 * Reference: runvision-iq/source/RunVisionIQView.mc:778-801
 */
@SuppressLint("MissingPermission")
class RLensScanner(
    private val context: Context,
    private val onDeviceFound: (BluetoothDevice) -> Unit
) {
    companion object {
        private const val TAG = "RLensScanner"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false
    private var deviceFound = false  // Prevent multiple connections

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (deviceFound) return  // Already found, ignore subsequent results

            val device = result.device
            val deviceName = device.name?.lowercase() ?: ""

            // Also check raw scan record for device name
            val scanRecord = result.scanRecord?.bytes?.let { bytes ->
                String(bytes.filter { it in 0x20..0x7E }.toByteArray())
            }?.lowercase() ?: ""

            // Filter: "ilens" or "rlens" (case-insensitive, backward compatible)
            if (deviceName.contains("ilens") || deviceName.contains("rlens") ||
                scanRecord.contains("ilens") || scanRecord.contains("rlens")) {
                deviceFound = true
                Log.d(TAG, "Found rLens device: ${device.name} (${device.address})")
                stopScan()
                onDeviceFound(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
        }
    }

    /**
     * Start scanning for rLens devices
     */
    fun startScan() {
        if (isScanning) return
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Starting BLE scan...")
        deviceFound = false  // Reset for new scan
        isScanning = true
        scanner.startScan(null, settings, scanCallback)
    }

    /**
     * Stop scanning
     */
    fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "Stopped BLE scan")
    }

    fun isScanning(): Boolean = isScanning
}
