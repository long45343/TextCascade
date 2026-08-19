/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascad.v2

import android.content.Context
import android.os.Build
import java.net.URI
import java.util.UUID

data class ClipConfig(
    val serverUrl: String,
    val websocketUrl: String,
    val username: String,
    val token: String,
    val tokenExpiresAtUtc: Long,
    val clientId: String,
    val clientName: String,
    val derivedKeyBase64: String,
    val maxTextBytes: Long,
    val helloTimeoutSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    val heartbeatTimeoutSeconds: Int,
    val lastServerVersion: Long,
    val hashRounds: Int,
    val salt: String,
    val cipherEnabled: Boolean,
    val relaunchOnBoot: Boolean,
    val websocketStatusNotification: Boolean,
    val localMaxClipboardBytes: Long,
    val trustAllCerts: Boolean
) {
    companion object {
        const val MIN_HASH_ROUNDS = 1
        const val MAX_HASH_ROUNDS = 5_000_000
        const val DEFAULT_HASH_ROUNDS = 664937
        const val MIN_CLIPBOARD_BYTES = 1L
        const val DEFAULT_SERVER_URL = "https://localhosts:8443"
        const val DEFAULT_MAX_TEXT_BYTES = 512_000L
        const val MAX_CLIPBOARD_BYTES = 2L * 1024L * 1024L
        const val MAX_TRANSPORT_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_HELLO_TIMEOUT_SECONDS = 10
        const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 20
        const val DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 60
        const val TOKEN_EXPIRY_SAFETY_MS = 60_000L

        fun clampClipboardLimit(value: Long): Long =
            value.coerceIn(MIN_CLIPBOARD_BYTES, MAX_CLIPBOARD_BYTES)

        fun sanitizeStoredClipboardLimit(value: Long): Long = when {
            value < MIN_CLIPBOARD_BYTES -> DEFAULT_MAX_TEXT_BYTES
            value > MAX_CLIPBOARD_BYTES -> MAX_CLIPBOARD_BYTES
            else -> value
        }

        fun default(context: Context): ClipConfig {
            val store = SettingsStore(context)
            return ClipConfig(
                serverUrl = store.serverUrl,
                websocketUrl = websocketUrlFromServerUrl(store.serverUrl),
                username = store.username,
                token = store.token,
                tokenExpiresAtUtc = store.tokenExpiresAtUtc,
                clientId = store.clientId(),
                clientName = store.clientName(),
                derivedKeyBase64 = store.derivedKeyBase64,
                maxTextBytes = store.maxTextBytes,
                helloTimeoutSeconds = store.helloTimeoutSeconds,
                heartbeatIntervalSeconds = store.heartbeatIntervalSeconds,
                heartbeatTimeoutSeconds = store.heartbeatTimeoutSeconds,
                lastServerVersion = store.lastServerVersion,
                hashRounds = store.hashRounds,
                salt = store.salt,
                cipherEnabled = store.cipherEnabled,
                relaunchOnBoot = store.relaunchOnBoot,
                websocketStatusNotification = store.websocketStatusNotification,
                localMaxClipboardBytes = store.localMaxClipboardBytes,
                trustAllCerts = store.trustAllCerts
            )
        }

        /** 由 https://host:port 派生 wss://{host}/api/v1/sync；仅支持 https。 */
        fun websocketUrlFromServerUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https") { "Only HTTPS server URLs are supported: ${uri.scheme}" }
            require(!uri.host.isNullOrBlank()) { "Server URL has no host" }
            return URI(
                "wss",
                uri.userInfo,
                uri.host,
                uri.port,
                Protocol.SYNC_PATH,
                null,
                null
            ).toString()
        }
    }
}

class SettingsStore(
    context: Context,
    private val commitEditor: (android.content.SharedPreferences.Editor) -> Boolean = { it.commit() },
    private val encryptor: (String) -> String? = EncryptedPrefs::tryEncrypt
) {
    private val preferences = context.getSharedPreferences("textcascade", Context.MODE_PRIVATE)

    val sharedPreferences: android.content.SharedPreferences get() = preferences

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

    /** 无符号语义；初始 0 表示未知/从未收到服务端版本。 */
    var lastServerVersion: Long
        get() = preferences.getLong("last_server_version", 0L).coerceAtLeast(0L)
        set(value) = preferences.edit().putLong("last_server_version", value.coerceAtLeast(0L)).apply()

    // --- 客户端标识 ---

    /** UUID v4，首次调用时生成并持久化。 */
    fun clientId(): String {
        preferences.getString("client_id", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString("client_id", id).commit()
        return id
    }

    /** Build.MODEL 去空格；首次调用时持久化。 */
    fun clientName(): String {
        preferences.getString("client_name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val name = (Build.MODEL ?: "Android").replace(" ", "")
        preferences.edit().putString("client_name", name).commit()
        return name
    }

    // --- 会话生命周期 ---

    var hasSession: Boolean
        get() = preferences.getBoolean("has_session", false)
        set(value) = preferences.edit().putBoolean("has_session", value).apply()

    var serviceRunning: Boolean
        get() = preferences.getBoolean("service_running", false)
        set(value) = preferences.edit().putBoolean("service_running", value).apply()

    var statusMessage: String
        get() = preferences.getString("status_message", "") ?: ""
        set(value) = putString("status_message", value)

    /**
     * D1/K7: 保存的密码因 Keystore 解密失败标记（UI 消费后清除）。
     */
    var passwordDecryptionFailed: Boolean
        get() = preferences.getBoolean("password_decryption_failed", false)
        set(value) = preferences.edit().putBoolean("password_decryption_failed", value).apply()

    fun consumePasswordDecryptionFailure(): Boolean {
        val failed = passwordDecryptionFailed
        if (failed) passwordDecryptionFailed = false
        return failed
    }

    /**
     * R3: Keystore 加密不可用导致敏感字段明文落盘的持久化降级标志。
     * UI 消费显示警示；Keystore 恢复正常写入成功时清除。
     */
    var securityDegraded: Boolean
        get() = preferences.getBoolean("security_degraded", false)
        set(value) = preferences.edit().putBoolean("security_degraded", value).apply()

    fun clearSession(): Boolean {
        return commitEditor(preferences.edit()
            .remove("token")
            .remove("token_expires_at_utc")
            .putBoolean("has_session", false)
            .putBoolean("service_running", false)
            .putString("status_message", "")
            .remove("security_degraded")
        )
    }

    fun markSessionInvalid(): Boolean {
        return commitEditor(
            preferences.edit()
                .putBoolean("has_session", false)
                .putBoolean("service_running", false)
        )
    }

    fun updateLoginSession(snapshot: SessionSnapshot): Boolean {
        var degraded = false
        fun encryptOrMark(value: String): String {
            val encrypted = encryptor(value)
            if (encrypted == null) degraded = true
            return encrypted ?: value
        }
        val editor = preferences.edit()
            .putString("server_url", snapshot.serverUrl.trim().trimEnd('/'))
            .putString("token", encryptOrMark(snapshot.token))
            .putLong("token_expires_at_utc", snapshot.tokenExpiresAtUtc)
            .putLong("max_text_bytes", ClipConfig.clampClipboardLimit(snapshot.maxTextBytes))
            .putInt("hello_timeout_seconds", snapshot.helloTimeoutSeconds)
            .putInt("heartbeat_interval_seconds", snapshot.heartbeatIntervalSeconds)
            .putInt("heartbeat_timeout_seconds", snapshot.heartbeatTimeoutSeconds)
            .putBoolean("has_session", true)

        when (snapshot.savedPassword) {
            null -> Unit
            "" -> editor.remove("saved_encrypted_password")
            else -> editor.putString(
                "saved_encrypted_password",
                encryptOrMark(snapshot.savedPassword)
            )
        }
        // R3: 任一敏感字段走明文降级则在本次提交一并置位降级标志；
        // 完整会话提交（token + 密码字段均覆盖）且全部加密成功时清除标志。
        if (degraded) {
            editor.putBoolean("security_degraded", true)
        } else if (snapshot.savedPassword != null) {
            editor.putBoolean("security_degraded", false)
        }
        return commitEditor(editor)
    }

    private fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    // --- 敏感字段加解密：存量明文读取时自动迁移；解密失败不清空只提示 ---

    private fun getSecret(key: String, default: String = ""): String {
        val stored = preferences.getString(key, default) ?: default
        if (stored.isEmpty() || stored == default) return stored
        if (!EncryptedPrefs.isEncrypted(stored)) {
            runCatching { preferences.edit().putString(key, EncryptedPrefs.encrypt(stored)).apply() }
            return stored
        }
        return EncryptedPrefs.tryDecrypt(stored) ?: run {
            if (key == "saved_encrypted_password") {
                passwordDecryptionFailed = true
            }
            ""
        }
    }

    private fun putSecret(key: String, value: String) {
        val encrypted = encryptor(value)
        if (encrypted != null) {
            preferences.edit().putString(key, encrypted).apply()
        } else {
            // R3: 降级到明文落盘时置位持久化降级标志，UI 显示警示
            android.util.Log.w("SettingsStore", "Keystore unavailable, storing plaintext for $key")
            preferences.edit().putString(key, value).putBoolean("security_degraded", true).apply()
        }
    }
}

data class SessionSnapshot(
    val serverUrl: String,
    val token: String,
    val tokenExpiresAtUtc: Long,
    val maxTextBytes: Long,
    val helloTimeoutSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    val heartbeatTimeoutSeconds: Int,
    val savedPassword: String? = null
)
