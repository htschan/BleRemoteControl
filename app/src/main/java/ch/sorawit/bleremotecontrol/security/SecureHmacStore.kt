package ch.sorawit.bleremotecontrol.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureHmacStore {
    private const val PREFS_NAME = "secrets.encrypted"
    private const val KEY_HMAC_HEX = "hmac_key_hex"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        PREFS_NAME,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun exists(ctx: Context): Boolean = prefs(ctx).contains(KEY_HMAC_HEX)

    /**
     * Saves the secret from the QR code (a UUID) as a clean hex string.
     */
    fun save(ctx: Context, secretUuid: String) {
        require(secretUuid.isNotBlank()) { "Secret is empty" }
        // Remove hyphens and store the raw 32-char hex string.
        val hexString = secretUuid.replace("-", "")
        prefs(ctx).edit().putString(KEY_HMAC_HEX, hexString).apply()
    }

    /**
     * Returns the HMAC key as a 16-byte array, decoded from the stored hex string.
     */
    fun getBytes(ctx: Context): ByteArray? {
        val hexString = prefs(ctx).getString(KEY_HMAC_HEX, null) ?: return null
        // A 32-char hex string becomes a 16-byte array.
        return hexToBytes(hexString)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HMAC_HEX).apply()
    }

    /**
     * Converts a hexadecimal string into a byte array.
     */
    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Must have an even length" }
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
