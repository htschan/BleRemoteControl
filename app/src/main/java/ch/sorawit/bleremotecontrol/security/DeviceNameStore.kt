package ch.sorawit.bleremotecontrol.security

import android.content.Context
import android.content.SharedPreferences
import ch.sorawit.bleremotecontrol.BuildConfig

object DeviceNameStore {
    private const val PREFS_NAME = "device_name.prefs"
    private const val KEY_DEVICE_NAME = "ble_device_name"

    private fun prefs(ctx: Context): SharedPreferences = 
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(ctx: Context): String {
        // The default value is taken from BuildConfig, which respects local.properties
        return prefs(ctx).getString(KEY_DEVICE_NAME, BuildConfig.BLE_DEVICE_NAME) ?: BuildConfig.BLE_DEVICE_NAME
    }

    fun save(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}
