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
 * R2: 收敛到 SessionRefresher，与首次登录产生一致的会话提交与派生密钥写入。
 */
class CachedReloginRunner(
    private val settings: SettingsStore,
    private val loginClient: LoginClient = HttpLoginClient(settings.trustAllCerts, settings.pinnedCertSha256),
    private val isCurrent: () -> Boolean = { true },
    private val deriveKeyBase64: (password: String) -> String =
        { password -> deriveCredentials(settings, password).derivedKeyBase64 }
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
            val refresher = SessionRefresher(
                settings = settings,
                deriveKeyBase64 = deriveKeyBase64
            )
            val outcome = refresher.refresh(
                loginClient = loginClient,
                password = savedPassword,
                savedPassword = null
            )
            if (!isCurrent()) return CachedReloginResult.TransientFailure(
                InterruptedException("Authentication task cancelled")
            )
            when (outcome) {
                is SessionRefreshOutcome.Success -> CachedReloginResult.Success(outcome.result)
                is SessionRefreshOutcome.ProtocolUnsupported -> CachedReloginResult.TransientFailure(
                    IllegalStateException(
                        "Server protocol version v${outcome.serverVersion} is newer than supported " +
                            "v${Protocol.SUPPORTED_PROTOCOL_VERSION}; app update required"
                    )
                )
                is SessionRefreshOutcome.Rejected ->
                    if (outcome.error is LoginRejectedException) {
                        CachedReloginResult.AuthFailure
                    } else {
                        CachedReloginResult.TransientFailure(outcome.error)
                    }
                is SessionRefreshOutcome.RateLimited ->
                    CachedReloginResult.RateLimited(outcome.error.retryAfterSeconds)
                SessionRefreshOutcome.PersistenceFailed -> CachedReloginResult.TransientFailure(
                    IllegalStateException("Unable to persist login session")
                )
                is SessionRefreshOutcome.Failed -> CachedReloginResult.TransientFailure(outcome.error)
            }
        } catch (e: LoginRateLimitedException) {
            CachedReloginResult.RateLimited(e.retryAfterSeconds)
        } catch (e: LoginRejectedException) {
            CachedReloginResult.AuthFailure
        } catch (e: Throwable) {
            CachedReloginResult.TransientFailure(e)
        }
    }
}

