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
    private val authManager = AuthManager(settingsStore, dependencies)

    var sessionPersistenceFailed = false
        private set
    var serviceRunningUiOverride: Boolean? = null
        private set

    fun updateStatus() {
        uiBinding.updateStatus(settingsStore, sessionPersistenceFailed, serviceRunningUiOverride)
    }

    fun setStatus(message: String) {
        settingsStore.statusMessage = message
        settingsStore.connectionStatusMessage = message
        updateStatus()
    }

    fun login() {
        if (!uiBinding.saveEditableSettings(settingsStore) { setStatus(it) }) return

        val typedPassword = uiBinding.typedPassword()
        val savedPasswordUsed = typedPassword.isBlank()
        setBusy(true, activity.getString(R.string.status_logging_in))
        val owner = ownerForGeneration(authGeneration.incrementAndGet())
        // 认证请求会阻塞到登录结束，必须在非主线程执行；结果分支回到 UI 线程。
        Thread {
            val submitted = authManager.submit(
                replaceActive = true,
                owner = owner,
                password = typedPassword.ifBlank {
                    if (settingsStore.savePassword) settingsStore.savedEncryptedPassword else ""
                },
                savedPasswordUsed = savedPasswordUsed,
                savedPassword = if (settingsStore.savePassword) typedPassword.takeIf { it.isNotBlank() } else "",
                onSuccess = {
                    settingsStore.serviceRunning = true
                    serviceRunningUiOverride = true
                    dependencies.startService(activity)
                }
            )

            activity.runOnUiThread {
            if (submitted == null) {
                applyLoginFailure(activity.getString(R.string.status_login_failed, "Authentication executor unavailable"))
                return@runOnUiThread
            }
            when (val result = submitted) {
                AuthResult.Cancelled -> Unit
                is AuthResult.Success -> applyLoginSuccess()
                AuthResult.MissingPassword ->
                    applyLoginFailure(activity.getString(R.string.status_login_required_fields))
                is AuthResult.ProtocolUnsupported ->
                    applyLoginFailure(
                        activity.getString(
                            R.string.status_protocol_unsupported,
                            result.serverVersion,
                            Protocol.SUPPORTED_PROTOCOL_VERSION
                        )
                    )
                is AuthResult.AuthRejected -> {
                    sessionPersistenceFailed = !result.invalidationPersisted
                    serviceRunningUiOverride = false
                    if (!result.invalidationPersisted) {
                        setBusy(false, activity.getString(R.string.status_session_invalidation_persist_failed))
                        updateStatus()
                    } else if (result.error is LoginRejectedException) {
                        setBusy(false, activity.getString(R.string.status_invalid_credentials))
                        updateStatus()
                    } else {
                        setBusy(
                            false,
                            activity.getString(R.string.status_login_failed, result.error.message ?: "login rejected")
                        )
                        updateStatus()
                    }
                }
                is AuthResult.RateLimited -> {
                    sessionPersistenceFailed = false
                    serviceRunningUiOverride = false
                    setBusy(false, activity.getString(R.string.status_login_rate_limited))
                    updateStatus()
                }
                AuthResult.NoCredentials ->
                    applyLoginFailure(activity.getString(R.string.status_login_required_fields))
                is AuthResult.PersistenceFailure -> {
                    sessionPersistenceFailed = !result.invalidationPersisted
                    serviceRunningUiOverride = false
                    if (!result.invalidationPersisted) {
                        setBusy(false, activity.getString(R.string.status_session_invalidation_persist_failed))
                    } else {
                        setBusy(
                            false,
                            activity.getString(R.string.status_login_failed, result.error.message ?: "persistence failed")
                        )
                    }
                    updateStatus()
                }
                is AuthResult.Failed ->
                    applyLoginFailure(
                        activity.getString(
                            R.string.status_login_failed,
                            result.error.message ?: result.error.javaClass.simpleName
                        )
                    )
                else -> Unit
            }
            }
        }.start()
    }

    fun logout() {
        uiBinding.saveEditableSettings(settingsStore) { setStatus(it) }
        dependencies.stopService(activity)
        val owner = ownerForGeneration(authGeneration.incrementAndGet())
        AuthenticationCoordinator.submit(replaceActive = true) { requestGeneration ->
            try {
                if (!owner.isCurrent()) return@submit
                if (!settingsStore.clearSession()) throw IllegalStateException("Unable to clear login session")
                if (!owner.isCurrent()) return@submit
                activity.runOnUiThread {
                    uiBinding.loadSettings(settingsStore)
                    setStatus(activity.getString(R.string.status_logged_out))
                }
            } catch (error: Throwable) {
                if (error is InterruptedException || !owner.isCurrent()) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    return@submit
                }
                activity.runOnUiThread {
                    setStatus(activity.getString(R.string.status_login_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    fun onDestroy() {
        authGeneration.incrementAndGet()
    }

    private fun applyLoginSuccess() {
        sessionPersistenceFailed = false
        serviceRunningUiOverride = true
        uiBinding.clearPasswordInput()
        uiBinding.loadSettings(settingsStore)
        uiBinding.loginButton.isEnabled = true
        updateStatus()
    }

    private fun applyLoginFailure(message: String) {
        sessionPersistenceFailed = false
        serviceRunningUiOverride = false
        setBusy(false, message)
        updateStatus()
    }

    private fun setBusy(busy: Boolean, message: String) {
        uiBinding.setBusy(busy, message, settingsStore, sessionPersistenceFailed, serviceRunningUiOverride)
    }

    internal fun ownerForGeneration(generation: Long): AuthOwner = AuthOwner {
        generation == authGeneration.get() &&
            !activity.isFinishing &&
            !activity.isDestroyed
    }

}
