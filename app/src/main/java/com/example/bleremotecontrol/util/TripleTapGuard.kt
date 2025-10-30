package com.example.bleremotecontrol.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Button

class TripleTapGuard(
    private val button: Button,
    private val requiredTaps: Int = 3,
    private val windowMs: Long = 2500,        // Time window for all taps
    private val onConfirmed: () -> Unit,
    private val onUpdateStatus: (String) -> Unit = {}
) {
    private var taps = 0
    private val main = Handler(Looper.getMainLooper())
    private var confirmationRunnable: Runnable? = null
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
        if (!isBusy) {
            // When no longer busy, ensure the guard is in a clean state.
            reset()
        }
    }

    fun reset() {
        taps = 0
        button.text = originalText
        confirmationRunnable?.let { main.removeCallbacks(it) }
        confirmationRunnable = null
    }

    private fun registerTap() {
        taps++
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (taps == 1) {
            // First tap starts the confirmation window
            scheduleConfirmation()
        }

        button.text = buildProgressLabel(originalText.toString(), taps, requiredTaps)
        onUpdateStatus("$taps/$requiredTaps taps registered.")
    }

    private fun scheduleConfirmation() {
        confirmationRunnable = Runnable {
            val finalTapCount = taps
            if (finalTapCount == requiredTaps) {
                // Correct number of taps, execute the action.
                onUpdateStatus("Sending command...")
                reset()
                setBusy(true) // Disable button during BLE operation.
                onConfirmed()
            } else {
                // Wrong number of taps.
                if (finalTapCount > 0) {
                    onUpdateStatus("Action cancelled: $finalTapCount of $requiredTaps taps. Expected $requiredTaps.")
                }
                reset()
            }
        }.also { main.postDelayed(it, windowMs) }
    }

    private fun buildProgressLabel(base: String, taps: Int, required: Int): String {
        return "$base ($taps/$required)"
    }
}
