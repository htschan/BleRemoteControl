// ble/BleManager.kt
package ch.sorawit.bleremotecontrol.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import ch.sorawit.bleremotecontrol.BuildConfig
import ch.sorawit.bleremotecontrol.security.SecureHmacStore
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    var gatt: BluetoothGatt? = null
        private set

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    val isReady: Boolean
        get() = gatt != null

    @Volatile private var latestNonceHex: String? = null
    @Volatile private var lastNonceMs: Long = 0L
    @Volatile private var isAwaitingNonceAfterCommand: Boolean = false

    fun start() = startScan()

    @SuppressLint("MissingPermission")
    fun stop() {
        try { stopScan() } catch (_: Throwable) {}
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; writeChar = null; notifyChar = null
        onReady(false)
    }

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
            keyBytes.fill(0)
        }

        val frame = "F|$command|$nonceHex|$macHex"
        Log.d(TAG, "Writing frame of length ${frame.toByteArray().size}: '$frame'")

        isAwaitingNonceAfterCommand = true
        writeFrame(frame)

        onStatusUpdate("Sent single-frame: $command")

        latestNonceHex = null
        onReady(false)
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(ch, payload, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = payload
            g.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        payload: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, payload) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = payload
            g.writeDescriptor(d)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestNonce() {
        val gattDevice = gatt ?: return onError("Not connected")
        val characteristic = writeChar ?: return onError("Write characteristic not available")

        val commandBytes = COMMAND_GET_NONCE.toByteArray(Charsets.UTF_8)
        val writeType =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        val ok = writeCharacteristicCompat(gattDevice, characteristic, commandBytes, writeType)
        if (ok) onStatusUpdate("Requested new nonce…") else onError("Nonce request write failed")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!adapter.isEnabled) { onError("Bluetooth is off"); return }
        val scn = adapter.bluetoothLeScanner ?: run { onError("No BLE scanner"); return }
        scanner = scn

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(BuildConfig.BLE_DEVICE_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(type: Int, res: ScanResult) {
                val n = res.device.name ?: ""
                val hasSvc = res.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
                if (n == BuildConfig.BLE_DEVICE_NAME || hasSvc) {
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
                    onStatusUpdate("Connected. Negotiating MTU…")
                    gatt.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatusUpdate("Disconnected"); onReady(false); startScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu. Discovering services…")
                onStatusUpdate("MTU OK. Discovering services…")
                gatt.discoverServices()
            } else {
                onError("MTU negotiation failed: $status")
                gatt.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Service discovery failed: $status"); return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                onError("Required service not found"); gatt.disconnect(); return
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
                onError("Required characteristics not found"); gatt.disconnect(); return
            }

            writeChar = wChar
            notifyChar = nChar

            if (!gatt.setCharacteristicNotification(nChar, true)) {
                onError("Failed to enable notifications"); gatt.disconnect(); return
            }

            val cccd = nChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onError("CCCD descriptor not found"); gatt.disconnect(); return
            }

            val ok = writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (!ok) onError("Failed to write CCCD descriptor")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestNonce()
            } else {
                onError("CCCD write failed: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (characteristic.uuid == notifyChar?.uuid) processNotifyValue(value)
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == notifyChar?.uuid) {
                processNotifyValue(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (isAwaitingNonceAfterCommand) {
                isAwaitingNonceAfterCommand = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    requestNonce()
                } else {
                    onError("Command write failed: $status")
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Write failed: $status")
            }
        }
    }

    private fun processNotifyValue(value: ByteArray) {
        // Find the first null character, as C-style strings are often null-terminated
        val nullIndex = value.indexOf(0.toByte())
        // If a null is found, consider only the part of the array before it
        val effectiveValue = if (nullIndex != -1) value.copyOfRange(0, nullIndex) else value

        // Convert the sanitized byte array to a string and trim whitespace
        val s = effectiveValue.toString(Charsets.UTF_8).trim()

        if (s.isNotEmpty()) {
            latestNonceHex = s
            lastNonceMs = System.currentTimeMillis()
            onReady(true)
            onStatusUpdate("Nonce received (${s.length} hex chars)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(frame: String) {
        val gattDevice = gatt ?: return onError("Not connected")
        val characteristic = writeChar ?: return onError("Write characteristic not ready")

        val writeType =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        val ok = writeCharacteristicCompat(gattDevice, characteristic, frame.toByteArray(Charsets.UTF_8), writeType)
        if (!ok) onError("Write enqueue failed")
    }

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
