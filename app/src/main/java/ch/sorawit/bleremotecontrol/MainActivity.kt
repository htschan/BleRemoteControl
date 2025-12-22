package ch.sorawit.bleremotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ch.sorawit.bleremotecontrol.ble.BleManager
import ch.sorawit.bleremotecontrol.security.SecureHmacStore

class MainActivity : ComponentActivity() {

    private lateinit var mainContainer: RelativeLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager
    private lateinit var tvSecretHint: TextView
    private lateinit var btnScanKey: Button

    // --- PERMISSION HANDLING ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                startBleProcess()
            } else {
                tvStatus.text = "Permissions are required to use this app."
            }
        }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val scanned = res.data?.getStringExtra(ScanQrActivity.EXTRA_QR_RESULT)
            if (!scanned.isNullOrBlank()) {
                SecureHmacStore.save(this, scanned.trim())
                refreshSecretState()
            }
        }
    }

    private val provisionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshSecretState()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- View Initialization ---
        mainContainer = findViewById(R.id.main_container)
        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnRescan = findViewById(R.id.btnRescan)
        tvSecretHint = findViewById(R.id.tvSecretHint)
        btnScanKey = findViewById(R.id.btnScanKey)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_manage_secret -> {
                    provisionLauncher.launch(Intent(this, ProvisionQrActivity::class.java))
                    true
                }
                else -> false
            }
        }


        // --- BLE Manager Initialization ---
        bleManager = BleManager(
            context = this,
            onStatusUpdate = { status ->
                tvStatus.text = getString(R.string.connect_status, status)
            },
            onReady = { isReady ->
                // Single point of truth for button state
                val hasSecret = SecureHmacStore.exists(this)
                val enable = isReady && hasSecret

                btnOpen.isEnabled = enable
                btnClose.isEnabled = enable

                if (enable) {
                    mainContainer.setBackgroundColor(Color.parseColor("#A5D6A7")) // light green
                } else {
                    mainContainer.setBackgroundResource(R.drawable.background_garage_door)
                }
            },
            onError = { error ->
                tvStatus.text = "ERROR: $error"
                btnOpen.isEnabled = false
                btnClose.isEnabled = false
            }
        )

        // --- Button Listeners (Simplified) ---
        btnOpen.setOnClickListener {
            bleManager.sendSingleFrameCommand("CmdOpen")
        }

        btnClose.setOnClickListener {
            bleManager.sendSingleFrameCommand("CmdClose")
        }

        btnRescan.setOnClickListener {
            startBleProcess() // Directly start the process
        }

        btnScanKey.setOnClickListener {
            qrLauncher.launch(Intent(this, ScanQrActivity::class.java))
        }

        // --- Initial State ---
        refreshSecretState()
    }

    override fun onResume() {
        super.onResume()
        // When returning to the app, check permissions and start the scan if necessary.
        // This is safe now because checkPermissionsAndStart is idempotent.
        checkPermissionsAndStart()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }

    private fun refreshSecretState() {
        val hasSecret = SecureHmacStore.exists(this)
        tvSecretHint.isVisible = !hasSecret
        btnScanKey.isVisible = !hasSecret

        if (hasSecret) {
            checkPermissionsAndStart()
        } else {
            // No secret, so disable buttons and stop BLE
            btnOpen.isEnabled = false
            btnClose.isEnabled = false
            bleManager.stop()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!SecureHmacStore.exists(this)) {
            tvStatus.text = getString(R.string.connect_status, "Please scan QR secret")
            return
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isEmpty()) {
            // ** CRITICAL FIX **
            // Only start the process if the manager isn't already connected and ready.
            // This prevents the connect/disconnect loop when the app is resumed.
            if (!bleManager.isManagerReady) {
                startBleProcess()
            }
        } else {
            requestPermissionLauncher.launch(missingPermissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleProcess() {
        // Always stop and start to ensure a clean state
        bleManager.stop()
        bleManager.start()
    }
}
