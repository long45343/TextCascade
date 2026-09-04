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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
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
 * HTTP 层由 OkHttp 承载：connect/read 5s、不跟随重定向、trustAll/pinning 复用 TlsFactory，
 * 与同步传输同源；测试可经 [HttpLoginClient.clientFactory] 注入客户端（MockWebServer 需要
 * localhost 信任配置）。
 */
class HttpLoginClient(
    private val trustAllCerts: Boolean = false,
    private val pinnedCertSha256: String = "",
    internal val clientFactory: (() -> OkHttpClient)? = null
) : LoginClient {

    private val httpClient: OkHttpClient by lazy {
        clientFactory?.invoke() ?: buildDefaultClient()
    }

    internal fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .sslSocketFactory(
            TlsFactory.sslSocketFactory(trustAllCerts, pinnedCertSha256),
            TlsFactory.x509TrustManager(trustAllCerts, pinnedCertSha256)
        )
        .hostnameVerifier(
            TlsFactory.hostnameVerifier(trustAllCerts, pinnedCertSha256)
                ?: HttpsURLConnection.getDefaultHostnameVerifier()
        )
        .build()
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
        // Content-Type 由请求体携带（ByteArray 路径不加 charset 后缀），与旧实现逐字节一致
        val requestBody = body?.let {
            it.toByteArray(Charsets.UTF_8).toRequestBody(contentType?.toMediaType())
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        requestBuilder.method(method, requestBody)

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val limit = if (response.code in 200..399) MAX_RESPONSE_BODY_BYTES else MAX_ERROR_BODY_BYTES
            val responseBody = readBounded(checkNotNull(response.body).source(), limit)
            val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            return HttpResult(response.code, responseBody, retryAfter, uri)
        }
    }

    /** 有界读取：超过 maxBytes 立即失败（2xx 成功体与错误体共用同一保护）。 */
    private fun readBounded(source: BufferedSource, maxBytes: Long): String {
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) {
            throw IOException("HTTP response exceeds size limit")
        }
        return source.readUtf8()
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


