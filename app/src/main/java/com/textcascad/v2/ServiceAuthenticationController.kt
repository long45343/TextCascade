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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal open class ServiceAuthenticationController(
    private val settings: SettingsStore,
    private val dependencies: AuthenticationDependencies,
    private val authGeneration: AtomicLong,
    private val serviceDestroyed: AtomicBoolean,
    private val autoLoginQueued: AtomicBoolean,
    private val strings: StringProvider,
    private val showStatus: (String) -> Unit,
    private val finishFailure: (String) -> Unit,
    private val restart: () -> Unit
) {
    private val authManager = AuthManager(settings, dependencies)

    open fun autoLogin() {
        val statusMessage = strings(R.string.status_auto_login)
        settings.statusMessage = statusMessage
        showStatus(statusMessage)
        if (!autoLoginQueued.compareAndSet(false, true)) return
        enqueueRelogin(typedPassword = "", automatic = true)
    }

    open fun reloginWithCurrentConfig(typedPassword: String) {
        showStatus(strings(R.string.status_connecting))
        enqueueRelogin(typedPassword = typedPassword, automatic = false)
    }

    private fun enqueueRelogin(typedPassword: String, automatic: Boolean) {
        val owner = ownerForGeneration(authGeneration.incrementAndGet())
        // 认证请求阻塞到登录结束；由后台线程执行，回调本身已在各自线程安全处理。
        Thread {
            val submitted = authManager.submit(
                replaceActive = !automatic,
                owner = owner,
                password = typedPassword.ifBlank {
                    if (settings.savePassword) settings.savedEncryptedPassword else ""
                },
                savedPasswordUsed = typedPassword.isBlank(),
                savedPassword = if (!settings.savePassword) "" else typedPassword.takeIf { it.isNotBlank() },
                onSuccess = {
                    settings.serviceRunning = true
                    restart()
                }
            )

            if (submitted == null) {
                if (automatic) autoLoginQueued.set(false)
                finishAuthFailure(
                    automatic,
                    strings(R.string.status_auto_login_failed, "Authentication executor unavailable")
                )
                return@Thread
            }
            handleOutcome(submitted, automatic, owner)
            if (automatic) autoLoginQueued.set(false)
        }.start()
    }

    private fun handleOutcome(result: AuthResult, automatic: Boolean, owner: AuthOwner) {
        when (result) {
            AuthResult.Cancelled -> Unit
            is AuthResult.Success -> Unit
            AuthResult.MissingPassword -> finishAuthFailure(
                automatic,
                if (automatic) strings(R.string.status_auto_login_failed, "No saved password")
                else strings(R.string.status_login_required_fields)
            )
            is AuthResult.ProtocolUnsupported -> finishAuthFailure(
                automatic,
                strings(
                    R.string.status_protocol_unsupported,
                    result.serverVersion,
                    Protocol.SUPPORTED_PROTOCOL_VERSION
                )
            )
            is AuthResult.PersistenceFailure -> finishAuthFailure(
                automatic,
                if (result.invalidationPersisted) {
                    authenticationErrorMessage(result.error.message.orEmpty(), automatic)
                } else {
                    strings(R.string.status_session_invalidation_persist_failed)
                }
            )
            is AuthResult.AuthRejected -> finishAuthFailure(
                automatic,
                if (result.invalidationPersisted) {
                    authenticationErrorMessage(result.error.message.orEmpty(), automatic)
                } else {
                    strings(R.string.status_session_invalidation_persist_failed)
                }
            )
            is AuthResult.RateLimited ->
                showAuthFailure(strings(R.string.status_login_rate_limited))
            AuthResult.NoCredentials ->
                finishAuthFailure(automatic, strings(R.string.status_session_expired))
            is AuthResult.Failed ->
                finishAuthFailure(
                    automatic,
                    authenticationErrorMessage(
                        result.error.message ?: result.error.javaClass.simpleName,
                        automatic
                    )
                )
        }
    }

    /** 引擎在连接线程同步调用；成功后由上层用新配置重建连接。 */
    open fun cachedReloginBlocking(): AuthResult {
        return authManager.cachedRelogin(ownerForCurrentService())
    }

    private fun authenticationErrorMessage(detail: String, automatic: Boolean): String =
        if (automatic) strings(R.string.status_auto_login_failed, detail)
        else strings(R.string.status_login_failed, detail)

    private fun showAuthFailure(message: String) {
        settings.statusMessage = message
        settings.connectionStatusMessage = message
        showStatus(message)
    }

    private fun finishAuthFailure(automatic: Boolean, message: String) {
        if (automatic && serviceDestroyed.get()) return
        finishFailure(message)
    }

    private fun ownerForCurrentService(): AuthOwner = AuthOwner { !serviceDestroyed.get() }

    private fun ownerForGeneration(generation: Long): AuthOwner = AuthOwner {
        generation == authGeneration.get() && !serviceDestroyed.get()
    }
}
