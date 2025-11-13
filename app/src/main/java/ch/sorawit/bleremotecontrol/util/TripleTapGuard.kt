package ch.sorawit.bleremotecontrol.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Button

class TripleTapGuard(
    private val button: Button,
    private val requiredTaps: Int = 3,
    private val windowMs: Long = 2500, // Time window to complete all taps
    private val onArmed: () -> Unit,
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
        if (!isBusy && taps > 0) {
            reset()
        }
    }

    fun reset() {
        taps = 0
        button.text = originalText
        resetRunnable?.let { main.removeCallbacks(it) }
        resetRunnable = null
    }

    private fun registerTap() {
        // On the first tap, start a timer to reset the guard.
        if (taps == 0) {
            resetRunnable = Runnable {
                onUpdateStatus("Action cancelled (timed out).")
                reset()
            }.also { main.postDelayed(it, windowMs) }
        }

        taps++
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        button.text = buildProgressLabel(originalText.toString(), taps, requiredTaps)
        onUpdateStatus("$taps/$requiredTaps taps registered.")

        if (taps >= requiredTaps) {
            // Reached the required number of taps, so arm immediately.
            resetRunnable?.let { main.removeCallbacks(it) }
            resetRunnable = null
            onUpdateStatus("Armed! Press Execute.")
            onArmed()
        }
    }

    private fun buildProgressLabel(base: String, taps: Int, required: Int): String {
        return "$base ($taps/$required)"
    }
}
