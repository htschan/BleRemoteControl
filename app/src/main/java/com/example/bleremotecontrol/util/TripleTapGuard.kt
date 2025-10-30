package com.example.bleremotecontrol.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Button

class TripleTapGuard(
    private val button: Button,
    private val requiredTaps: Int = 3,
    private val windowMs: Long = 2500,        // alle Taps müssen in diesem Fenster passieren
    private val onConfirmed: () -> Unit,
    private val onUpdateStatus: (String) -> Unit = {}
) {
    private var taps = 0
    private val main = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null
    private var originalText: CharSequence = button.text
    private var busy = false
    private var windowStartedAt = 0L

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
        windowStartedAt = 0L
    }

    private fun registerTap() {
        val now = System.currentTimeMillis()

        // erstes Tippen: Fenster starten
        if (taps == 0) {
            windowStartedAt = now
            scheduleReset()
        }

        // Wenn das Zeitfenster abgelaufen ist, frisch beginnen (wie erstes Tippen)
        if (windowStartedAt == 0L || now - windowStartedAt > windowMs) {
            reset()
            windowStartedAt = now
            scheduleReset()
        }

        taps++
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // --- wichtigste Logik: EXAKT requiredTaps ---
        when {
            taps < requiredTaps -> {
                // Fortschritt anzeigen
                button.text = buildProgressLabel(originalText.toString(), taps, requiredTaps)
                onUpdateStatus("Noch ${requiredTaps - taps}× tippen zum Ausführen…")
            }

            taps == requiredTaps -> {
                // Bestätigt: genau die gewünschte Anzahl erreicht
                confirmAndLock()
            }

            else -> {
                // ZU VIELE TAPS => Abbruch, KEINE Aktion
                onUpdateStatus("Abgebrochen: zu viele Taps")
                // kurze Haptik fürs Feedback
                button.performHapticFeedback(HapticFeedbackConstants.REJECT)
                reset()
            }
        }
    }

    private fun confirmAndLock() {
        // vor dem Callback sperren, um Doppelauslösung zu verhindern
        setBusy(true)
        val base = originalText.toString()
        button.text = "$base ✓"
        onUpdateStatus("Befehl wird gesendet…")

        // Fenster/Taps zurücksetzen, damit ein neuer Zyklus sauber starten kann
        reset()

        // Aktion ausführen
        onConfirmed()
    }

    private fun scheduleReset() {
        resetRunnable?.let { main.removeCallbacks(it) }
        resetRunnable = Runnable {
            if (taps > 0) onUpdateStatus("Bestätigung abgebrochen")
            reset()
        }.also { main.postDelayed(it, windowMs) }
    }

    private fun buildProgressLabel(base: String, taps: Int, required: Int): String {
        // z.B. "Open (2/3)"
        return "$base (${taps}/${required})"
    }
}
