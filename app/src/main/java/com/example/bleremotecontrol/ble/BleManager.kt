package com.example.bleremotecontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.bleremotecontrol.crypto.HmacTools
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class BleManager(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit,
    private val onReady: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val TAG = "BleManager"

    private val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = btMgr.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    // Latest nonce pushed by device
    @Volatile private var latestNonce: ByteArray? = null
    @Volatile private var lastNonceMs: Long = 0

    private val pendingCommands: Queue<ByteArray> = ConcurrentLinkedQueue()

    fun start() {
        startScan()
    }

    fun stop() {
        try { stopScan() } catch (_: Throwable) {}
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        writeChar = null
        notifyChar = null
        onReady(false)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!adapter.isEnabled) {
            onError("Bluetooth is off")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onError("No BLE scanner")
            return
        }

        val filters = listOf(
            // By name
            ScanFilter.Builder().setDeviceName("BtBridge").build(),
            // Or by service UUID
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.device.name ?: ""
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
                if (n == "BtBridge" || hasService) {
                    onStatusUpdate("Found ${n.ifEmpty { result.device.address }} — connecting…")
                    stopScan()
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onError("Scan failed: $errorCode")
            }
        }
        onStatusUpdate("Scanning…")
        scanner!!.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanCallback?.let { cb ->
            scanner?.stopScan(cb)
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= 31) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // Public API: queue a secure command
    fun sendSecureCommand(commandBytes: ByteArray, hmacKeyHex: String) {
        val key = HmacTools.hexToBytes(hmacKeyHex)
        if (key == null || key.isEmpty()) {
            onError("HMAC key not configured")
            return
        }
        // Store as a combined object: we keep command now; when nonce arrives we compute HMAC and send
        pendingCommands.add(commandBytes)
        drainIfReady(key)
    }

    @SuppressLint("MissingPermission")
    private fun writeSmall(bytes: ByteArray) {
        val c = writeChar ?: run {
            onError("Write characteristic not ready")
            return
        }
        val g = gatt ?: return

        // respect small MTU; keep under 20B per spec
        c.value = bytes
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // or NO_RESPONSE if device supports
        val ok = g.writeCharacteristic(c)
        if (!ok) onError("Write failed to enqueue")
    }

    private fun drainIfReady(hmacKey: ByteArray) {
        val nonce = latestNonce
        val fresh = nonce != null && (System.currentTimeMillis() - lastNonceMs) <= 10_000
        val canWrite = (writeChar != null && gatt != null)

        if (!fresh || !canWrite) return

        while (true) {
            val cmd = pendingCommands.poll() ?: break
            val hmac = HmacTools.hmacSha256(cmd, nonce!!, hmacKey) // HMAC over cmd||nonce
            val hmac8 = hmac.copyOfRange(0, 8)

            // 3 writes: [cmd], [nonce], [hmac8]
            writeSmall(cmd)
            writeSmall(nonce)
            writeSmall(hmac8)
            onStatusUpdate("Sent ${String(cmd)} (nonce ${nonce.size}B, hmac8)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("GATT error $status")
                stop()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatusUpdate("Connected; discovering services…")
                    gatt.requestMtu(247) // best effort
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatusUpdate("Disconnected")
                    onReady(false)
                    // auto-rescan
                    startScan()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: $mtu status=$status")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Service discovery failed: $status")
                return
            }

            var foundWrite: BluetoothGattCharacteristic? = null
            var foundNotify: BluetoothGattCharacteristic? = null

            gatt.services.forEach { svc ->
                if (svc.uuid == serviceUuid) {
                    svc.characteristics.forEach { ch ->
                        val p = ch.properties
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                            foundWrite = ch
                        }
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            foundNotify = ch
                        }
                    }
                }
            }

            if (foundWrite == null || foundNotify == null) {
                onError("Required characteristics not found")
                return
            }

            writeChar = foundWrite
            notifyChar = foundNotify

            // Enable notifications
            val ok = gatt.setCharacteristicNotification(foundNotify, true)
            if (!ok) {
                onError("Failed to enable notifications")
                return
            }
            // Write CCCD
            val cccd = foundNotify!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }

            onStatusUpdate("Ready")
            onReady(true)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic == notifyChar) {
                val data = characteristic.value ?: return
                // Assume this is the nonce (binary)
                latestNonce = data
                lastNonceMs = System.currentTimeMillis()
                // If there are queued commands, send now
                val key = HmacTools.hexToBytes(com.example.bleremotecontrol.BuildConfig.HMAC_KEY)
                if (key != null && key.isNotEmpty()) {
                    drainIfReady(key)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Write failed: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatusUpdate("Notifications enabled; waiting for nonce…")
            } else {
                onError("CCCD write failed: $status")
            }
        }
    }
}