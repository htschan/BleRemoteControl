package ch.sorawit.bleremotecontrol

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import ch.sorawit.bleremotecontrol.security.DeviceNameStore
import ch.sorawit.bleremotecontrol.security.SecureHmacStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

class ProvisionQrActivity : ComponentActivity() {

    private lateinit var etSecret: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var btnOk: Button
    private lateinit var btnReset: Button
    private lateinit var imgQr: ImageView
    private lateinit var btnShare: Button

    private var lastQrBitmap: Bitmap? = null
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provision_qr)

        etSecret = findViewById(R.id.etSecret)
        etDeviceName = findViewById(R.id.etDeviceName)
        btnOk = findViewById(R.id.btnOk)
        btnReset = findViewById(R.id.btnReset)
        imgQr = findViewById(R.id.imgQr)
        btnShare = findViewById(R.id.btnShare)

        imgQr.setImageDrawable(null)
        btnShare.isEnabled = false

        // Load current device name
        etDeviceName.setText(DeviceNameStore.get(this))

        btnOk.setOnClickListener {
            // Save the device name
            val deviceName = etDeviceName.text.toString().trim()
            if (deviceName.isNotBlank()) {
                DeviceNameStore.save(this, deviceName)
            } else {
                Toast.makeText(this, "Device name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Handle the HMAC secret
            val rawSecret = etSecret.text.toString().trim()
            if (rawSecret.isNotBlank()) {
                if (!uuidRegex.matches(rawSecret)) {
                    Toast.makeText(this, "Please enter a valid UUID", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SecureHmacStore.save(this, rawSecret)
                generateAndShowQr(rawSecret)
            }

            // Mask and lock input fields
            etSecret.transformationMethod = PasswordTransformationMethod.getInstance()
            etSecret.isEnabled = false
            etDeviceName.isEnabled = false

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            lastQrBitmap?.let { bmp ->
                shareQrPng(bmp)
            }
        }

        btnReset.setOnClickListener {
            SecureHmacStore.clear(this)
            etSecret.text?.clear()
            etSecret.transformationMethod = null
            etSecret.isEnabled = true
            etDeviceName.isEnabled = true
            imgQr.setImageDrawable(null)
            btnShare.isEnabled = false
            lastQrBitmap = null
        }
    }

    private fun generateAndShowQr(content: String) {
        val size = 800 // px
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        imgQr.setImageBitmap(bmp)
        lastQrBitmap = bmp
        btnShare.isEnabled = true
    }

    private fun shareQrPng(bmp: Bitmap) {
        try {
            val dir = File(cacheDir, "shared_qr").apply { mkdirs() }
            val file = File(dir, "hmac_qr.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri: Uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share QR Code"))
        } catch (e: Exception) {
            Toast.makeText(this, "Sharing failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
