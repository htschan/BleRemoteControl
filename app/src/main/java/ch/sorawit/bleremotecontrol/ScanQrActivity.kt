package ch.sorawit.bleremotecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class ScanQrActivity : ComponentActivity() {

    companion object { const val EXTRA_QR_RESULT = "qr_result" }

    private lateinit var previewView: PreviewView
    private val executor = Executors.newSingleThreadExecutor()
    private var handled = false

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_qr)
        previewView = findViewById(R.id.previewView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply {
                    setAnalyzer(executor) { imageProxy ->
                        try {
                            val mediaImage = imageProxy.image ?: return@setAnalyzer
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            BarcodeScanning.getClient().process(image)
                                .addOnSuccessListener { barcodes ->
                                    if (!handled) {
                                        for (b in barcodes) {
                                            if (b.valueType == Barcode.TYPE_TEXT || b.valueType == Barcode.TYPE_URL) {
                                                val raw = b.rawValue?.trim()
                                                if (!raw.isNullOrBlank()) {
                                                    handled = true
                                                    deliverResult(raw)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { /* ignore */ }
                                .addOnCompleteListener { imageProxy.close() }
                        } catch (_: Throwable) {
                            imageProxy.close()
                        }
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun deliverResult(text: String) {
        val data = Intent().putExtra(EXTRA_QR_RESULT, text)
        setResult(RESULT_OK, data)
        finish()
    }
}