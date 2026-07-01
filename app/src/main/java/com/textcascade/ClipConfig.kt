/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
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
    val localMaxClipboardBytes: Long
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
                localMaxClipboardBytes = store.localMaxClipboardBytes
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

    var passwordSha3: String
        get() = preferences.getString("password_sha3", "") ?: ""
        set(value) = putString("password_sha3", value)

    var hashedPasswordBase64: String
        get() = preferences.getString("hashed_password_base64", "") ?: ""
        set(value) = putString("hashed_password_base64", value)

    var csrfToken: String
        get() = preferences.getString("csrf_token", "") ?: ""
        set(value) = putString("csrf_token", value)

    var cookieHeader: String
        get() = preferences.getString("cookie_header", "") ?: ""
        set(value) = putString("cookie_header", value)

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

    var localMaxClipboardBytes: Long
        get() = preferences.getLong("local_max_clipboard_bytes", ClipConfig.DEFAULT_MAX_SIZE_BYTES)
        set(value) = preferences.edit().putLong("local_max_clipboard_bytes", value).apply()

    var savePassword: Boolean
        get() = preferences.getBoolean("save_password", false)
        set(value) = preferences.edit().putBoolean("save_password", value).apply()

    var savedPasswordHash: String
       get() = preferences.getString("saved_password_hash", "") ?: ""
        set(value) = putString("saved_password_hash", value)

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
}
