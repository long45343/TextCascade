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
import android.util.Base64

internal data class DerivedCredentials(
    val derivedKeyBase64: String
)

internal sealed class AuthenticationOutcome {
    data class Success(val result: LoginResult) : AuthenticationOutcome()
    object Cancelled : AuthenticationOutcome()
    object MissingPassword : AuthenticationOutcome()

    /** 服务端 protocolVersion 高于客户端支持：不建连、提示升级。 */
    data class ProtocolUnsupported(val serverVersion: Int) : AuthenticationOutcome()
    data class Rejected(
        val error: LoginApiException,
        val invalidationPersisted: Boolean
    ) : AuthenticationOutcome()
    data class PersistenceFailure(
        val error: Throwable,
        val invalidationPersisted: Boolean
    ) : AuthenticationOutcome()
    data class Failed(val error: Throwable) : AuthenticationOutcome()
}

internal class AuthenticationDependencies(
    val settingsStoreFactory: (Context) -> SettingsStore = { SettingsStore(it) },
    val loginClientFactory: (Boolean) -> LoginClient = { HttpLoginClient(it) },
    val startService: (Context) -> Unit = { ClipServiceController.start(it) },
    val stopService: (Context) -> Unit = { ClipServiceController.stop(it) },
    val restartService: (ClipForegroundService) -> Unit = { it.restartSelfForFreshConfig() }
)

internal fun deriveCredentials(settings: SettingsStore, password: String): DerivedCredentials {
    return DerivedCredentials(
        derivedKeyBase64 = if (settings.cipherEnabled) {
            Base64.encodeToString(
                CryptoManager.derivePasswordKey(
                    settings.username,
                    password,
                    settings.salt,
                    settings.hashRounds
                ),
                Base64.NO_WRAP
            )
        } else {
            ""
        }
    )
}

internal class AuthenticationWorkflow(
    private val settings: SettingsStore,
    private val loginClientFactory: (Boolean) -> LoginClient,
    private val deriveCredentials: (password: String, savedPasswordUsed: Boolean) -> DerivedCredentials,
    private val startService: (LoginResult) -> Boolean,
    private val setStatus: (String) -> Unit,
    private val isOwnerAlive: () -> Boolean
) {
    fun execute(
        password: String,
        savedPasswordUsed: Boolean,
        savedPassword: String? = null
    ): AuthenticationOutcome {
        if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled
        if (password.isBlank()) {
            setStatus("missing_password")
            return AuthenticationOutcome.MissingPassword
        }

        return try {
            val refresher = SessionRefresher(
                settings = settings,
                deriveKeyBase64 = { password -> deriveCredentials(password, savedPasswordUsed).derivedKeyBase64 }
            )
            val outcome = refresher.refresh(
                loginClient = loginClientFactory(settings.trustAllCerts),
                password = password,
                savedPassword = savedPassword
            )
            if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled

            when (outcome) {
                is SessionRefreshOutcome.Success -> {
                    if (!startService(outcome.result)) {
                        return AuthenticationOutcome.Cancelled
                    }
                    AuthenticationOutcome.Success(outcome.result)
                }
                is SessionRefreshOutcome.ProtocolUnsupported ->
                    AuthenticationOutcome.ProtocolUnsupported(outcome.serverVersion)
                SessionRefreshOutcome.PersistenceFailed -> AuthenticationOutcome.PersistenceFailure(
                    error = IllegalStateException("Unable to persist login session"),
                    invalidationPersisted = settings.markSessionInvalid()
                )
                is SessionRefreshOutcome.Rejected ->
                    AuthenticationOutcome.Rejected(outcome.error, settings.markSessionInvalid())
                is SessionRefreshOutcome.RateLimited ->
                    AuthenticationOutcome.Rejected(outcome.error, settings.markSessionInvalid())
                is SessionRefreshOutcome.Failed -> AuthenticationOutcome.Failed(outcome.error)
            }
        } catch (error: Throwable) {
            if (!isOwnerAlive()) AuthenticationOutcome.Cancelled
            else AuthenticationOutcome.Failed(error)
        }
    }
}

