/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
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

package com.textcascade

sealed class CachedReloginResult {
    data class Success(val result: LoginResult) : CachedReloginResult()
    object AuthFailure : CachedReloginResult()
    data class TransientFailure(val error: Throwable) : CachedReloginResult()
    object NoCredentials : CachedReloginResult()
}

interface LoginClient {
    fun login(
        serverUrl: String,
        username: String,
        passwordSha3: String,
        hashedPasswordBase64: String = ""
    ): LoginResult
}

class CachedReloginRunner(
    private val settings: SettingsStore,
    private val loginClient: LoginClient
) {
    fun execute(): CachedReloginResult {
        val serverUrl = settings.serverUrl
        val username = settings.username
        val passwordSha3 = settings.passwordSha3
        val hashedPasswordBase64 = settings.hashedPasswordBase64
        val cipherEnabled = settings.cipherEnabled

        if (serverUrl.isBlank() || username.isBlank() || passwordSha3.isBlank()) {
            return CachedReloginResult.NoCredentials
        }
        if (cipherEnabled && hashedPasswordBase64.isBlank()) {
            return CachedReloginResult.NoCredentials
        }

        return try {
            val result = loginClient.login(
                serverUrl = serverUrl,
                username = username,
                passwordSha3 = passwordSha3,
                hashedPasswordBase64 = hashedPasswordBase64
            )
            settings.serverUrl = result.normalizedServerUrl
            settings.websocketUrl = result.websocketUrl
            settings.csrfToken = result.csrfToken
            settings.cookieHeader = result.cookieHeader
            settings.maxSizeBytes = result.maxSizeBytes
            CachedReloginResult.Success(result)
        } catch (e: LoginRejectedException) {
            if (e.statusCode == 401 || e.statusCode == 403 || e.badCredentials) {
                CachedReloginResult.AuthFailure
            } else {
                CachedReloginResult.TransientFailure(e)
            }
        } catch (e: Throwable) {
            CachedReloginResult.TransientFailure(e)
        }
    }
}
