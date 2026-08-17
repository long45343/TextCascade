/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import android.util.Base64

internal data class DerivedCredentials(
    val passwordSha3: String,
    val hashedPasswordBase64: String
)

internal sealed class AuthenticationOutcome {
    data class Success(val result: LoginResult) : AuthenticationOutcome()
    object Cancelled : AuthenticationOutcome()
    object MissingPassword : AuthenticationOutcome()
    data class Rejected(
        val error: LoginRejectedException,
        val invalidationPersisted: Boolean
    ) : AuthenticationOutcome()
    data class PersistenceFailure(
        val error: Throwable,
        val invalidationPersisted: Boolean
    ) : AuthenticationOutcome()
    data class Failed(val error: Throwable) : AuthenticationOutcome()
}

internal object AuthenticationDependencies {
    var settingsStoreFactory: (Context) -> SettingsStore = { SettingsStore(it) }
    var loginClientFactory: (Boolean) -> LoginClient = { ClipApiClient(it) }
    var startService: (Context) -> Unit = { ClipForegroundService.start(it) }
    var restartService: (ClipForegroundService) -> Unit = { it.restartSelfForFreshConfig() }
    var deriveCredentials: (SettingsStore, String) -> DerivedCredentials = { settings, password ->
        DerivedCredentials(
            passwordSha3 = CryptoManager.sha3_512LowercaseHex(password),
            hashedPasswordBase64 = if (settings.cipherEnabled) {
                Base64.encodeToString(
                    CryptoManager.derivePasswordKey(
                        settings.username,
                        password,
                        settings.salt,
                        settings.hashRounds
                    ),
                    Base64.NO_WRAP
                )
            } else ""
        )
    }

    fun reset() {
        settingsStoreFactory = { SettingsStore(it) }
        loginClientFactory = { ClipApiClient(it) }
        startService = { ClipForegroundService.start(it) }
        restartService = { it.restartSelfForFreshConfig() }
        deriveCredentials = { settings, password ->
            DerivedCredentials(
                passwordSha3 = CryptoManager.sha3_512LowercaseHex(password),
                hashedPasswordBase64 = if (settings.cipherEnabled) {
                    Base64.encodeToString(
                        CryptoManager.derivePasswordKey(
                            settings.username,
                            password,
                            settings.salt,
                            settings.hashRounds
                        ),
                        Base64.NO_WRAP
                    )
                } else ""
            )
        }
    }
}

internal class AuthenticationWorkflow(
    private val settings: SettingsStore,
    private val loginClientFactory: (Boolean) -> LoginClient,
    private val deriveCredentials: (password: String, savedPasswordUsed: Boolean) -> DerivedCredentials,
    private val startService: (LoginResult) -> Unit,
    private val setStatus: (String) -> Unit,
    private val isOwnerAlive: () -> Boolean
) {
    fun execute(password: String, savedPasswordUsed: Boolean, savedPassword: String? = null): AuthenticationOutcome {
        if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled
        if (password.isBlank()) {
            setStatus("missing_password")
            return AuthenticationOutcome.MissingPassword
        }

        return try {
            val credentials = deriveCredentials(password, savedPasswordUsed)
            if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled

            val result = loginClientFactory(settings.trustAllCerts).login(
                serverUrl = settings.serverUrl,
                username = settings.username,
                passwordSha3 = credentials.passwordSha3,
                hashedPasswordBase64 = credentials.hashedPasswordBase64
            )
            if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled

            val committed = settings.updateLoginSession(
                SessionSnapshot(
                    serverUrl = result.normalizedServerUrl,
                    websocketUrl = result.websocketUrl,
                    passwordSha3 = result.passwordSha3,
                    hashedPasswordBase64 = result.hashedPasswordBase64,
                    csrfToken = result.csrfToken,
                    cookieHeader = result.cookieHeader,
                    maxSizeBytes = result.maxSizeBytes,
                    savedPassword = savedPassword
                )
            )
            if (!committed) {
                return AuthenticationOutcome.PersistenceFailure(
                    error = IllegalStateException("Unable to persist login session"),
                    invalidationPersisted = settings.markSessionInvalid()
                )
            }
            if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled

            startService(result)
            AuthenticationOutcome.Success(result)
        } catch (error: LoginRejectedException) {
            if (!isOwnerAlive()) return AuthenticationOutcome.Cancelled
            AuthenticationOutcome.Rejected(error, settings.markSessionInvalid())
        } catch (error: Throwable) {
            if (!isOwnerAlive()) AuthenticationOutcome.Cancelled
            else AuthenticationOutcome.Failed(error)
        }
    }
}
