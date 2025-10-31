// ble/BleManager.kt
package com.example.bleremotecontrol.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.example.bleremotecontrol.security.SecureHmacStore

class BleManager(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit,
    private val onReady: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val COMMAND_GET_NONCE = "GET_NONCE"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val TAG = "BleManager"

    private val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = btMgr.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    // Latest nonce as HEX string (ESP32 sends hex via notify)
    @Volatile private var latestNonceHex: String? = null
    @Volatile private var lastNonceMs: Long = 0L

    fun start() = startScan()
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
        try { stopScan() } catch (_: Throwable) {}
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; writeChar = null; notifyChar = null
        onReady(false)
    }

    // --- Public: single-frame command ---
    fun sendSingleFrameCommand(command: String) {
        val nonceHex = latestNonceHex
        val canWrite = (writeChar != null && gatt != null)
        val fresh = nonceHex != null && (System.currentTimeMillis() - lastNonceMs) <= 10_000
        if (!canWrite) { onError("Not connected"); return }
        if (!fresh) { onError("No fresh nonce yet"); return }

        val keyBytes = SecureHmacStore.getBytes(context)
        if (keyBytes == null || keyBytes.isEmpty()) {
            onError("Secret key missing. Please scan QR.")
            return
        }

        val macHex = try {
            hmac8Hex("$command|$nonceHex", keyBytes)
        } finally {
            // wipe key material best-effort
            for (i in keyBytes.indices) keyBytes[i] = 0
        }

        val frame = "F|$command|$nonceHex|$macHex"
        writeFrame(frame)
        onStatusUpdate("Sent single-frame: $command")

        latestNonceHex = null
        onReady(false) // deactivate buttons, wait for new Nonce
        requestNonce()
    }

    @SuppressLint("MissingPermission")
    fun requestNonce() {
        gatt?.let { gattDevice ->
            writeChar?.let { characteristic ->
                // Use the recommended, non-deprecated API for writing characteristics
                val commandBytes = COMMAND_GET_NONCE.toByteArray(Charsets.UTF_8)
                val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                // This is the modern, correct way to write a value for API 33+
                // It is also backward-compatible.
                val status = gattDevice.writeCharacteristic(characteristic, commandBytes, writeType)

                // The new call returns a status code, not a boolean.
                // BluetoothStatusCodes.SUCCESS is 0.
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onStatusUpdate("Requested new nonce…")
                } else {
                    // It's good practice to handle the failure case.
                    onError("Nonce request write failed with status: $status")
                }
            } ?: onError("Write characteristic not available")
        } ?: onError("Not connected")
    }

    // --- BLE plumbing (scan/connect/discover/notify) ---
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!adapter.isEnabled) { onError("Bluetooth is off"); return }
        val scn = adapter.bluetoothLeScanner ?: run { onError("No BLE scanner"); return }
        scanner = scn

        val filters = listOf(
            ScanFilter.Builder().setDeviceName("BtBridge").build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(type: Int, res: ScanResult) {
                val n = res.device.name ?: ""
                val hasSvc = res.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
                if (n == "BtBridge" || hasSvc) {
                    onStatusUpdate("Found ${n.ifEmpty { res.device.address }} — connecting…")
                    stopScan()
                    connect(res.device)
                }
            }
            override fun onScanFailed(errorCode: Int) = onError("Scan failed: $errorCode")
        }
        onStatusUpdate("Scanning…")
        scn.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() { scanCallback?.let { scanner?.stopScan(it) }; scanCallback = null }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("GATT error $status"); stop(); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatusUpdate("Connected; discovering services…")
                    gatt.requestMtu(247) // enables single-frame headroom
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatusUpdate("Disconnected"); onReady(false); startScan()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Service discovery failed: $status")
                return
            }

            // Find the service and characteristics in a more idiomatic way
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                onError("Required service not found")
                gatt.disconnect()
                return
            }

            val wChar = service.characteristics.firstOrNull {
                val p = it.properties
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                        (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            }
            val nChar = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }

            if (wChar == null || nChar == null) {
                onError("Required characteristics not found")
                gatt.disconnect()
                return
            }

            writeChar = wChar
            notifyChar = nChar

            // Enable notifications on the characteristic
            if (!gatt.setCharacteristicNotification(nChar, true)) {
                onError("Failed to enable notifications")
                gatt.disconnect()
                return
            }

            // Find the CCCD for the notification characteristic
            val cccd = nChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onError("CCCD descriptor not found")
                gatt.disconnect()
                return
            }

            // --- THIS IS THE KEY FIX ---
            // Use the modern, non-deprecated API to write to the descriptor
            val writeStatus =
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                onError("Failed to write CCCD descriptor")
            }
            // The result is confirmed in onDescriptorWrite
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) onError("CCCD write failed: $status")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            // It's good practice to call the super method.
            super.onCharacteristicChanged(gatt, ch, value)

            if (ch.uuid == notifyChar?.uuid) {
                // ESP32 sends nonce as ASCII HEX (e.g., "A1B2C3..."), keep it as a hex string.
                // The 'value' parameter is the new, direct way to get the data.
                val s = value.toString(Charsets.UTF_8).trim()
                if (s.isNotEmpty()) {
                    latestNonceHex = s
                    lastNonceMs = System.currentTimeMillis()
                    // We are "ready" once connected and have a recent nonce.
                    onReady(true)
                    onStatusUpdate("Nonce received (${s.length} hex chars)")
                }
            }
        }


        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) onError("Write failed: $status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(frame: String) {
        // Use let for cleaner, null-safe execution
        gatt?.let { gattDevice ->
            writeChar?.let { characteristic ->
                // Determine the write type. Prefer NO_RESPONSE if the characteristic supports it.
                val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }

                val frameBytes = frame.toByteArray(Charsets.UTF_8)

                // Use the modern, non-deprecated API to write the characteristic value.
                // This is also backward-compatible.
                val status = gattDevice.writeCharacteristic(characteristic, frameBytes, writeType)

                // The new call returns a status code (like GATT_SUCCESS which is 0).
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onError("Write enqueue failed with status: $status")
                }
            } ?: onError("Write characteristic not ready")
        } ?: onError("Not connected")
    }

    // --- Crypto helpers ---
    private fun hmac8Hex(message: String, keyBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val full = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val first8 = full.copyOfRange(0, 8)
        return bytesToHex(first8)
    }

    private fun bytesToHex(b: ByteArray): String {
        val hex = CharArray(b.size * 2)
        val digits = "0123456789abcdef".toCharArray()
        var i = 0
        b.forEach { v ->
            val x = v.toInt() and 0xff
            hex[i++] = digits[x ushr 4]
            hex[i++] = digits[x and 0x0f]
        }
        return String(hex)
    }
}
