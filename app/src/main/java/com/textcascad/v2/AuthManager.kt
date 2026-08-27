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

import java.util.concurrent.CountDownLatch

/**
 * 统一认证入口：前台登录、后台重登和引擎 cached relogin 共用。
 *
 * 按 Spec A1/A2/A3/A5：
 * - 只返回 [AuthResult]，成功动作由各 Controller 决定；
 * - [AuthenticationCoordinator] 仍作为进程级 single-flight 基础设施；
 * - owner/cancel 检查集中在这里，Controller 不再维护重复 generation。
 */
internal class AuthManager(
    private val settings: SettingsStore,
    private val dependencies: AuthenticationDependencies = AuthenticationDependencies(),
    private val loginClientOverride: LoginClient? = null
) {
    /**
     * 提交认证请求并等待协调器执行器上的任务完成；调用方（Activity/Service）
     * 必须把本调用放到非主线程。返回 null 表示未提交（已有活动任务且不可替换）。
     */
    fun submit(
        replaceActive: Boolean,
        owner: AuthOwner,
        password: String,
        savedPasswordUsed: Boolean,
        savedPassword: String?,
        onSuccess: () -> Unit = {}
    ): AuthResult? {
        val holder = ResultHolder(AuthResult.Cancelled)
        val finished = CountDownLatch(1)
        val submitted = AuthenticationCoordinator.submit(replaceActive = replaceActive) { requestGeneration ->
            try {
                if (!owner.isCurrent()) return@submit
                val result = execute(
                    owner = owner,
                    requestGeneration = requestGeneration,
                    password = password,
                    savedPasswordUsed = savedPasswordUsed,
                    savedPassword = savedPassword
                )
                holder.value = result
                if (result is AuthResult.Success && owner.isCurrent()) {
                    onSuccess()
                }
            } catch (error: Throwable) {
                holder.value = AuthResult.Failed(error)
            } finally {
                finished.countDown()
            }
        }
        if (submitted == null) return null
        try {
            finished.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return holder.value
    }

    /** 引擎线程同步重登；使用 Coordinator 的 blocking 单飞语义。 */
    fun cachedRelogin(owner: AuthOwner = AuthOwner.ALWAYS): AuthResult {
        val serverUrl = settings.serverUrl
        val username = settings.username
        val savedPassword = settings.savedEncryptedPassword

        if (serverUrl.isBlank() || username.isBlank() || savedPassword.isBlank()) {
            return AuthResult.NoCredentials
        }

        val result = AuthenticationCoordinator.submitBlocking(replaceActive = false) { requestGeneration ->
            if (!owner.isCurrent()) {
                AuthResult.Cancelled
            } else {
                refresh(
                    loginClient = currentLoginClient(),
                    password = savedPassword,
                    savedPassword = null,
                    owner = owner,
                    requestGeneration = requestGeneration
                )
            }
        }
        return result ?: AuthResult.Failed(IllegalStateException("Authentication executor busy"))
    }

    internal fun currentLoginClient(): LoginClient =
        loginClientOverride ?: dependencies.loginClientFactory(
            settings.trustAllCerts,
            settings.pinnedCertSha256
        )

    private fun execute(
        owner: AuthOwner,
        requestGeneration: Long,
        password: String,
        savedPasswordUsed: Boolean,
        savedPassword: String?
    ): AuthResult {
        // 输入为空时回退到已保存密码；两者皆无则视为缺凭据。
        val effectivePassword = if (password.isNotBlank()) {
            password
        } else if (savedPasswordUsed && settings.savedEncryptedPassword.isNotBlank()) {
            settings.savedEncryptedPassword
        } else {
            return AuthResult.MissingPassword
        }
        return refresh(
            loginClient = currentLoginClient(),
            password = effectivePassword,
            savedPassword = when {
                !settings.savePassword -> ""
                savedPassword != null && savedPassword.isNotBlank() -> savedPassword
                savedPasswordUsed && settings.savedEncryptedPassword.isNotBlank() -> null
                else -> ""
            },
            owner = owner,
            requestGeneration = requestGeneration
        )
    }

    private fun refresh(
        loginClient: LoginClient,
        password: String,
        savedPassword: String?,
        owner: AuthOwner,
        requestGeneration: Long
    ): AuthResult {
        val result = SessionRefresher(
            settings = settings,
            deriveKeyBase64 = { value -> deriveCredentials(settings, value).derivedKeyBase64 },
            markSessionInvalid = ::commitInvalidSession
        ).refresh(loginClient, password, savedPassword)
        if (!owner.isCurrent()) return AuthResult.Cancelled
        return result
    }

    /**
     * 拒绝、协议不支持、限流、持久化失败后的安全失效路径。
     * `session_active=false` 与凭据事务同步提交；返回值供 persistence-failure 分支区分。
     */
    internal fun commitInvalidSession(): Boolean {
        if (!settings.appPreferences.setSessionActive(false)) return false
        return settings.markSessionInvalid(sessionActiveCommitted = true)
    }

    private class ResultHolder(@Volatile var value: AuthResult)
}

