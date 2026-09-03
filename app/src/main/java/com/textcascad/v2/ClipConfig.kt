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
import android.content.SharedPreferences
import java.net.URI

data class ClipConfig(
    val session: ServerSession,
    val userPrefs: UserPrefs,
    val cryptoMaterial: CryptoMaterial
) {
    val websocketUrl: String get() = websocketUrlFromServerUrl(session.serverUrl)

    companion object {
        const val MIN_HASH_ROUNDS = 1
        const val MAX_HASH_ROUNDS = 5_000_000
        const val DEFAULT_HASH_ROUNDS = 664937
        const val MIN_CLIPBOARD_BYTES = 1L
        const val DEFAULT_SERVER_URL = "https://your-server:8443"
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
            val serverSession = ServerSession(
                serverUrl = store.serverUrl,
                username = store.username,
                token = store.token,
                tokenExpiresAtUtc = store.tokenExpiresAtUtc,
                clientId = store.clientId(),
                clientName = store.clientName()
            )
            val cryptoMaterial = CryptoMaterial(
                derivedKeyBase64 = store.derivedKeyBase64,
                hashRounds = store.hashRounds,
                salt = store.salt,
                cipherEnabled = store.cipherEnabled,
                trustAllCerts = store.trustAllCerts,
                pinnedCertSha256 = store.pinnedCertSha256
            )
            val userPrefs = UserPrefs(
                maxTextBytes = store.maxTextBytes,
                helloTimeoutSeconds = store.helloTimeoutSeconds,
                heartbeatIntervalSeconds = store.heartbeatIntervalSeconds,
                heartbeatTimeoutSeconds = store.heartbeatTimeoutSeconds,
                lastServerVersion = store.lastServerVersion,
                relaunchOnBoot = store.relaunchOnBoot,
                websocketStatusNotification = store.websocketStatusNotification,
                localMaxClipboardBytes = store.localMaxClipboardBytes
            )
            return ClipConfig(
                session = serverSession,
                userPrefs = userPrefs,
                cryptoMaterial = cryptoMaterial
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

data class ServerSession(
    val serverUrl: String,
    val username: String,
    val token: String,
    val tokenExpiresAtUtc: Long,
    val clientId: String,
    val clientName: String
)

data class UserPrefs(
    val maxTextBytes: Long,
    val helloTimeoutSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    val heartbeatTimeoutSeconds: Int,
    val lastServerVersion: Long,
    val relaunchOnBoot: Boolean,
    val websocketStatusNotification: Boolean,
    val localMaxClipboardBytes: Long
)

data class CryptoMaterial(
    val derivedKeyBase64: String,
    val hashRounds: Int,
    val salt: String,
    val cipherEnabled: Boolean,
    val trustAllCerts: Boolean,
    val pinnedCertSha256: String = ""
)

/**
 * 设置存储门面 (Facade)：
 * 组合代理 [AppPreferences]（低频持久化与凭据）与共享 [RuntimeStateStore]
 * （进程内运行时状态），保持对所有现有调用点与单测用例的兼容。
 */
class SettingsStore(
    context: Context,
    commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    encryptor: (String) -> String? = EncryptedPrefs::tryEncrypt
) {
    val appPreferences = AppPreferences(context, commitEditor, encryptor)
    val runtimeState = RuntimeStateStoreHolder.forContext(context.applicationContext)

    init {
        appPreferences.onSecretDegradedListener = { degraded ->
            runtimeState.securityDegraded = degraded
        }
    }

    val sharedPreferences: SharedPreferences get() = appPreferences.sharedPreferences

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun registerRuntimeListener(listener: RuntimeStateStore.Listener) {
        runtimeState.registerListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterRuntimeListener(listener: RuntimeStateStore.Listener) {
        runtimeState.unregisterListener(listener)
    }

    var serverUrl: String
        get() = appPreferences.serverUrl
        set(value) { appPreferences.serverUrl = value }

    var username: String
        get() = appPreferences.username
        set(value) { appPreferences.username = value }

    var hashRounds: Int
        get() = appPreferences.hashRounds
        set(value) { appPreferences.hashRounds = value }

    var salt: String
        get() = appPreferences.salt
        set(value) { appPreferences.salt = value }

    var localMaxClipboardBytes: Long
        get() = appPreferences.localMaxClipboardBytes
        set(value) { appPreferences.localMaxClipboardBytes = value }

    var cipherEnabled: Boolean
        get() = appPreferences.cipherEnabled
        set(value) { appPreferences.cipherEnabled = value }

    var relaunchOnBoot: Boolean
        get() = appPreferences.relaunchOnBoot
        set(value) { appPreferences.relaunchOnBoot = value }

    var websocketStatusNotification: Boolean
        get() = appPreferences.websocketStatusNotification
        set(value) { appPreferences.websocketStatusNotification = value }

    var trustAllCerts: Boolean
        get() = appPreferences.trustAllCerts
        set(value) { appPreferences.trustAllCerts = value }

    var pinnedCertSha256: String
        get() = appPreferences.pinnedCertSha256
        set(value) { appPreferences.pinnedCertSha256 = value.trim() }

    var batteryOptimizationPromptDismissed: Boolean
        get() = appPreferences.batteryOptimizationPromptDismissed
        set(value) { appPreferences.batteryOptimizationPromptDismissed = value }

    var batteryOptimizationPromptShownAt: Long
        get() = appPreferences.batteryOptimizationPromptShownAt
        set(value) { appPreferences.batteryOptimizationPromptShownAt = value }

    var savePassword: Boolean
        get() = appPreferences.savePassword
        set(value) { appPreferences.savePassword = value }

    var token: String
        get() = appPreferences.token
        set(value) { appPreferences.token = value }

    var tokenExpiresAtUtc: Long
        get() = appPreferences.tokenExpiresAtUtc
        set(value) { appPreferences.tokenExpiresAtUtc = value }

    var derivedKeyBase64: String
        get() = appPreferences.derivedKeyBase64
        set(value) { appPreferences.derivedKeyBase64 = value }

    var savedEncryptedPassword: String
        get() = appPreferences.savedEncryptedPassword
        set(value) { appPreferences.savedEncryptedPassword = value }

    var maxTextBytes: Long
        get() = appPreferences.maxTextBytes
        set(value) { appPreferences.maxTextBytes = value }

    var helloTimeoutSeconds: Int
        get() = appPreferences.helloTimeoutSeconds
        set(value) { appPreferences.helloTimeoutSeconds = value }

    var heartbeatIntervalSeconds: Int
        get() = appPreferences.heartbeatIntervalSeconds
        set(value) { appPreferences.heartbeatIntervalSeconds = value }

    var heartbeatTimeoutSeconds: Int
        get() = appPreferences.heartbeatTimeoutSeconds
        set(value) { appPreferences.heartbeatTimeoutSeconds = value }

    var lastServerVersion: Long
        get() = appPreferences.lastServerVersion
        set(value) { appPreferences.lastServerVersion = value }

    fun clientId(): String = appPreferences.clientId()

    fun clientName(): String = appPreferences.clientName()

    var hasSession: Boolean
        get() = runtimeState.hasSession
        private set(value) { runtimeState.hasSession = value }

    var serviceRunning: Boolean
        get() = runtimeState.serviceRunning
        set(value) { runtimeState.serviceRunning = value }

    var statusMessage: String
        get() = runtimeState.statusMessage
        set(value) { runtimeState.statusMessage = value }

    var connectionStatusMessage: String
        get() = runtimeState.connectionStatusMessage
        set(value) { runtimeState.connectionStatusMessage = value }

    var backgroundStatus: String
        get() = runtimeState.backgroundStatus
        set(value) { runtimeState.backgroundStatus = value }

    var passwordDecryptionFailed: Boolean
        get() = runtimeState.passwordDecryptionFailed
        set(value) { runtimeState.passwordDecryptionFailed = value }

    fun consumePasswordDecryptionFailure(): Boolean = runtimeState.consumePasswordDecryptionFailure()

    var securityDegraded: Boolean
        get() = runtimeState.securityDegraded
        set(value) { runtimeState.securityDegraded = value }

    /** 同步清除持久凭据；成功后更新内存运行时状态。 */
    fun clearSession(): Boolean {
        if (!appPreferences.clearCredentials()) return false
        if (!appPreferences.setSessionActive(false)) return false
        hasSession = false
        serviceRunning = false
        return true
    }

    /**
     * 安全关键流程的持久化语义：`session_active` 由认证核心在拒绝/持久化失败事务中先提交，
     * 提交成功后才允许通过本函数更新内存会话状态。
     */
    fun markSessionInvalid(sessionActiveCommitted: Boolean): Boolean {
        if (!sessionActiveCommitted) return false
        hasSession = false
        serviceRunning = false
        return true
    }

    fun updateLoginSession(snapshot: SessionSnapshot): Boolean {
        val persisted = appPreferences.updateLoginSession(snapshot) { degraded ->
            securityDegraded = degraded
        }
        if (persisted) hasSession = true
        return persisted
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


