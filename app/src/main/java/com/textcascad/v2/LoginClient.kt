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

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val MAX_RESPONSE_BODY_BYTES = ClipConfig.MAX_TRANSPORT_BYTES
private const val MAX_ERROR_BODY_BYTES = 64L * 1024L

open class LoginApiException(message: String) : Exception(message)

/** 401 invalid_credentials：用户名或密码错误。 */
class LoginRejectedException(
    val statusCode: Int,
    message: String
) : LoginApiException(message)

/** 429 rate_limited：登录过于频繁，自动重登需退避至少 30s。 */
class LoginRateLimitedException(
    val statusCode: Int,
    val retryAfterSeconds: Long?
) : LoginApiException("Login rate limited (HTTP $statusCode)")

/** 其他登录请求失败（网络错误/5xx 等，可重试）。 */
class LoginRequestFailedException(
    val statusCode: Int,
    message: String
) : LoginApiException(message)

interface LoginClient {
    fun login(serverUrl: String, username: String, password: String): LoginResult
}

data class LoginResult(
    val normalizedServerUrl: String,
    val websocketUrl: String,
    val token: String,
    val tokenExpiresAtUtc: Long,
    val protocolVersion: Int,
    val maxTextBytes: Long,
    val helloTimeoutSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    val heartbeatTimeoutSeconds: Int
)

/**
 * POST /api/v1/login（JSON：username、password 原始密码，经 TLS 上送）。
 * 仅支持 https；映射 200 / 401 invalid_credentials / 429 rate_limited / 网络错误。
 */
class HttpLoginClient(
    private val trustAllCerts: Boolean = false,
    internal val connectionFactory: ((URL) -> HttpURLConnection)? = null
) : LoginClient {
    override fun login(serverUrl: String, username: String, password: String): LoginResult {
        val normalizedServerUrl = serverUrl.trim().trimEnd('/')
        validateHttpsUrl(normalizedServerUrl)
        val loginUri = URI(normalizedServerUrl).resolve(Protocol.LOGIN_PATH)

        val response = request(
            url = loginUri.toString(),
            method = "POST",
            body = Protocol.loginMessage(username, password),
            contentType = "application/json"
        )
        when {
            response.statusCode == 200 || response.statusCode == 201 -> {
                check(response.body.trimStart().startsWith("{")) {
                    "Login endpoint returned non-JSON body"
                }
                return parseLoginResponse(normalizedServerUrl, response.body)
            }
            response.statusCode == 401 -> throw LoginRejectedException(
                401,
                "Invalid credentials (HTTP 401)"
            )
            response.statusCode == 429 -> throw LoginRateLimitedException(
                429,
                response.retryAfterSeconds
            )
            else -> throw LoginRequestFailedException(
                response.statusCode,
                "Login request failed: HTTP ${response.statusCode}"
            )
        }
    }

    internal fun parseLoginResponse(normalizedServerUrl: String, body: String): LoginResult {
        val obj = JSONObject(body)
        val token = obj.optString("token", "")
        check(token.isNotBlank()) { "Login response missing token" }
        return LoginResult(
            normalizedServerUrl = normalizedServerUrl,
            websocketUrl = ClipConfig.websocketUrlFromServerUrl(normalizedServerUrl),
            token = token,
            tokenExpiresAtUtc = Protocol.parseUtcToEpochMillis(obj.optString("expiresAtUtc", ""))
                ?: 0L,
            protocolVersion = obj.optInt("protocolVersion", Protocol.SUPPORTED_PROTOCOL_VERSION),
            maxTextBytes = obj.optLong("maxTextBytes", ClipConfig.DEFAULT_MAX_TEXT_BYTES)
                .coerceIn(ClipConfig.MIN_CLIPBOARD_BYTES, ClipConfig.MAX_CLIPBOARD_BYTES),
            helloTimeoutSeconds = obj.optInt(
                "helloTimeoutSeconds", ClipConfig.DEFAULT_HELLO_TIMEOUT_SECONDS
            ).coerceAtLeast(1),
            heartbeatIntervalSeconds = obj.optInt(
                "heartbeatIntervalSeconds", ClipConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS
            ).coerceAtLeast(1),
            heartbeatTimeoutSeconds = obj.optInt(
                "heartbeatTimeoutSeconds", ClipConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS
            ).coerceAtLeast(1)
        )
    }

    internal fun request(
        url: String,
        method: String,
        body: String? = null,
        contentType: String? = null
    ): HttpResult {
        val uri = validateHttpsUrl(url)
        val connection = connectionFactory?.invoke(URL(url))
            ?: (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Unsupported HTTP connection")
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = TlsFactory.sslSocketFactory(trustAllCerts)
                if (!trustAllCerts) {
                    connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                }
            }
            connection.setRequestProperty("Accept", "application/json")
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType)
            }
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val limit = if (status in 200..399) MAX_RESPONSE_BODY_BYTES else MAX_ERROR_BODY_BYTES
            val responseBody = stream?.use { readBounded(it, limit) }.orEmpty()
            return HttpResult(status, responseBody, retryAfter, uri)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("HTTP response exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    internal fun validateHttpsUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid URL") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https") { "Only HTTPS server URLs are supported" }
        require(!uri.host.isNullOrBlank()) { "Server URL has no host" }
        return uri
    }
}

internal data class HttpResult(
    val statusCode: Int,
    val body: String,
    val retryAfterSeconds: Long?,
    val finalUri: URI
)
