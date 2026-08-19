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
 * R2: 共享会话刷新器——首次登录与静默重登的唯一定义点。
 * 封装「login + 派生密钥 + protocolVersion 检查 + 会话提交」，
 * 两条路径对同一 LoginResult 产生完全一致的会话提交与派生结果。
 */
sealed class SessionRefreshOutcome {
    data class Success(val result: LoginResult) : SessionRefreshOutcome()

    /** 服务端 protocolVersion 高于客户端支持：拒绝并提示升级，不隐式建连。 */
    data class ProtocolUnsupported(val serverVersion: Int) : SessionRefreshOutcome()

    data class Rejected(val error: LoginApiException) : SessionRefreshOutcome()
    data class RateLimited(val error: LoginRateLimitedException) : SessionRefreshOutcome()
    object PersistenceFailed : SessionRefreshOutcome()
    data class Failed(val error: Throwable) : SessionRefreshOutcome()
}

class SessionRefresher(
    private val settings: SettingsStore,
    private val deriveKeyBase64: (password: String) -> String
) {
    /**
     * 执行完整会话刷新。成功路径写入会话字段并恢复/写入 derivedKeyBase64。
     * [savedPassword]：null 不触碰已存密码；"" 清除；非空保存。
     */
    fun refresh(
        loginClient: LoginClient,
        password: String,
        savedPassword: String? = null
    ): SessionRefreshOutcome {
        return try {
            val derivedKeyBase64 = deriveKeyBase64(password)
            val result = loginClient.login(
                serverUrl = settings.serverUrl,
                username = settings.username,
                password = password
            )
            if (result.protocolVersion > Protocol.SUPPORTED_PROTOCOL_VERSION) {
                return SessionRefreshOutcome.ProtocolUnsupported(result.protocolVersion)
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
                return SessionRefreshOutcome.PersistenceFailed
            }
            if (derivedKeyBase64.isNotBlank()) {
                settings.derivedKeyBase64 = derivedKeyBase64
            }
            SessionRefreshOutcome.Success(result)
        } catch (error: LoginRateLimitedException) {
            SessionRefreshOutcome.RateLimited(error)
        } catch (error: LoginApiException) {
            SessionRefreshOutcome.Rejected(error)
        } catch (error: Throwable) {
            SessionRefreshOutcome.Failed(error)
        }
    }
}
