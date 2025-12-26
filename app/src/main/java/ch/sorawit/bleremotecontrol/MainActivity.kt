package ch.sorawit.bleremotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ch.sorawit.bleremotecontrol.ble.BleManager
import ch.sorawit.bleremotecontrol.security.SecureHmacStore
import ch.sorawit.bleremotecontrol.util.TripleTapGuard

class MainActivity : ComponentActivity() {

    private lateinit var mainContainer: RelativeLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnExecute: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager
    private lateinit var tvSecretHint: TextView
    private lateinit var btnScanKey: Button

    private lateinit var openGuard: TripleTapGuard
    private lateinit var closeGuard: TripleTapGuard

    private var armedCommand: String? = null
    private val armingHandler = Handler(Looper.getMainLooper())
    private var disarmRunnable: Runnable? = null


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
        btnExecute = findViewById(R.id.btnExecute)
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
                if (isReady) {
                    mainContainer.setBackgroundColor(Color.parseColor("#A5D6A7")) // light green
                } else {
                    mainContainer.setBackgroundResource(R.drawable.background_garage_door)
                }
                updateButtonStates()
            },
            onError = { error ->
                tvStatus.text = "ERROR: $error"
                updateButtonStates()
            }
        )

        // --- Button Listeners ---
        btnExecute.setOnClickListener {
            armedCommand?.let {
                bleManager.sendSingleFrameCommand(it)
            }
            disarm()
        }

        btnRescan.setOnClickListener {
            disarm() // Disarm before starting a new scan
            startBleProcess()
        }

        btnScanKey.setOnClickListener {
            qrLauncher.launch(Intent(this, ScanQrActivity::class.java))
        }

        // --- Initial State ---
        setupTapGuards()
        refreshSecretState()
    }

    override fun onResume() {
        super.onResume()
        disarm() // Always disarm when coming back to the app for safety
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
            bleManager.stop()
        }
        updateButtonStates()
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
            if (!bleManager.isManagerReady) {
                startBleProcess()
            }
        } else {
            requestPermissionLauncher.launch(missingPermissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleProcess() {
        bleManager.stop()
        bleManager.start()
    }

    // --- Triple Tap Logic ---

    private fun isArmed(): Boolean = armedCommand != null

    private fun arm(command: String) {
        armedCommand = command
        updateButtonStates()
        disarmRunnable = Runnable { disarm() }
        armingHandler.postDelayed(disarmRunnable!!, 5000)
    }

    private fun disarm() {
        disarmRunnable?.let { armingHandler.removeCallbacks(it) }
        disarmRunnable = null
        armedCommand = null
        updateButtonStates()
        openGuard.reset()
        closeGuard.reset()
    }

    private fun updateButtonStates() {
        val isBleReady = bleManager.isManagerReady
        val hasSecret = SecureHmacStore.exists(this)
        val isCurrentlyArmed = isArmed()

        btnOpen.isEnabled = isBleReady && hasSecret && !isCurrentlyArmed
        btnClose.isEnabled = isBleReady && hasSecret && !isCurrentlyArmed
        btnExecute.isEnabled = isCurrentlyArmed

        if (::openGuard.isInitialized) openGuard.setBusy(!isBleReady || isCurrentlyArmed)
        if (::closeGuard.isInitialized) closeGuard.setBusy(!isBleReady || isCurrentlyArmed)
    }

    private fun setupTapGuards() {
        openGuard = TripleTapGuard(
            button = btnOpen,
            requiredTaps = 3,
            windowMs = 2500,
            onArmed = { arm("CmdOpen") },
            onUpdateStatus = { s -> tvStatus.text = getString(R.string.connect_status, s) }
        ).also { it.attach() }

        closeGuard = TripleTapGuard(
            button = btnClose,
            requiredTaps = 3,
            windowMs = 2500,
            onArmed = { arm("CmdClose") },
            onUpdateStatus = { s -> tvStatus.text = getString(R.string.connect_status, s) }
        ).also { it.attach() }
    }
}
