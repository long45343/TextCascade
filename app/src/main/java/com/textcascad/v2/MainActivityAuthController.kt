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

import android.app.Activity
import java.util.concurrent.atomic.AtomicLong

internal class MainActivityAuthController(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
    private val uiBinding: MainActivityUiBinding,
    private val dependencies: AuthenticationDependencies,
    private val authGeneration: AtomicLong = AtomicLong(0L)
) {
    var sessionPersistenceFailed = false
        private set
    var serviceRunningUiOverride: Boolean? = null
        private set

    fun updateStatus() {
        uiBinding.updateStatus(settingsStore, sessionPersistenceFailed, serviceRunningUiOverride)
    }

    fun setStatus(message: String) {
        settingsStore.statusMessage = message
        updateStatus()
    }

    private fun setBusy(busy: Boolean, message: String) {
        uiBinding.setBusy(busy, message, settingsStore, sessionPersistenceFailed, serviceRunningUiOverride)
    }

    private fun saveEditableSettings(): Boolean {
        return uiBinding.saveEditableSettings(settingsStore) { error ->
            setStatus(error)
        }
    }

    fun login() {
        if (!saveEditableSettings()) return
        val typedPassword = uiBinding.typedPassword()
        val savedPasswordUsed = typedPassword.isBlank()
        setBusy(true, activity.getString(R.string.status_logging_in))
        val activityGeneration = authGeneration.incrementAndGet()
        val submitted = AuthenticationCoordinator.submit(replaceActive = true) authTask@{ requestGeneration ->
            val password = typedPassword.ifBlank {
                if (settingsStore.savePassword) settingsStore.savedEncryptedPassword else ""
            }
            val outcome = AuthenticationWorkflow(
                settings = settingsStore,
                loginClientFactory = dependencies.loginClientFactory,
                deriveCredentials = { value, _ ->
                    deriveCredentials(settingsStore, value)
                },
                startService = { _ ->
                    if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) {
                        false
                    } else {
                        settingsStore.serviceRunning = true
                        serviceRunningUiOverride = true
                        settingsStore.statusMessage = activity.getString(R.string.status_connecting)
                        dependencies.startService(activity)
                        true
                    }
                },
                setStatus = {},
                isOwnerAlive = { isAuthTaskCurrent(activityGeneration, requestGeneration) }
            ).execute(
                password = password,
                savedPasswordUsed = savedPasswordUsed,
                savedPassword = if (settingsStore.savePassword) typedPassword.takeIf { it.isNotBlank() } else ""
            )

            if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
            activity.runOnUiThread {
                val currentView = LoginViewState(
                    isLoading = true,
                    sessionPersistenceFailed = sessionPersistenceFailed,
                    serviceRunningUiOverride = serviceRunningUiOverride,
                    message = activity.getString(R.string.status_logging_in)
                )
                val viewState = LoginOutcomeReducer.reduce(
                    outcome = outcome,
                    current = currentView,
                    currentThreadStillValid = true,
                    message = { msg ->
                        when (msg) {
                            LoginOutcomeMessage.MissingPassword -> activity.getString(R.string.status_login_required_fields)
                            LoginOutcomeMessage.Connecting -> activity.getString(R.string.status_connecting)
                            LoginOutcomeMessage.InvalidCredentials -> activity.getString(R.string.status_invalid_credentials)
                            LoginOutcomeMessage.LoginRateLimited -> activity.getString(R.string.status_login_rate_limited)
                            LoginOutcomeMessage.SessionPersistenceFailed -> activity.getString(R.string.status_session_invalidation_persist_failed)
                            is LoginOutcomeMessage.ProtocolUnsupported -> activity.getString(
                                R.string.status_protocol_unsupported,
                                msg.serverVersion,
                                Protocol.SUPPORTED_PROTOCOL_VERSION
                            )
                            is LoginOutcomeMessage.LoginFailed -> activity.getString(R.string.status_login_failed, msg.detail)
                        }
                    }
                )
                sessionPersistenceFailed = viewState.sessionPersistenceFailed
                serviceRunningUiOverride = viewState.serviceRunningUiOverride
                if (viewState.clearPasswordInput) {
                    uiBinding.clearPasswordInput()
                }
                if (viewState.reloadSettings) {
                    uiBinding.loadSettings(settingsStore)
                }
                setBusy(viewState.isLoading, viewState.message)
            }
        }
        if (submitted == null) {
            setBusy(false, activity.getString(R.string.status_login_failed, "Authentication executor unavailable"))
        }
    }

    fun logout() {
        saveEditableSettings()
        dependencies.stopService(activity)
        val activityGeneration = authGeneration.incrementAndGet()
        AuthenticationCoordinator.submit(replaceActive = true) authTask@{ requestGeneration ->
            try {
                if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
                if (!settingsStore.clearSession()) throw IllegalStateException("Unable to clear login session")
                if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
                activity.runOnUiThread {
                    uiBinding.loadSettings(settingsStore)
                    setStatus(activity.getString(R.string.status_logged_out))
                }
            } catch (error: Throwable) {
                if (error is InterruptedException || !isAuthTaskCurrent(activityGeneration, requestGeneration)) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    return@authTask
                }
                activity.runOnUiThread {
                    setStatus(activity.getString(R.string.status_login_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    fun startServiceFromUi() {
        if (!saveEditableSettings()) return
        if (sessionPersistenceFailed) {
            setStatus(activity.getString(R.string.status_session_invalidation_persist_failed))
            return
        }
        if (!settingsStore.hasSession || settingsStore.token.isBlank()) {
            setStatus(activity.getString(R.string.status_login_first))
            return
        }
        dependencies.startService(activity)
        settingsStore.serviceRunning = true
        serviceRunningUiOverride = true
        setStatus(activity.getString(R.string.status_connecting))
    }

    fun stopServiceFromUi() {
        dependencies.stopService(activity)
        settingsStore.serviceRunning = false
        serviceRunningUiOverride = false
        setStatus(activity.getString(R.string.status_service_stopped))
    }

    fun saveAndReconnect() {
        if (!saveEditableSettings()) return
        val typedPassword = uiBinding.typedPassword()
        val hasPassword = typedPassword.isNotBlank() ||
            (settingsStore.savePassword && settingsStore.savedEncryptedPassword.isNotBlank())
        if (!hasPassword) {
            setStatus(activity.getString(R.string.status_login_required_fields))
            return
        }
        ClipServiceController.saveReconnect(activity, typedPassword)
        uiBinding.clearPasswordInput()
        setStatus(activity.getString(R.string.status_connecting))
    }

    fun onDestroy() {
        authGeneration.incrementAndGet()
    }

    private fun isAuthTaskCurrent(activityGeneration: Long, requestGeneration: Long): Boolean =
        activityGeneration == authGeneration.get() &&
            AuthenticationCoordinator.isCurrent(requestGeneration) &&
            !activity.isFinishing &&
            !activity.isDestroyed
}

