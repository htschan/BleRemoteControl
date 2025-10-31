package com.example.bleremotecontrol.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Base64

object SecureHmacStore {
    private const val PREFS_NAME = "secrets.encrypted"
    private const val KEY_HMAC = "hmac_key"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        PREFS_NAME,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun exists(ctx: Context): Boolean =
        prefs(ctx).contains(KEY_HMAC)

    /** Save as base64 of UTF-8 bytes. */
    fun save(ctx: Context, secretText: String) {
        // minimal validation example (UUID-ähnlich erlaubt, aber nicht erzwungen)
        require(secretText.isNotBlank()) { "Secret is empty" }
        val b64 = Base64.encodeToString(secretText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        prefs(ctx).edit().putString(KEY_HMAC, b64).apply()
    }

    /** Returns a fresh copy of the key bytes (UTF-8). Caller should zero it after use. */
    fun getBytes(ctx: Context): ByteArray? {
        val b64 = prefs(ctx).getString(KEY_HMAC, null) ?: return null
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        // raw = UTF-8 of secret; return a copy the caller may wipe
        return raw
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HMAC).apply()
    }
}
