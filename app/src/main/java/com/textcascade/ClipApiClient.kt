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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

private const val MAX_RESPONSE_BODY_BYTES = ClipConfig.MAX_TRANSPORT_BYTES
private const val MAX_ERROR_BODY_BYTES = 64L * 1024L

open class ClipApiException(message: String) : Exception(message)

class LoginRejectedException(
    val statusCode: Int,
    val badCredentials: Boolean,
    message: String
) : ClipApiException(message)

class LoginRequestFailedException(
    val statusCode: Int,
    message: String
) : ClipApiException(message)

class ClipApiClient(
    private val trustAllCerts: Boolean = false,
    internal val connectionFactory: ((URL) -> HttpURLConnection)? = null
) : LoginClient {
    override fun login(
        serverUrl: String,
        username: String,
        passwordSha3: String,
        hashedPasswordBase64: String
    ): LoginResult {
        val normalizedServerUrl = serverUrl.trim().trimEnd('/')
        validateHttpUrl(normalizedServerUrl)
        val cookieJar = HttpCookieJar()
        val loginPage = request(
            url = "$normalizedServerUrl/login",
            method = "GET",
            cookieJar = cookieJar
        )
        if (loginPage.statusCode !in 200..299) {
            throw LoginRequestFailedException(loginPage.statusCode, "Failed to fetch login page: ${loginPage.statusCode}")
        }

        val finalLoginUri = loginPage.finalUri
        val csrf = findLoginCsrf(loginPage.body)
        check(csrf.isNotBlank()) { "No CSRF token found in login page" }
        check(cookieJar.header(finalLoginUri).isNotBlank()) {
            "No Set-Cookie header returned from login page"
        }

        val body = formBody(
            "username" to username,
            "password" to passwordSha3,
            "_csrf" to csrf
        )
        val loginResponse = request(
            url = finalLoginUri.toString(),
            method = "POST",
            cookieJar = cookieJar,
            body = body,
            contentType = "application/x-www-form-urlencoded"
        )
        val loginFailed = loginResponse.body.lowercase(Locale.US).contains("bad credentials")
        if (loginResponse.statusCode !in 200..299 || loginFailed) {
            throw LoginRejectedException(
                statusCode = loginResponse.statusCode,
                badCredentials = loginFailed,
                message = "Login rejected: ${loginResponse.statusCode}"
            )
        }

        val postLoginUri = loginResponse.finalUri
        val finalNormalizedServerUrl = deriveServerUrl(postLoginUri)
        val cookieHeader = cookieJar.header(postLoginUri)
        check(cookieHeader.isNotBlank()) { "Login succeeded but no authenticated session cookie was retained" }

        val serverModeUri = postLoginUri.resolve("server-mode")
        val serverModeResponse = request(
            url = serverModeUri.toString(),
            method = "GET",
            cookieJar = cookieJar
        )
        if (serverModeResponse.statusCode !in 200..299) {
            throw LoginRequestFailedException(
                serverModeResponse.statusCode,
                "Login succeeded but server mode request failed: ${serverModeResponse.statusCode}"
            )
        }
        check(serverModeResponse.body.trimStart().startsWith("{")) {
            "Login succeeded but /server-mode returned HTML instead of JSON; session cookie was not accepted"
        }
        val serverMode = JsonUtil.stringField(serverModeResponse.body, "mode", "P2S")
        check(serverMode == "P2S") { "This refactor only supports P2S; server returned $serverMode" }

        val maxSizeUri = postLoginUri.resolve("max-size")
        val maxSizeResponse = request(
            url = maxSizeUri.toString(),
            method = "GET",
            cookieJar = cookieJar
        )
        if (maxSizeResponse.statusCode !in 200..299) {
            throw LoginRequestFailedException(
                maxSizeResponse.statusCode,
                "Login succeeded but max-size request failed: ${maxSizeResponse.statusCode}"
            )
        }
        check(maxSizeResponse.body.trimStart().startsWith("{")) {
            "Login succeeded but /max-size returned HTML instead of JSON; session cookie was not accepted"
        }
        val maxSize = try {
            val serverMaxSize = JsonUtil.nullableLongField(maxSizeResponse.body, "maxsize")
            if (serverMaxSize == null || serverMaxSize <= 0L) {
                ClipConfig.DEFAULT_MAX_SIZE_BYTES
            } else {
                serverMaxSize.coerceAtMost(ClipConfig.MAX_CLIPBOARD_BYTES)
            }
        } catch (error: Exception) {
            throw LoginRequestFailedException(
                maxSizeResponse.statusCode,
                "Login succeeded but server max-size invalid"
            )
        }

        val csrfUri = postLoginUri.resolve("csrf-token")
        val csrfResponse = request(
            url = csrfUri.toString(),
            method = "GET",
            cookieJar = cookieJar
        )
        val sessionCsrf = if (csrfResponse.statusCode in 200..299) {
            JsonUtil.stringField(csrfResponse.body, "token", "")
        } else {
            ""
        }

        return LoginResult(
            normalizedServerUrl = finalNormalizedServerUrl,
            websocketUrl = ClipConfig.websocketUrlFromServerUrl(finalNormalizedServerUrl),
            passwordSha3 = passwordSha3,
            hashedPasswordBase64 = hashedPasswordBase64,
            csrfToken = sessionCsrf,
            cookieHeader = cookieHeader,
            maxSizeBytes = maxSize
        )
    }

    fun validateSession(serverUrl: String, cookieHeader: String): Boolean {
        val response = request(
            url = "${serverUrl.trim().trimEnd('/')}/validate-session",
            method = "GET",
            cookieHeader = cookieHeader
        )
        return response.statusCode in 200..299 && response.body == "OK"
    }

    fun logout(serverUrl: String, cookieHeader: String, csrfToken: String) {
        if (cookieHeader.isBlank()) return
        runCatching {
            request(
                url = "${serverUrl.trim().trimEnd('/')}/logout",
                method = "POST",
                cookieHeader = cookieHeader,
                body = formBody("_csrf" to csrfToken),
                contentType = "application/x-www-form-urlencoded"
            )
        }
    }

    internal fun request(
        url: String,
        method: String,
        cookieHeader: String = "",
        cookieJar: HttpCookieJar? = null,
        body: String? = null,
        contentType: String? = null,
        redirectsRemaining: Int = 5
    ): HttpResult {
        val uri = validateHttpUrl(url)
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
            val requestCookieHeader = cookieJar?.header(uri).orEmpty().ifBlank { cookieHeader }
            if (requestCookieHeader.isNotBlank()) {
                connection.setRequestProperty("Cookie", requestCookieHeader)
            }
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType)
            }
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            cookieJar?.store(uri, connection.headerFields)
            val location = connection.getHeaderField("Location")
            if (status in 300..399 && !location.isNullOrBlank()) {
                if (redirectsRemaining <= 0) {
                    throw LoginRequestFailedException(status, "Too many redirects")
                }
                val redirectedUri = runCatching { uri.resolve(location) }.getOrElse {
                    throw LoginRequestFailedException(status, "Invalid redirect Location: $location")
                }
                validateHttpUrl(redirectedUri.toString())
                if (!isSameOriginRedirect(uri, redirectedUri)) {
                    throw LoginRequestFailedException(status, "Cross-origin redirect rejected: $redirectedUri")
                }
                val redirectedMethod: String
                val redirectedBody: String?
                val redirectedContentType: String?
                when (status) {
                    307, 308 -> {
                        redirectedMethod = method
                        redirectedBody = body
                        redirectedContentType = contentType
                    }
                    else -> {
                        redirectedMethod = "GET"
                        redirectedBody = null
                        redirectedContentType = null
                    }
                }
                return request(
                    url = redirectedUri.toString(),
                    method = redirectedMethod,
                    cookieHeader = cookieHeader,
                    cookieJar = cookieJar,
                    body = redirectedBody,
                    contentType = redirectedContentType,
                    redirectsRemaining = redirectsRemaining - 1
                )
            }

            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val limit = if (status in 200..399) MAX_RESPONSE_BODY_BYTES else MAX_ERROR_BODY_BYTES
            val responseBody = stream?.use { readBounded(it, limit) }.orEmpty()
            return HttpResult(status, responseBody, cookieJar?.header(uri).orEmpty(), uri)
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

    private fun formBody(vararg pairs: Pair<String, String>): String = pairs.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

    private fun findLoginCsrf(html: String): String {
        val inputRegex = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""\bname\s*=\s*(['"])_csrf\1""", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("""\bvalue\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
        return inputRegex.findAll(html)
            .firstOrNull { nameRegex.containsMatchIn(it.value) }
            ?.let { valueRegex.find(it.value)?.groupValues?.getOrNull(2) }
            .orEmpty()
    }

    internal fun validateHttpUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid URL") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Only HTTP and HTTPS URLs are supported" }
        require(!uri.host.isNullOrBlank()) { "HTTP URL has no host" }
        return uri
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    internal fun isSameOriginRedirect(originalUri: URI, redirectedUri: URI): Boolean {
        if (!originalUri.host.equals(redirectedUri.host, ignoreCase = true)) return false
        if (originalUri.userInfo != redirectedUri.userInfo) return false
        val originalPort = effectivePort(originalUri)
        val redirectedPort = effectivePort(redirectedUri)
        if (originalPort == redirectedPort) return true
        return (originalUri.scheme.equals("http", ignoreCase = true) &&
            originalPort == 80 &&
            redirectedUri.scheme.equals("https", ignoreCase = true) &&
            redirectedPort == 443) ||
            (originalUri.scheme.equals("https", ignoreCase = true) &&
                originalPort == 443 &&
                redirectedUri.scheme.equals("http", ignoreCase = true) &&
                redirectedPort == 80)
    }

    internal fun deriveServerUrl(uri: URI): String {
        val path = uri.path.orEmpty()
        val basePath = if (path.endsWith("/login")) {
            path.substring(0, path.length - "/login".length)
        } else {
            path.substringBeforeLast('/', "")
        }
        val portPart = if (uri.port != -1 &&
            !((uri.scheme.equals("http", ignoreCase = true) && uri.port == 80) ||
              (uri.scheme.equals("https", ignoreCase = true) && uri.port == 443))
        ) {
            ":${uri.port}"
        } else ""
        val userInfoPart = if (!uri.userInfo.isNullOrBlank()) "${uri.userInfo}@" else ""
        return "${uri.scheme.lowercase()}://${userInfoPart}${uri.host}${portPart}${basePath}".trimEnd('/')
    }
}

internal class HttpCookieJar {
    private val manager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

    fun store(uri: URI, headers: Map<String?, List<String>?>?) {
        if (headers != null) runCatching { manager.put(uri, headers) }
    }

    fun header(uri: URI): String = runCatching {
        val cookies = manager.cookieStore.get(uri)
        cookies.filter { cookie ->
            if (cookie.secure && !uri.scheme.equals("https", ignoreCase = true)) {
                false
            } else if (cookie.hasExpired()) {
                false
            } else {
                true
            }
        }.joinToString("; ") { "${it.name}=${it.value}" }
    }.getOrDefault("")
}

data class LoginResult(
    val normalizedServerUrl: String,
    val websocketUrl: String,
    val passwordSha3: String,
    val hashedPasswordBase64: String,
    val csrfToken: String,
    val cookieHeader: String,
    val maxSizeBytes: Long
)

internal data class HttpResult(
    val statusCode: Int,
    val body: String,
    val cookieHeader: String,
    val finalUri: URI
)
