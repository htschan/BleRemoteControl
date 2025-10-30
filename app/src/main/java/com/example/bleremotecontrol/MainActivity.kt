package com.example.bleremotecontrol

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnRescan = findViewById(R.id.btnRescan)

        bleManager = BleManager(
            context = this,
            onStatusUpdate = { status ->
                runOnUiThread {
                    tvStatus.text = getString(R.string.connect_status, status)
                }
            },
            onReady = { ready ->
                runOnUiThread {
                    // Only enable if not "busy" (during a send)
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

        // Attach triple-tap guards
        openGuard = TripleTapGuard(
            button = btnOpen,
            requiredTaps = 3,
            windowMs = 2500,
            onConfirmed = {
                // Send "Open"; request nonce after sending
                bleManager.sendSingleFrameCommand("CmdOpen")
                bleManager.requestNonce() // ESP32 understands "GET_NONCE"
                // Busy remains until new nonce arrives (onReady(true) is set there)
            },
            onUpdateStatus = { s ->
                runOnUiThread {
                    tvStatus.text = getString(R.string.connect_status, s)
                }
            }
        ).also { it.attach() }

        closeGuard = TripleTapGuard(
            button = btnClose,
            requiredTaps = 3,
            windowMs = 2500,
            onConfirmed = {
                bleManager.sendSingleFrameCommand("CmdClose")
                bleManager.requestNonce()
            },
            onUpdateStatus = { s ->
                runOnUiThread {
                    tvStatus.text = getString(R.string.connect_status, s)
                }
            }
        ).also { it.attach() }


        btnRescan.setOnClickListener @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) {
            // Cancel/reset visual states
            openGuard.reset()
            closeGuard.reset()
            bleManager.stop()
            bleManager.start()
        }

        // initial: Start scanning (keep your existing permission logic)
        tvStatus.text = getString(R.string.connect_status, "Scanning…")
        bleManager.start()
    }

    override fun onPause() {
        super.onPause()
        // Reset as a precaution (prevents "stuck" (2/3) labels)
        if (::openGuard.isInitialized) openGuard.reset()
        if (::closeGuard.isInitialized) closeGuard.reset()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }
}
