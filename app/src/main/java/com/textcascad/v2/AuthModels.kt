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

import android.util.Base64

/**
 * 统一认证结果：前台登录、后台重登和引擎 cached relogin 共用。
 */
sealed class AuthResult {
    data class Success(val result: LoginResult) : AuthResult()
    object Cancelled : AuthResult()
    object MissingPassword : AuthResult()

    /** 服务端 protocolVersion 高于客户端支持：拒绝并提示升级，不隐式建连。 */
    data class ProtocolUnsupported(val serverVersion: Int) : AuthResult()
    data class AuthRejected(
        val error: LoginApiException,
        val invalidationPersisted: Boolean
    ) : AuthResult()
    data class RateLimited(val retryAfterSeconds: Long?) : AuthResult()
    object NoCredentials : AuthResult()
    data class PersistenceFailure(
        val error: Throwable,
        val invalidationPersisted: Boolean
    ) : AuthResult()
    data class Failed(val error: Throwable) : AuthResult()
}

/**
 * 认证 owner：封装前台 Activity alive、Service destroyed 与引擎 cached relogin 语义。
 */
fun interface AuthOwner {
    fun isCurrent(): Boolean

    companion object {
        val ALWAYS: AuthOwner = AuthOwner { true }
    }
}

internal data class DerivedCredentials(
    val derivedKeyBase64: String
)

internal fun deriveCredentials(settings: SettingsStore, password: String): DerivedCredentials {
    return DerivedCredentials(
        derivedKeyBase64 = if (settings.cipherEnabled) {
            android.util.Base64.encodeToString(
                CryptoManager.derivePasswordKey(
                    settings.username,
                    password,
                    settings.salt,
                    settings.hashRounds
                ),
                android.util.Base64.NO_WRAP
            )
        } else {
            ""
        }
    )
}

