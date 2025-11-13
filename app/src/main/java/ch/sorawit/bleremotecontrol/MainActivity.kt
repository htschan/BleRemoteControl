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
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {

    private lateinit var mainContainer: RelativeLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var btnExecute: Button
    private lateinit var btnRescan: Button

    private lateinit var bleManager: BleManager

    private lateinit var openGuard: TripleTapGuard
    private lateinit var closeGuard: TripleTapGuard

    private lateinit var tvSecretHint: TextView

    private lateinit var btnScanKey: Button

    private var armedCommand: String? = null
    private val armingHandler = Handler(Looper.getMainLooper())
    private var disarmRunnable: Runnable? = null

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
                SecureHmacStore.save(this, scanned.trim())
                refreshSecretState()
                tvStatus.text = getString(R.string.connect_status, "Secret saved. Scanning…")
                ensureBlePermissions { bleManager.start() }
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
        refreshSecretState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        val menuHost: MenuHost = this
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
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

        mainContainer = findViewById(R.id.main_container)
        tvStatus = findViewById(R.id.tvStatus)
        btnOpen = findViewById(R.id.btnOpen)
        btnClose = findViewById(R.id.btnClose)
        btnExecute = findViewById(R.id.btnExecute)
        btnRescan = findViewById(R.id.btnRescan)
        tvSecretHint = findViewById(R.id.tvSecretHint)
        btnScanKey = findViewById(R.id.btnScanKey)

        btnScanKey.isVisible = false

        btnScanKey.setOnClickListener { promptScan() }

        btnExecute.setOnClickListener {
            armedCommand?.let {
                bleManager.sendSingleFrameCommand(it)
            }
            disarm()
        }

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
                    if (!isArmed()) {
                        btnOpen.isEnabled = enabled
                        btnClose.isEnabled = enabled
                        if (::openGuard.isInitialized) openGuard.setBusy(!enabled)
                        if (::closeGuard.isInitialized) closeGuard.setBusy(!enabled)
                    }

                    if (ready) {
                        mainContainer.setBackgroundColor(Color.parseColor("#A5D6A7")) // light green
                    } else {
                        mainContainer.setBackgroundResource(R.drawable.background_garage_door)
                    }
                }
            },
            onError = { msg -> runOnUiThread { tvStatus.text = "Status: $msg" } }
        )

        setupTapGuards()

        refreshSecretState()
        if (SecureHmacStore.exists(this)) {
            tvStatus.text = getString(R.string.connect_status, "Scanning…")
            bleManager.start()
        } else {
            tvStatus.text = getString(R.string.connect_status, "Please scan QR secret")
        }

        btnRescan.setOnClickListener {
            disarm()
            checkPermissionsAndStart()
        }

        checkPermissionsAndStart()
    }

    private fun isArmed(): Boolean = armedCommand != null

    private fun arm(command: String) {
        armedCommand = command
        btnOpen.isEnabled = false
        btnClose.isEnabled = false
        btnExecute.isEnabled = true

        // Automatically disarm after 5 seconds
        disarmRunnable = Runnable { disarm() }
        disarmRunnable?.let { armingHandler.postDelayed(it, 5000) }
    }

    private fun disarm() {
        disarmRunnable?.let { armingHandler.removeCallbacks(it) }
        disarmRunnable = null
        armedCommand = null

        // Re-enable buttons if BLE is ready
        val bleReady = bleManager.isReady
        btnOpen.isEnabled = bleReady
        btnClose.isEnabled = bleReady
        btnExecute.isEnabled = false

        openGuard.reset()
        closeGuard.reset()
    }

    private fun promptScan() {
        val intent = Intent(this, ScanQrActivity::class.java)
        qrLauncher.launch(intent)
    }

    private fun refreshSecretState() {
        val hasSecret = SecureHmacStore.exists(this)
        tvSecretHint.isVisible = !hasSecret
        btnScanKey.isVisible = !hasSecret

        if (!hasSecret) {
            btnOpen.isEnabled = false
            btnClose.isEnabled = false
            disarm()
        }
    }

    override fun onPause() {
        super.onPause()
        disarm()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }

    override fun onResume() {
        super.onResume()
        refreshSecretState()
        disarm()
    }

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

    @SuppressLint("MissingPermission")
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
            onArmed = {
                arm("CmdOpen")
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
            onArmed = {
                arm("CmdClose")
            },
            onUpdateStatus = { s ->
                runOnUiThread {
                    tvStatus.text = getString(R.string.connect_status, s)
                }
            }
        ).also { it.attach() }
    }

    private fun ensureBlePermissions(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.ACCESS_FINE_LOCATION

            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 1001)
            } else onGranted()
        } else {
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.ACCESS_COARSE_LOCATION
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.ACCESS_FINE_LOCATION

            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 1000)
            } else onGranted()
        }
    }
}
