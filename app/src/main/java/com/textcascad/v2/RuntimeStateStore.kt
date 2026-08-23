package com.textcascad.v2

import android.content.Context
import android.content.SharedPreferences

class RuntimeStateStore(
    context: Context,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() }
) {
    private val preferences = context.getSharedPreferences("textcascade_runtime", Context.MODE_PRIVATE)

    val sharedPreferences: SharedPreferences get() = preferences

    var statusMessage: String
        get() = preferences.getString("status_message", "") ?: ""
        set(value) {
            commitEditor(preferences.edit().putString("status_message", value))
        }

    var connectionStatusMessage: String
        get() = preferences.getString("connection_status_message", "") ?: ""
        set(value) {
            commitEditor(preferences.edit().putString("connection_status_message", value))
        }

    var backgroundStatus: String
        get() = preferences.getString("background_status", "") ?: ""
        set(value) {
            commitEditor(preferences.edit().putString("background_status", value))
        }

    var hasSession: Boolean
        get() = preferences.getBoolean("has_session", false)
        set(value) = preferences.edit().putBoolean("has_session", value).apply()

    var serviceRunning: Boolean
        get() = preferences.getBoolean("service_running", false)
        set(value) = preferences.edit().putBoolean("service_running", value).apply()

    var passwordDecryptionFailed: Boolean
        get() = preferences.getBoolean("password_decryption_failed", false)
        set(value) = preferences.edit().putBoolean("password_decryption_failed", value).apply()

    fun consumePasswordDecryptionFailure(): Boolean {
        val failed = passwordDecryptionFailed
        if (failed) passwordDecryptionFailed = false
        return failed
    }

    var securityDegraded: Boolean
        get() = preferences.getBoolean("security_degraded", false)
        set(value) = preferences.edit().putBoolean("security_degraded", value).apply()

    fun clearRuntimeState(): Boolean {
        return commitEditor(preferences.edit()
            .putString("status_message", "")
            .putString("connection_status_message", "")
            .putString("background_status", "")
            .putBoolean("has_session", false)
            .putBoolean("service_running", false)
            .remove("security_degraded")
            .remove("password_decryption_failed")
        )
    }

    fun markSessionInvalid(): Boolean {
        return commitEditor(
            preferences.edit()
                .putBoolean("has_session", false)
                .putBoolean("service_running", false)
        )
    }
}
