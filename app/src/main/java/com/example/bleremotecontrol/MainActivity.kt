package com.example.bleremotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.bleremotecontrol.ble.BleManager

class MainActivity : ComponentActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        maybeStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Quick sanity: BLE available?
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btMgr.adapter
        if (adapter == null) {
            finish() // no BLE
            return
        }

        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnRescan = findViewById(R.id.btnRescan)

        bleManager = BleManager(
            context = this,
            onStatusUpdate = { status -> runOnUiThread { tvStatus.text = getString(R.string.connect_status, status) } },
            onReady = { ready -> runOnUiThread {
                btnOpen.isEnabled = ready
                btnClose.isEnabled = ready
            }},
            onError = { msg -> runOnUiThread { tvStatus.text = "Status: $msg" } }
        )

        btnOpen.setOnClickListener { queueCommand("CmdCopen") }
        btnClose.setOnClickListener { queueCommand("CmdClose") }

        btnRescan.setOnClickListener {
            bleManager.stop()
            bleManager.start()
        }

        ensurePermissions()
    }

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) need += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (need.isNotEmpty()) {
            permissionsLauncher.launch(need.toTypedArray())
        } else {
            maybeStart()
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun maybeStart() {
        tvStatus.text = "Status: Scanning…"
        bleManager.start()
    }

    private fun queueCommand(cmd: String) {
        // BleManager will combine: [cmd] + [nonce] + [HMAC(cmd||nonce)] into three writes
        bleManager.sendSecureCommand(
            commandBytes = cmd.toByteArray(Charsets.UTF_8),
            hmacKeyHex = BuildConfig.HMAC_KEY
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }
}
