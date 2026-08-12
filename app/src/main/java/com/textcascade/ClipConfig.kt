/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
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

package com.textcascade

import android.content.Context
import java.net.URI

data class ClipConfig(
    val serverUrl: String,
    val websocketUrl: String,
    val username: String,
    val passwordSha3: String,
    val hashedPasswordBase64: String,
    val csrfToken: String,
    val cookieHeader: String,
    val maxSizeBytes: Long,
    val hashRounds: Int,
    val salt: String,
    val cipherEnabled: Boolean,
    val relaunchOnBoot: Boolean,
    val websocketStatusNotification: Boolean,
    val localMaxClipboardBytes: Long,
    val trustAllCerts: Boolean
) {
    companion object {
        const val DEFAULT_HASH_ROUNDS = 664937
        const val DEFAULT_MAX_SIZE_BYTES = 512000L

        fun default(context: Context): ClipConfig {
            val store = SettingsStore(context)
            return ClipConfig(
                serverUrl = store.serverUrl,
                websocketUrl = store.websocketUrl,
                username = store.username,
                passwordSha3 = store.passwordSha3,
                hashedPasswordBase64 = store.hashedPasswordBase64,
                csrfToken = store.csrfToken,
                cookieHeader = store.cookieHeader,
                maxSizeBytes = store.maxSizeBytes,
                hashRounds = store.hashRounds,
                salt = store.salt,
                cipherEnabled = store.cipherEnabled,
                relaunchOnBoot = store.relaunchOnBoot,
                websocketStatusNotification = store.websocketStatusNotification,
                localMaxClipboardBytes = store.localMaxClipboardBytes,
                trustAllCerts = store.trustAllCerts
            )
        }

        fun websocketUrlFromServerUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            val uri = URI(trimmed)
            val scheme = when (uri.scheme?.lowercase()) {
                "http" -> "ws"
                "https" -> "wss"
                else -> error("Unsupported server URL scheme: ${uri.scheme}")
            }
            return URI(
                scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                "${uri.path.orEmpty().trimEnd('/')}/clipsocket",
                uri.query,
                uri.fragment
            ).toString()
        }
    }
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("textcascade", Context.MODE_PRIVATE)

    val sharedPreferences: android.content.SharedPreferences get() = preferences

    var serverUrl: String
        get() = preferences.getString("server_url", "http://localhost:8080") ?: "http://localhost:8080"
        set(value) = putString("server_url", value.trim().trimEnd('/'))

    var websocketUrl: String
        get() = preferences.getString("websocket_url", "") ?: ""
        set(value) = putString("websocket_url", value)

    var username: String
        get() = preferences.getString("username", "") ?: ""
        set(value) = putString("username", value.trim())

    // --- R1: 敏感字段通过 Keystore 加密存储 ---
    var passwordSha3: String
        get() = getSecret("password_sha3")
        set(value) = putSecret("password_sha3", value)

    var hashedPasswordBase64: String
        get() = getSecret("hashed_password_base64")
        set(value) = putSecret("hashed_password_base64", value)

    var csrfToken: String
        get() = getSecret("csrf_token")
        set(value) = putSecret("csrf_token", value)

    var cookieHeader: String
        get() = getSecret("cookie_header")
        set(value) = putSecret("cookie_header", value)

    var maxSizeBytes: Long
        get() = preferences.getLong("max_size_bytes", ClipConfig.DEFAULT_MAX_SIZE_BYTES)
        set(value) = preferences.edit().putLong("max_size_bytes", value).apply()

    var hashRounds: Int
        get() = preferences.getInt("hash_rounds", ClipConfig.DEFAULT_HASH_ROUNDS)
        set(value) = preferences.edit().putInt("hash_rounds", value).apply()

    var salt: String
        get() = preferences.getString("salt", "") ?: ""
        set(value) = putString("salt", value)

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

    var localMaxClipboardBytes: Long
        get() = preferences.getLong("local_max_clipboard_bytes", ClipConfig.DEFAULT_MAX_SIZE_BYTES)
        set(value) = preferences.edit().putLong("local_max_clipboard_bytes", value).apply()

    var savePassword: Boolean
        get() = preferences.getBoolean("save_password", false)
        set(value) = preferences.edit().putBoolean("save_password", value).apply()

    var savedPasswordHash: String
        get() = getSecret("saved_password_hash")
        set(value) = putSecret("saved_password_hash", value)

    /**
     * K1: Keystore 加密后的原始明文密码。
     */
    var savedEncryptedPassword: String
        get() = getSecret("saved_encrypted_password")
        set(value) = putSecret("saved_encrypted_password", value)

    /**
     * K4: 检测是否存在旧版 SHA3 哈希且无新版加密密码。
     */
    fun needsPasswordMigration(): Boolean {
        val oldHash = preferences.getString("saved_password_hash", "") ?: ""
        val newPwd = preferences.getString("saved_encrypted_password", "") ?: ""
        return oldHash.isNotBlank() && newPwd.isBlank()
    }

    /**
     * K4: 清除旧版 SHA3 哈希（迁移完成后调用）。
     */
    fun clearLegacyPasswordHash() {
        preferences.edit().remove("saved_password_hash").apply()
    }

    /**
     * D1/K7: 标记是否有保存的密码因 Keystore 解密失败而被清除。
     * 由 getSecret() 在解密失败时设置，由 UI 消费后清除。
     */
    var passwordDecryptionFailed: Boolean
        get() = preferences.getBoolean("password_decryption_failed", false)
        set(value) = preferences.edit().putBoolean("password_decryption_failed", value).apply()

    /**
     * D1/K7: 检查并消费解密失败标记。
     * 返回 true 表示曾发生解密失败（调用方应提示用户），然后自动清除标记。
     */
    fun consumePasswordDecryptionFailure(): Boolean {
        val failed = passwordDecryptionFailed
        if (failed) passwordDecryptionFailed = false
        return failed
    }
    var serviceRunning: Boolean
        get() = preferences.getBoolean("service_running", false)
        set(value) = preferences.edit().putBoolean("service_running", value).apply()

    var statusMessage: String
        get() = preferences.getString("status_message", "") ?: ""
        set(value) = putString("status_message", value)

    fun clearSession() {
        preferences.edit()
            .remove("websocket_url")
            .remove("password_sha3")
            .remove("hashed_password_base64")
            .remove("csrf_token")
            .remove("cookie_header")
            .putBoolean("service_running", false)
            .putString("status_message", "")
            .apply()
    }

    private fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    // --- R1: 敏感字段加解密 ---
    // 存量明文在读取时自动迁移为加密格式；解密失败则清空返回空串
    private fun getSecret(key: String, default: String = ""): String {
        val stored = preferences.getString(key, default) ?: default
        if (stored.isEmpty() || stored == default) return stored
        if (!EncryptedPrefs.isEncrypted(stored)) {
            // 存量明文：立即迁移为加密格式，返回明文
            runCatching { preferences.edit().putString(key, EncryptedPrefs.encrypt(stored)).apply() }
            return stored
        }
        return EncryptedPrefs.tryDecrypt(stored) ?: run {
            // 解密失败（Keystore 被清除等）：清空字段
            preferences.edit().remove(key).apply()
            // D2/K7: 仅对密码字段设置失败标记，供 UI 提示用户
            if (key == "saved_encrypted_password") {
                passwordDecryptionFailed = true
            }
            ""
        }
    }

    private fun putSecret(key: String, value: String) {
        val encrypted = EncryptedPrefs.tryEncrypt(value)
        if (encrypted != null) {
            preferences.edit().putString(key, encrypted).apply()
        } else {
            // Keystore 不可用：回退到明文存储
            android.util.Log.w("SettingsStore", "Keystore unavailable, storing plaintext for $key")
            preferences.edit().putString(key, value).apply()
        }
    }
}
