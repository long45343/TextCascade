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

/**
 * SessionRefresher 是登录、协议检查与会话事务提交的唯一共享函数，
 * 全链路直接返回统一 [AuthResult]（Spec A2）。
 */
internal class SessionRefresher(
    private val settings: SettingsStore,
    private val deriveKeyBase64: (password: String) -> String,
    /** 认证拒绝/持久化失败后的安全失效路径，默认等价于 AuthManager.commitInvalidSession 语义。 */
    private val markSessionInvalid: () -> Boolean = {
        if (settings.appPreferences.setSessionActive(false)) {
            settings.markSessionInvalid(sessionActiveCommitted = true)
        } else {
            false
        }
    }
) {

    /**
     * 执行完整会话刷新。成功路径写入会话字段并恢复/写入 derivedKeyBase64；
     * 拒绝/协议不支持/持久化失败路径在同一凭据事务内提交 `session_active=false`。
     */
    fun refresh(
        loginClient: LoginClient,
        password: String,
        savedPassword: String? = null
    ): AuthResult {
        return try {
            val derivedKeyBase64 = deriveKeyBase64(password)
            val result = loginClient.login(
                serverUrl = settings.serverUrl,
                username = settings.username,
                password = password
            )
            if (result.protocolVersion > Protocol.SUPPORTED_PROTOCOL_VERSION) {
                markSessionInvalid()
                return AuthResult.ProtocolUnsupported(result.protocolVersion)
            }
            val committed = settings.updateLoginSession(
                SessionSnapshot(
                    serverUrl = result.normalizedServerUrl,
                    token = result.token,
                    tokenExpiresAtUtc = result.tokenExpiresAtUtc,
                    maxTextBytes = result.maxTextBytes,
                    helloTimeoutSeconds = result.helloTimeoutSeconds,
                    heartbeatIntervalSeconds = result.heartbeatIntervalSeconds,
                    heartbeatTimeoutSeconds = result.heartbeatTimeoutSeconds,
                    savedPassword = savedPassword
                )
            )
            if (!committed) {
                return AuthResult.PersistenceFailure(
                    error = IllegalStateException("Unable to persist login session"),
                    invalidationPersisted = runCatching { markSessionInvalid() }.getOrDefault(false)
                )
            }
            if (derivedKeyBase64.isNotBlank()) {
                settings.derivedKeyBase64 = derivedKeyBase64
            }
            AuthResult.Success(result)
        } catch (error: LoginRateLimitedException) {
            markSessionInvalid()
            AuthResult.RateLimited(error.retryAfterSeconds)
        } catch (error: LoginApiException) {
            AuthResult.AuthRejected(
                error = error,
                invalidationPersisted = runCatching { markSessionInvalid() }.getOrDefault(false)
            )
        } catch (error: Throwable) {
            AuthResult.Failed(error)
        }
    }
}
