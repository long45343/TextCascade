/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
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

import android.util.Base64
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class ClipApiClient {
   fun login(
       serverUrl: String,
       username: String,
        passwordSha3: String,
        hashedPasswordBase64: String = ""
   ): LoginResult {
       val normalizedServerUrl = serverUrl.trim().trimEnd('/')
       val cookieJar = HttpCookieJar()
        val loginPage = request(
            url = "$normalizedServerUrl/login",
            method = "GET",
            cookieJar = cookieJar
        )
        check(loginPage.statusCode in 200..299) {
            "Failed to fetch login page: ${loginPage.statusCode}"
        }

        val csrf = findLoginCsrf(loginPage.body)
        check(csrf.isNotBlank()) { "No CSRF token found in login page" }
        check(cookieJar.header().isNotBlank()) { "No Set-Cookie header returned from login page" }

        val body = formBody(
            "username" to username,
            "password" to passwordSha3,
            "_csrf" to csrf
        )
        val loginResponse = request(
            url = "$normalizedServerUrl/login",
            method = "POST",
            cookieJar = cookieJar,
            body = body,
            contentType = "application/x-www-form-urlencoded"
        )
        val loginFailed = loginResponse.body.lowercase(Locale.US).contains("bad credentials")
        check(loginResponse.statusCode in 200..299 && !loginFailed) {
            "Login failed: ${loginResponse.statusCode}"
        }

        val cookieHeader = cookieJar.header()
        check(cookieHeader.isNotBlank()) { "Login succeeded but no authenticated session cookie was retained" }
        val serverModeResponse = request(
            url = "$normalizedServerUrl/server-mode",
            method = "GET",
            cookieJar = cookieJar
        )
        check(serverModeResponse.statusCode in 200..299) {
            "Login succeeded but server mode request failed: ${serverModeResponse.statusCode}"
        }
        check(serverModeResponse.body.trimStart().startsWith("{")) {
            "Login succeeded but /server-mode returned HTML instead of JSON; session cookie was not accepted"
        }
        val serverMode = JsonUtil.stringField(serverModeResponse.body, "mode", "P2S")
        check(serverMode == "P2S") { "This refactor only supports P2S; server returned $serverMode" }

        val maxSizeResponse = request(
            url = "$normalizedServerUrl/max-size",
            method = "GET",
            cookieJar = cookieJar
        )
        check(maxSizeResponse.statusCode in 200..299) {
            "Login succeeded but max-size request failed: ${maxSizeResponse.statusCode}"
        }
        check(maxSizeResponse.body.trimStart().startsWith("{")) {
            "Login succeeded but /max-size returned HTML instead of JSON; session cookie was not accepted"
        }
        val maxSize = JsonUtil.longField(maxSizeResponse.body, "maxsize", ClipConfig.DEFAULT_MAX_SIZE_BYTES)

        val csrfResponse = request(
            url = "$normalizedServerUrl/csrf-token",
            method = "GET",
            cookieJar = cookieJar
        )
       val sessionCsrf = if (csrfResponse.statusCode in 200..299) {
           JsonUtil.stringField(csrfResponse.body, "token", "")
       } else {
           ""
       }

       return LoginResult(
           normalizedServerUrl = normalizedServerUrl,
           websocketUrl = ClipConfig.websocketUrlFromServerUrl(normalizedServerUrl),
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
        if (cookieHeader.isBlank()) {
            return
        }
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

    private fun request(
        url: String,
        method: String,
        cookieHeader: String = "",
        cookieJar: HttpCookieJar? = null,
        body: String? = null,
        contentType: String? = null,
        redirectsRemaining: Int = 5
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = false
            val requestCookieHeader = cookieJar?.header().orEmpty().ifBlank { cookieHeader }
            if (requestCookieHeader.isNotBlank()) {
                setRequestProperty("Cookie", requestCookieHeader)
            }
            if (contentType != null) {
                setRequestProperty("Content-Type", contentType)
            }
            if (body != null) {
                doOutput = true
            }
        }

        if (body != null) {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
        }

        val status = connection.responseCode
        cookieJar?.store(connection.headerFields["Set-Cookie"])
        val responseCookie = formatSetCookie(connection.headerFields["Set-Cookie"])
        val location = connection.getHeaderField("Location")
        if (status in 300..399 && !location.isNullOrBlank() && redirectsRemaining > 0) {
            connection.disconnect()
            val redirectedUrl = URI(url).resolve(location).toString()
            val redirectedMethod = if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == HttpURLConnection.HTTP_MOVED_PERM
            ) {
                "GET"
            } else {
                method
            }
            return request(
                url = redirectedUrl,
                method = redirectedMethod,
                cookieHeader = cookieHeader,
                cookieJar = cookieJar,
                body = if (redirectedMethod == "GET") null else body,
                contentType = if (redirectedMethod == "GET") null else contentType,
                redirectsRemaining = redirectsRemaining - 1
            )
        }

        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return HttpResult(status, responseBody, cookieJar?.header().orEmpty().ifBlank { responseCookie })
    }

    private fun formBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    private fun findLoginCsrf(html: String): String {
        val inputRegex = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("\\bname\\s*=\\s*(['\"])_csrf\\1", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("\\bvalue\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE)
        return inputRegex.findAll(html)
            .firstOrNull { nameRegex.containsMatchIn(it.value) }
            ?.let { valueRegex.find(it.value)?.groupValues?.getOrNull(2) }
            .orEmpty()
    }

    private fun formatSetCookie(values: List<String>?): String {
        return values.orEmpty()
            .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
            .joinToString("; ")
    }
}

private class HttpCookieJar {
    private val cookies = LinkedHashMap<String, String>()

    fun store(setCookieHeaders: List<String>?) {
        for (header in setCookieHeaders.orEmpty()) {
            val pair = header.substringBefore(';')
            val separator = pair.indexOf('=')
            if (separator <= 0) {
                continue
            }
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (name.isNotBlank()) {
                cookies[name] = value
            }
        }
    }

    fun header(): String {
        return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }
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

private data class HttpResult(
    val statusCode: Int,
    val body: String,
    val cookieHeader: String
)
