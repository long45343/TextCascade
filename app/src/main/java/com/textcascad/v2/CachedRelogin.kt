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

sealed class CachedReloginResult {
    data class Success(val result: LoginResult) : CachedReloginResult()
    object AuthFailure : CachedReloginResult()
    data class RateLimited(val retryAfterSeconds: Long?) : CachedReloginResult()
    data class TransientFailure(val error: Throwable) : CachedReloginResult()
    object NoCredentials : CachedReloginResult()
}

/**
 * 用已保存的密码（Keystore 加密存储）执行静默重登并更新会话。
 */
class CachedReloginRunner(
    private val settings: SettingsStore,
    private val loginClient: LoginClient = HttpLoginClient(settings.trustAllCerts),
    private val isCurrent: () -> Boolean = { true }
) {
    fun execute(): CachedReloginResult {
        val serverUrl = settings.serverUrl
        val username = settings.username
        val savedPassword = settings.savedEncryptedPassword

        if (serverUrl.isBlank() || username.isBlank() || savedPassword.isBlank()) {
            return CachedReloginResult.NoCredentials
        }

        return try {
            if (!isCurrent()) return CachedReloginResult.TransientFailure(
                InterruptedException("Authentication task cancelled")
            )
            val result = loginClient.login(
                serverUrl = serverUrl,
                username = username,
                password = savedPassword
            )
            if (!isCurrent()) return CachedReloginResult.TransientFailure(
                InterruptedException("Authentication task cancelled")
            )
            val committed = settings.updateLoginSession(
                SessionSnapshot(
                    serverUrl = result.normalizedServerUrl,
                    token = result.token,
                    tokenExpiresAtUtc = result.tokenExpiresAtUtc,
                    maxTextBytes = result.maxTextBytes,
                    helloTimeoutSeconds = result.helloTimeoutSeconds,
                    heartbeatIntervalSeconds = result.heartbeatIntervalSeconds,
                    heartbeatTimeoutSeconds = result.heartbeatTimeoutSeconds
                )
            )
            if (!committed) {
                return CachedReloginResult.TransientFailure(
                    IllegalStateException("Unable to persist login session")
                )
            }
            CachedReloginResult.Success(result)
        } catch (e: LoginRejectedException) {
            CachedReloginResult.AuthFailure
        } catch (e: LoginRateLimitedException) {
            CachedReloginResult.RateLimited(e.retryAfterSeconds)
        } catch (e: Throwable) {
            CachedReloginResult.TransientFailure(e)
        }
    }
}
