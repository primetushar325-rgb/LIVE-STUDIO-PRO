package com.livevip.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stream keys are credentials. They are stored in keystore backed encrypted
 * preferences and are NEVER written to logs or diagnostics in full.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "live_vip_secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        Log.w("SecureKeyStore", "Encrypted storage unavailable, falling back to private prefs")
        context.getSharedPreferences("live_vip_keys_fallback", Context.MODE_PRIVATE)
    }

    fun setStreamKey(profileId: String, key: String) {
        prefs.edit().putString("key_$profileId", key).apply()
    }

    fun getStreamKey(profileId: String): String = prefs.getString("key_$profileId", "") ?: ""

    fun removeStreamKey(profileId: String) {
        prefs.edit().remove("key_$profileId").apply()
    }

    companion object {
        /** Safe representation for UI/diagnostics: never shows the full key. */
        fun mask(key: String): String = when {
            key.isEmpty() -> "NOT SET"
            key.length <= 8 -> "****"
            else -> "${key.take(4)}••••••••${key.takeLast(2)} (${key.length} chars)"
        }
    }
}
