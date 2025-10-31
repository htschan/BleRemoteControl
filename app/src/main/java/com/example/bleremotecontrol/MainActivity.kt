package com.example.bleremotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.bleremotecontrol.ble.BleManager
import com.example.bleremotecontrol.security.SecureHmacStore
import com.example.bleremotecontrol.util.TripleTapGuard
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager

    private lateinit var openGuard: TripleTapGuard
    private lateinit var closeGuard: TripleTapGuard

    private lateinit var tvSecretHint: TextView

    private lateinit var btnScanKey: Button

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

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                startBleProcess()
            } else {
                tvStatus.text = "Permissions required. Please grant permissions in settings."
            }
        }

    // --- LIFECYCLE ---
    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val scanned = res.data?.getStringExtra(ScanQrActivity.EXTRA_QR_RESULT)
            if (!scanned.isNullOrBlank()) {
                // Minimal validation (optionally stricter: UUID-Regex)
                SecureHmacStore.save(this, scanned.trim())
                tvStatus.text = getString(R.string.connect_status, "Secret saved. Scanning…")
                bleManager.start() // if not already started
            } else {
                tvStatus.text = getString(R.string.connect_status, "QR scan canceled/empty")
            }
        } else {
            tvStatus.text = getString(R.string.connect_status, "QR scan canceled")
        }
    }

    private val provisionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshSecretState()   // nach Rückkehr Zustand neu bewerten
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)  // <-- res/menu/menu_main.xml
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_manage_secret -> {
                    provisionLauncher.launch(Intent(this, ProvisionQrActivity::class.java))
                    true
                }

                else -> false
            }
        }

        val menuHost: MenuHost = this
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(
                menu: android.view.Menu,
                menuInflater: android.view.MenuInflater
            ) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_manage_secret -> {
                        startActivity(Intent(this@MainActivity, ProvisionQrActivity::class.java))
                        true
                    }

                    else -> false
                }
            }
        }, this, Lifecycle.State.RESUMED)

        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnRescan = findViewById(R.id.btnRescan)
        tvSecretHint = findViewById(R.id.tvSecretHint)
        btnScanKey = findViewById(R.id.btnScanKey)
        btnScanKey.isVisible = false

        btnScanKey.setOnClickListener { promptScan() }

        bleManager = BleManager(
            context = this,
            onStatusUpdate = { status ->
                runOnUiThread {
                    tvStatus.text = getString(R.string.connect_status, status)
                }
            },
            onReady = { ready ->
                runOnUiThread {
                    val hasSecret = SecureHmacStore.exists(this)
                    val enabled = ready && hasSecret
                    // Buttons only active if BLE is ready + secret is present
                    btnOpen.isEnabled = enabled
                    btnClose.isEnabled = enabled

                    if (::openGuard.isInitialized) openGuard.setBusy(!enabled)
                    if (::closeGuard.isInitialized) closeGuard.setBusy(!enabled)
                }
            },
            onError = { msg -> runOnUiThread { tvStatus.text = "Status: $msg" } }
        )

        setupTapGuards()

        // Check secret on start:
        refreshSecretState()
        if (SecureHmacStore.exists(this)) {
            tvStatus.text = getString(R.string.connect_status, "Scanning…")
            bleManager.start()
        } else {
            tvStatus.text = getString(R.string.connect_status, "Please scan QR secret")
        }

        btnRescan.setOnClickListener {
            openGuard.reset()
            closeGuard.reset()
            checkPermissionsAndStart()
        }

        checkPermissionsAndStart()
    }

    private fun promptScan() {
        val intent = Intent(this, ScanQrActivity::class.java)
        qrLauncher.launch(intent)
    }

    private fun refreshSecretState() {
        val hasSecret = SecureHmacStore.exists(this)
        tvSecretHint.isVisible = !hasSecret
        btnScanKey.isVisible = !hasSecret

        // Reset taps/guard and disable buttons if secret is missing
        if (!hasSecret) {
            btnOpen.isEnabled = false
            btnClose.isEnabled = false
            if (::openGuard.isInitialized) openGuard.reset()
            if (::closeGuard.isInitialized) closeGuard.reset()
        }
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
        if (SecureHmacStore.exists(this)) {
            bleManager.stop()
            tvStatus.text = getString(R.string.connect_status, "Scanning…")
            bleManager.start()
        }
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
    }
}
