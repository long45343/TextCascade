package com.textcascad.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

class AppPreferences(
    context: Context,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    private val encryptor: (String) -> String? = EncryptedPrefs::tryEncrypt
) {
    private val preferences = context.getSharedPreferences("textcascade", Context.MODE_PRIVATE)

    val sharedPreferences: SharedPreferences get() = preferences

    var serverUrl: String
        get() = preferences.getString("server_url", ClipConfig.DEFAULT_SERVER_URL) ?: ClipConfig.DEFAULT_SERVER_URL
        set(value) = putString("server_url", value.trim().trimEnd('/'))

    var username: String
        get() = preferences.getString("username", "") ?: ""
        set(value) = putString("username", value.trim())

    var hashRounds: Int
        get() = preferences.getInt("hash_rounds", ClipConfig.DEFAULT_HASH_ROUNDS)
        set(value) = preferences.edit().putInt("hash_rounds", value).apply()

    var salt: String
        get() = preferences.getString("salt", "") ?: ""
        set(value) = putString("salt", value)

    var localMaxClipboardBytes: Long
        get() = ClipConfig.sanitizeStoredClipboardLimit(
            preferences.getLong("local_max_clipboard_bytes", ClipConfig.DEFAULT_MAX_TEXT_BYTES)
        )
        set(value) = preferences.edit()
            .putLong("local_max_clipboard_bytes", ClipConfig.clampClipboardLimit(value))
            .apply()

    var cipherEnabled: Boolean
        get() = preferences.getBoolean("cipher_enabled", true)
        set(value) = preferences.edit().putBoolean("cipher_enabled", value).apply()

    var relaunchOnBoot: Boolean
        get() = preferences.getBoolean("relaunch_on_boot", false)
        set(value) = preferences.edit().putBoolean("relaunch_on_boot", value).apply()

    var websocketStatusNotification: Boolean
        get() = preferences.getBoolean("websocket_status_notification", false)
        set(value) = preferences.edit().putBoolean("websocket_status_notification", value).apply()

    var trustAllCerts: Boolean
        get() = preferences.getBoolean("trust_all_certs", false)
        set(value) = preferences.edit().putBoolean("trust_all_certs", value).apply()

    var pinnedCertSha256: String
        get() = preferences.getString("pinned_cert_sha256", "") ?: ""
        set(value) = putString("pinned_cert_sha256", value.trim())

    // --- 电池优化白名单引导（Doze 治本） ---

    var batteryOptimizationPromptDismissed: Boolean
        get() = preferences.getBoolean("battery_optimization_prompt_dismissed", false)
        set(value) = preferences.edit().putBoolean("battery_optimization_prompt_dismissed", value).apply()

    var batteryOptimizationPromptShownAt: Long
        get() = preferences.getLong("battery_optimization_prompt_shown_at", 0L)
        set(value) = preferences.edit().putLong("battery_optimization_prompt_shown_at", value).apply()

    var savePassword: Boolean
        get() = preferences.getBoolean("save_password", false)
        set(value) = preferences.edit().putBoolean("save_password", value).apply()

    // --- 敏感字段（Keystore AES-256-GCM，aks: 前缀，存量明文自动迁移） ---

    var token: String
        get() = getSecret("token")
        set(value) = putSecret("token", value)

    var tokenExpiresAtUtc: Long
        get() = preferences.getLong("token_expires_at_utc", 0L)
        set(value) = preferences.edit().putLong("token_expires_at_utc", value).apply()

    var derivedKeyBase64: String
        get() = getSecret("derived_key_b64")
        set(value) = putSecret("derived_key_b64", value)

    var savedEncryptedPassword: String
        get() = getSecret("saved_encrypted_password")
        set(value) = putSecret("saved_encrypted_password", value)

    // --- 会话参数（服务端下发） ---

    var maxTextBytes: Long
        get() = ClipConfig.sanitizeStoredClipboardLimit(
            preferences.getLong("max_text_bytes", ClipConfig.DEFAULT_MAX_TEXT_BYTES)
        )
        set(value) = preferences.edit()
            .putLong("max_text_bytes", ClipConfig.clampClipboardLimit(value))
            .apply()

    var helloTimeoutSeconds: Int
        get() = preferences.getInt("hello_timeout_seconds", ClipConfig.DEFAULT_HELLO_TIMEOUT_SECONDS)
        set(value) = preferences.edit().putInt("hello_timeout_seconds", value).apply()

    var heartbeatIntervalSeconds: Int
        get() = preferences.getInt("heartbeat_interval_seconds", ClipConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS)
        set(value) = preferences.edit().putInt("heartbeat_interval_seconds", value).apply()

    var heartbeatTimeoutSeconds: Int
        get() = preferences.getInt("heartbeat_timeout_seconds", ClipConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS)
        set(value) = preferences.edit().putInt("heartbeat_timeout_seconds", value).apply()

    var lastServerVersion: Long
        get() = preferences.getLong("last_server_version", 0L).coerceAtLeast(0L)
        set(value) = preferences.edit().putLong("last_server_version", value.coerceAtLeast(0L)).apply()

    /**
     * R1/R3 澄清后的低频安全会话标记。它只在登录、登出与会话失效事务中变化，
     * 不参与高频 UI/连接状态；Application 启动时用它初始化进程内 [RuntimeStateStore]。
     */
    var sessionActive: Boolean
        get() = preferences.getBoolean("session_active", false)
        private set(value) = preferences.edit().putBoolean("session_active", value).apply()

    fun setSessionActive(active: Boolean): Boolean =
        commitEditor(preferences.edit().putBoolean("session_active", active))

    // --- 客户端标识 ---

    fun clientId(): String {
        preferences.getString("client_id", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString("client_id", id).commit()
        return id
    }

    fun clientName(): String {
        preferences.getString("client_name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val name = (Build.MODEL ?: "Android").replace(" ", "")
        preferences.edit().putString("client_name", name).commit()
        return name
    }

    var onSecretDegradedListener: ((Boolean) -> Unit)? = null

    fun updateLoginSession(
        snapshot: SessionSnapshot,
        onSecurityDegraded: ((Boolean) -> Unit)? = null
    ): Boolean {
        var degraded = false
        fun encryptOrMark(value: String): String {
            val encrypted = encryptor(value)
            if (encrypted == null) degraded = true
            return encrypted ?: value
        }
        val editor = preferences.edit()
            .putBoolean("session_active", true)
            .putString("server_url", snapshot.serverUrl.trim().trimEnd('/'))
            .putString("token", encryptOrMark(snapshot.token))
            .putLong("token_expires_at_utc", snapshot.tokenExpiresAtUtc)
            .putLong("max_text_bytes", ClipConfig.clampClipboardLimit(snapshot.maxTextBytes))
            .putInt("hello_timeout_seconds", snapshot.helloTimeoutSeconds)
            .putInt("heartbeat_interval_seconds", snapshot.heartbeatIntervalSeconds)
            .putInt("heartbeat_timeout_seconds", snapshot.heartbeatTimeoutSeconds)

        when (snapshot.savedPassword) {
            null -> Unit
            "" -> editor.remove("saved_encrypted_password")
            else -> editor.putString(
                "saved_encrypted_password",
                encryptOrMark(snapshot.savedPassword)
            )
        }
        if (degraded) {
            onSecurityDegraded?.invoke(true)
        } else if (snapshot.savedPassword != null) {
            onSecurityDegraded?.invoke(false)
        }
        return commitEditor(editor)
    }

    /** 凭据清除与 `session_active=false` 必须同步确认成功，用于安全登出/失效路径。 */
    fun clearCredentials(): Boolean {
        return commitEditor(preferences.edit()
            .remove("token")
            .remove("token_expires_at_utc")
            .putBoolean("session_active", false)
        )
    }

    private fun putString(key: String, value: String) {
        commitEditor(preferences.edit().putString(key, value))
    }

    private fun getSecret(key: String, default: String = ""): String {
        val stored = preferences.getString(key, default) ?: default
        if (stored.isEmpty() || stored == default) return stored
        if (!EncryptedPrefs.isEncrypted(stored)) {
            runCatching { preferences.edit().putString(key, EncryptedPrefs.encrypt(stored)).apply() }
            return stored
        }
        return EncryptedPrefs.tryDecrypt(stored) ?: ""
    }

    private fun putSecret(key: String, value: String) {
        val encrypted = encryptor(value)
        if (encrypted != null) {
            preferences.edit().putString(key, encrypted).apply()
            onSecretDegradedListener?.invoke(false)
        } else {
            android.util.Log.w("AppPreferences", "Keystore unavailable, storing plaintext for $key")
            preferences.edit().putString(key, value).apply()
            onSecretDegradedListener?.invoke(true)
        }
    }
}



