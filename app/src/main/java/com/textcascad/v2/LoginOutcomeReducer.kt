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

internal data class LoginViewState(
    val isLoading: Boolean,
    val sessionPersistenceFailed: Boolean,
    val serviceRunningUiOverride: Boolean?,
    val message: String,
    val clearPasswordInput: Boolean = false,
    val reloadSettings: Boolean = false
)

internal sealed class LoginOutcomeMessage {
    object MissingPassword : LoginOutcomeMessage()
    object Connecting : LoginOutcomeMessage()
    object InvalidCredentials : LoginOutcomeMessage()
    object LoginRateLimited : LoginOutcomeMessage()
    object SessionPersistenceFailed : LoginOutcomeMessage()
    data class ProtocolUnsupported(val serverVersion: Int) : LoginOutcomeMessage()
    data class LoginFailed(val detail: String) : LoginOutcomeMessage()
}

internal object LoginOutcomeReducer {
    fun reduce(
        outcome: AuthenticationOutcome,
        current: LoginViewState,
        currentThreadStillValid: Boolean,
        message: (LoginOutcomeMessage) -> String
    ): LoginViewState {
        if (!currentThreadStillValid) return current
        return when (outcome) {
            AuthenticationOutcome.Cancelled -> current
            AuthenticationOutcome.MissingPassword -> current.copy(
                isLoading = false,
                message = message(LoginOutcomeMessage.MissingPassword)
            )
            is AuthenticationOutcome.Success -> current.copy(
                isLoading = false,
                sessionPersistenceFailed = false,
                serviceRunningUiOverride = true,
                message = message(LoginOutcomeMessage.Connecting),
                clearPasswordInput = true,
                reloadSettings = true
            )
            is AuthenticationOutcome.ProtocolUnsupported -> current.copy(
                isLoading = false,
                sessionPersistenceFailed = false,
                serviceRunningUiOverride = false,
                message = message(LoginOutcomeMessage.ProtocolUnsupported(outcome.serverVersion))
            )
            is AuthenticationOutcome.PersistenceFailure -> current.copy(
                isLoading = false,
                sessionPersistenceFailed = !outcome.invalidationPersisted,
                serviceRunningUiOverride = false,
                message = if (outcome.invalidationPersisted) {
                    message(
                        LoginOutcomeMessage.LoginFailed(
                            outcome.error.message ?: outcome.error.javaClass.simpleName
                        )
                    )
                } else {
                    message(LoginOutcomeMessage.SessionPersistenceFailed)
                }
            )
            is AuthenticationOutcome.Rejected -> {
                if (outcome.error is LoginRateLimitedException) {
                    current.copy(
                        isLoading = false,
                        sessionPersistenceFailed = false,
                        serviceRunningUiOverride = false,
                        message = message(LoginOutcomeMessage.LoginRateLimited)
                    )
                } else if (!outcome.invalidationPersisted) {
                    current.copy(
                        isLoading = false,
                        sessionPersistenceFailed = true,
                        serviceRunningUiOverride = false,
                        message = message(LoginOutcomeMessage.SessionPersistenceFailed)
                    )
                } else {
                    current.copy(
                        isLoading = false,
                        sessionPersistenceFailed = false,
                        serviceRunningUiOverride = false,
                        message = if (outcome.error is LoginRejectedException) {
                            message(LoginOutcomeMessage.InvalidCredentials)
                        } else {
                            message(
                                LoginOutcomeMessage.LoginFailed(
                                    outcome.error.message ?: outcome.error.javaClass.simpleName
                                )
                            )
                        }
                    )
                }
            }
            is AuthenticationOutcome.Failed -> current.copy(
                isLoading = false,
                sessionPersistenceFailed = false,
                serviceRunningUiOverride = false,
                message = message(
                    LoginOutcomeMessage.LoginFailed(
                        outcome.error.message ?: outcome.error.javaClass.simpleName
                    )
                )
            )
        }
    }
}
