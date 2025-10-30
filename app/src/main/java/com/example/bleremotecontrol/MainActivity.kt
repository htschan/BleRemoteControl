package com.example.bleremotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.bleremotecontrol.ble.BleManager
import com.example.bleremotecontrol.util.TripleTapGuard

class MainActivity : ComponentActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager

    private lateinit var openGuard: TripleTapGuard
    private lateinit var closeGuard: TripleTapGuard

    // --- PERMISSION HANDLING ---

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            startBleProcess()
        } else {
            tvStatus.text = "Permissions required. Please grant permissions in settings."
        }
    }

    // --- LIFECYCLE ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnRescan = findViewById(R.id.btnRescan)

        bleManager = BleManager(
            context = this,
            onStatusUpdate = { status -> runOnUiThread { tvStatus.text = getString(R.string.connect_status, status) } },
            onReady = { ready ->
                runOnUiThread {
                    if (!::openGuard.isInitialized || !::closeGuard.isInitialized) {
                        btnOpen.isEnabled = ready
                        btnClose.isEnabled = ready
                    } else {
                        openGuard.setBusy(!ready)
                        closeGuard.setBusy(!ready)
                    }
                }
            },
            onError = { msg -> runOnUiThread { tvStatus.text = "Status: $msg" } }
        )

        setupTapGuards()

        btnRescan.setOnClickListener {
            openGuard.reset()
            closeGuard.reset()
            checkPermissionsAndStart()
        }

        checkPermissionsAndStart()
    }

    override fun onPause() {
        super.onPause()
        if (::openGuard.isInitialized) openGuard.reset()
        if (::closeGuard.isInitialized) closeGuard.reset()
    }

    @SuppressLint("MissingPermission") // Permissions are checked before calling stop()
    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }

    // --- PRIVATE HELPERS ---

    private fun checkPermissionsAndStart() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isEmpty()) {
            startBleProcess()
        } else {
            tvStatus.text = "Requesting permissions..."
            requestPermissionLauncher.launch(missingPermissions)
        }
    }

    @SuppressLint("MissingPermission") // Permissions are checked before this is called
    private fun startBleProcess() {
        bleManager.stop()
        tvStatus.text = getString(R.string.connect_status, "Scanning…")
        bleManager.start()
    }

    private fun setupTapGuards() {
        openGuard = TripleTapGuard(
            button = btnOpen,
            requiredTaps = 3,
            windowMs = 2500,
            onConfirmed = {
                bleManager.sendSingleFrameCommand("CmdOpen")
                bleManager.requestNonce()
            },
            onUpdateStatus = { s -> runOnUiThread { tvStatus.text = getString(R.string.connect_status, s) } }
        ).also { it.attach() }

        closeGuard = TripleTapGuard(
            button = btnClose,
            requiredTaps = 3,
            windowMs = 2500,
            onConfirmed = {
                bleManager.sendSingleFrameCommand("CmdClose")
                bleManager.requestNonce()
            },
            onUpdateStatus = { s -> runOnUiThread { tvStatus.text = getString(R.string.connect_status, s) } }
        ).also { it.attach() }
    }
}
