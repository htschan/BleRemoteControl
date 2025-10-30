// ble/BleManager.kt
package com.example.bleremotecontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.bleremotecontrol.BuildConfig
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    // Latest nonce as HEX string (ESP32 sends hex via notify)
    @Volatile private var latestNonceHex: String? = null
    @Volatile private var lastNonceMs: Long = 0L

    fun start() = startScan()
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

        val key = BuildConfig.HMAC_KEY
        if (key.isBlank()) { onError("HMAC key not configured"); return }

        val macHex = hmac8Hex("$command|$nonceHex", key)
        val frame = "F|$command|$nonceHex|$macHex"
        writeFrame(frame)
        onStatusUpdate("Sent single-frame: $command")
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
        gatt = if (Build.VERSION.SDK_INT >= 31)
            device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCb)
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
            if (status != BluetoothGatt.GATT_SUCCESS) { onError("Service discovery failed: $status"); return }
            var w: BluetoothGattCharacteristic? = null
            var n: BluetoothGattCharacteristic? = null

            gatt.services.forEach { svc ->
                if (svc.uuid == serviceUuid) {
                    svc.characteristics.forEach { ch ->
                        val p = ch.properties
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) w = ch
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) n = ch
                    }
                }
            }
            if (w == null || n == null) { onError("Required characteristics not found"); return }
            writeChar = w; notifyChar = n

            val ok = gatt.setCharacteristicNotification(n, true)
            if (!ok) { onError("Failed to enable notifications"); return }
            // Enable CCCD
            val cccd = n!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            onStatusUpdate("Notifications enabled; waiting for nonce…")
            // Buttons stay disabled until we receive a (fresh) nonce.
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) onError("CCCD write failed: $status")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch == notifyChar) {
                // ESP32 sends nonce as ASCII HEX (e.g., "A1B2C3..."), keep it as hex string
                val s = ch.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
                if (s.isNotEmpty()) {
                    latestNonceHex = s
                    lastNonceMs = System.currentTimeMillis()
                    // We are "ready" once connected + have a recent nonce
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
        val c = writeChar ?: run { onError("Write characteristic not ready"); return }
        val g = gatt ?: run { onError("Not connected"); return }

        // Prefer NO_RESPONSE if supported; otherwise default is fine
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = frame.toByteArray(Charsets.UTF_8)
        val ok = g.writeCharacteristic(c)
        if (!ok) onError("Write enqueue failed")
    }

    // --- Crypto helpers ---
    private fun hmac8Hex(message: String, keyUtf8: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyUtf8.toByteArray(Charsets.UTF_8), "HmacSHA256"))
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
