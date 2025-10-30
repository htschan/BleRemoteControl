package com.example.bleremotecontrol.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Button

class TripleTapGuard(
    private val button: Button,
    private val requiredTaps: Int = 3,
    private val windowMs: Long = 2500,        // Zeitfenster für alle Taps
    private val onConfirmed: () -> Unit,
    private val onUpdateStatus: (String) -> Unit = {}
) {
    private var taps = 0
    private val main = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null
    private var originalText: CharSequence = button.text
    private var busy = false

    fun attach() {
        originalText = button.text
        button.setOnClickListener {
            if (busy) return@setOnClickListener
            registerTap()
        }
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        button.isEnabled = !isBusy
    }

    fun reset() {
        taps = 0
        button.text = originalText
        resetRunnable?.let { main.removeCallbacks(it) }
        resetRunnable = null
    }

    private fun registerTap() {
        taps++
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val remaining = requiredTaps - taps
        if (taps == 1) {
            // erstes tippen: timeout starten
            scheduleReset()
        }

        if (remaining > 0) {
            button.text = buildProgressLabel(originalText.toString(), taps, requiredTaps)
            onUpdateStatus("Noch $remaining× tippen zum Ausführen…")
        } else {
            // bestätigt
            reset() // optisch zurücksetzen
            setBusy(true)
            onUpdateStatus("Befehl wird gesendet…")
            onConfirmed()
        }
    }

    private fun scheduleReset() {
        resetRunnable?.let { main.removeCallbacks(it) }
        resetRunnable = Runnable {
            if (taps > 0) {
                onUpdateStatus("Bestätigung abgebrochen")
            }
            reset()
        }.also { main.postDelayed(it, windowMs) }
    }

    private fun buildProgressLabel(base: String, taps: Int, required: Int): String {
        // z.B. "Open (2/3)"
        return "$base (${taps}/${required})"
    }
}
