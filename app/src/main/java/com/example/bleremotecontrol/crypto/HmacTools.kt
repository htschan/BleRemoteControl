package com.example.bleremotecontrol.crypto

import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacTools {
    /**
     * HMAC-SHA256 over (cmd || nonce), returns 32 bytes. Caller may truncate.
     */
    fun hmacSha256(cmd: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(cmd)
        mac.update(nonce)
        return mac.doFinal()
    }

// In HmacTools.kt

    fun hexToBytes(hex: String): ByteArray {
        // First, remove any hyphens that are common in UUID formats.
        val cleanHex = hex.replace("-", "")

        try {
            // Ensure the cleaned string has an even number of characters.
            if (cleanHex.length % 2 != 0) {
                Log.w("HmacTools", "Odd-length hex string received: $cleanHex")
                return ByteArray(0)
            }

            // Use the cleaned string for conversion.
            return cleanHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        } catch (e: NumberFormatException) {
            // Log the error for debugging
            Log.e("HmacTools", "Invalid hexadecimal string provided: $cleanHex", e)
            // Return an empty byte array to prevent a crash
            return ByteArray(0)
        }
    }
}