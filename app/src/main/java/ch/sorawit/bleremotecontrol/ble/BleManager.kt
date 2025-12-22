// ble/BleManager.kt
package ch.sorawit.bleremotecontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import ch.sorawit.bleremotecontrol.security.DeviceNameStore
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
        private const val COMMAND_GET_NONCE = "get_nonce"
        private val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val RX_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8") // Write
        private val TX_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9") // Notify
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val tag = "BleManager"

    private val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = btMgr.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    var gatt: BluetoothGatt? = null
        private set

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var readyState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                handler.post { onReady(value) }
            }
        }

    val isManagerReady: Boolean
        get() = readyState

    @Volatile private var latestNonceHex: String? = null
    @Volatile private var lastNonceMs: Long = 0L
    @Volatile private var isAwaitingNonceAfterCommand: Boolean = false

    fun start() {
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try { stopScan() } catch (_: Throwable) {}
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; writeChar = null; notifyChar = null
        readyState = false
    }

    fun sendSingleFrameCommand(command: String) {
        val nonceHex = latestNonceHex
        val canWrite = (writeChar != null && gatt != null)
        val fresh = nonceHex != null && (System.currentTimeMillis() - lastNonceMs) <= 10_000
        if (!canWrite) { postError("Not connected"); return }
        if (!fresh) { postError("No fresh nonce yet"); return }

        val keyBytes = SecureHmacStore.getBytes(context)
        if (keyBytes == null || keyBytes.isEmpty()) {
            postError("Secret key missing. Please scan QR.")
            return
        }

        val macHex = try {
            hmac8Hex("$command|$nonceHex", keyBytes)
        } finally {
            keyBytes.fill(0)
        }

        val frame = "F|$command|$nonceHex|$macHex"
        isAwaitingNonceAfterCommand = true
        writeFrame(frame)

        postStatus("Sent single-frame: $command")

        latestNonceHex = null
        readyState = false
    }

    private fun postStatus(message: String) {
        handler.post { onStatusUpdate(message) }
    }

    private fun postError(message: String) {
        handler.post { onError(message) }
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
            @Suppress("DEPRECATION")
            ch.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        payload: ByteArray
    ): Boolean {
        Log.i(tag, "writeDescriptorCompat: descriptor=${d.uuid}, payload=${bytesToHex(payload)}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeDescriptor(d, payload) == BluetoothStatusCodes.SUCCESS
            Log.i(tag, "writeDescriptorCompat (T+): result=$result")
            result
        } else {
            @Suppress("DEPRECATION")
            d.value = payload
            @Suppress("DEPRECATION")
            val result = g.writeDescriptor(d)
            Log.i(tag, "writeDescriptorCompat (pre-T): result=$result")
            result
        }
    }

    @SuppressLint("MissingPermission")
    fun requestNonce() {
        Log.i(tag, "Requesting nonce...")
        val gattDevice = gatt ?: run { postError("Not connected"); Log.e(tag, "requestNonce: gatt is null"); return }
        val characteristic = writeChar ?: run { postError("Write characteristic not available"); Log.e(tag, "requestNonce: writeChar is null"); return }

        val commandBytes = COMMAND_GET_NONCE.toByteArray(Charsets.UTF_8)
        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        Log.i(tag, "Writing '$COMMAND_GET_NONCE' to ${characteristic.uuid} with writeType=$writeType")
        val ok = writeCharacteristicCompat(gattDevice, characteristic, commandBytes, writeType)
        if (ok) {
            postStatus("Requested new nonce…")
            Log.i(tag, "Nonce request write operation initiated successfully.")
        } else {
            postError("Nonce request write failed")
            Log.e(tag, "Nonce request write operation failed to initiate.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!adapter.isEnabled) { postError("Bluetooth is off"); return }
        val scn = adapter.bluetoothLeScanner ?: run { postError("No BLE scanner"); return }
        scanner = scn

        val deviceName = DeviceNameStore.get(context)
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(deviceName).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(type: Int, res: ScanResult) {
                val n = res.device.name ?: ""
                val hasSvc = res.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                if (n == deviceName || hasSvc) {
                    postStatus("Found ${n.ifEmpty { res.device.address }} — connecting…")
                    stopScan()
                    connect(res.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                postError("Scan failed: $errorCode")
            }
        }
        postStatus("Scanning for '$deviceName'…")
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
                postError("GATT error $status"); stop(); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    postStatus("Connected. Requesting MTU...")
                    gatt.requestMtu(517) // Request MTU required by server
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    postStatus("Disconnected"); readyState = false; startScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                postStatus("MTU changed to $mtu. Discovering services...")
                gatt.discoverServices()
            } else {
                postError("MTU negotiation failed. Status: $status")
                gatt.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postError("Service discovery failed: $status"); return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                postError("Required service not found"); gatt.disconnect(); return
            }

            val wChar = service.getCharacteristic(RX_CHAR_UUID)
            val nChar = service.getCharacteristic(TX_CHAR_UUID)

            if (wChar == null || nChar == null) {
                postError("Required characteristics not found"); gatt.disconnect(); return
            }

            writeChar = wChar
            notifyChar = nChar

            Log.i(tag, "Enabling notifications for ${nChar.uuid}")
            if (!gatt.setCharacteristicNotification(nChar, true)) {
                postError("Failed to enable notifications"); gatt.disconnect(); return
            }

            val cccd = nChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                postError("CCCD descriptor not found"); gatt.disconnect(); return
            }

            Log.i(tag, "Writing to CCCD to enable notifications/indications...")
            val payload = if ((nChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val ok = writeDescriptorCompat(gatt, cccd, payload)
            if (!ok) {
                postError("Failed to write CCCD descriptor")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Log.i(tag, "onDescriptorWrite: status=$status, descriptor=${d.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (d.uuid == CCCD_UUID) {
                    Log.i(tag, "CCCD write successful. Posting delayed nonce request (500ms).")
                    handler.postDelayed({ requestNonce() }, 500)
                }
            } else {
                postError("CCCD write failed: $status")
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                processNotifyValue(value)
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                processNotifyValue(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.i(tag, "onCharacteristicWrite: status=$status, characteristic=${ch.uuid}")
            if (isAwaitingNonceAfterCommand) {
                isAwaitingNonceAfterCommand = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    requestNonce()
                } else {
                    postError("Command write failed: $status")
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                postError("Write failed: $status")
            }
        }
    }

    private fun processNotifyValue(value: ByteArray) {
        val s = value.toString(Charsets.UTF_8).trim()
        if (s.isNotEmpty()) {
            latestNonceHex = s
            lastNonceMs = System.currentTimeMillis()
            readyState = true
            postStatus("Nonce received (${s.length} hex chars)")
        } else {
            Log.w(tag, "Received empty or whitespace-only nonce. Raw value: ${bytesToHex(value)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(frame: String) {
        val gattDevice = gatt ?: return postError("Not connected")
        val characteristic = writeChar ?: return postError("Write characteristic not ready")

        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val ok = writeCharacteristicCompat(gattDevice, characteristic, frame.toByteArray(Charsets.UTF_8), writeType)
        if (!ok) postError("Write enqueue failed")
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
