/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginClientTest {

    private class FakeHttpURLConnection(
        url: URL,
        var status: Int,
        var body: ByteArray,
        var headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = status

        override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(body)

        override fun getErrorStream(): java.io.InputStream = ByteArrayInputStream(body)

        override fun getOutputStream(): java.io.OutputStream = output

        override fun getHeaderField(name: String?): String = headers[name] ?: ""

        private val output = java.io.ByteArrayOutputStream()
    }

    private fun clientWith(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpLoginClient {
        var captured: FakeHttpURLConnection? = null
        val client = HttpLoginClient(trustAllCerts = false, connectionFactory = { url ->
            FakeHttpURLConnection(url, status, body.toByteArray(Charsets.UTF_8), headers).also { captured = it }
        })
        return client
    }

    @Test
    fun loginSuccessParsesContractResponse() {
        val client = clientWith(200, ContractSamples.LOGIN_RESPONSE)
        val result = client.login("https://srv.example", "user", "pass")
        assertEquals("https://srv.example", result.normalizedServerUrl)
        assertEquals("wss://srv.example/api/v1/sync", result.websocketUrl)
        assertEquals("tok-123", result.token)
        assertTrue(result.tokenExpiresAtUtc > 0L)
        assertEquals(1, result.protocolVersion)
        assertEquals(512_000L, result.maxTextBytes)
        assertEquals(10, result.helloTimeoutSeconds)
        assertEquals(20, result.heartbeatIntervalSeconds)
        assertEquals(60, result.heartbeatTimeoutSeconds)
    }

    @Test
    fun login401MapsToRejected() {
        val client = clientWith(401, """{"error":"invalid_credentials"}""")
        try {
            client.login("https://srv.example", "user", "wrong")
            fail("expected LoginRejectedException")
        } catch (e: LoginRejectedException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun login429MapsToRateLimitedWithRetryAfter() {
        val client = clientWith(
            429,
            """{"error":"rate_limited"}""",
            headers = mapOf("Retry-After" to "77")
        )
        try {
            client.login("https://srv.example", "user", "pass")
            fail("expected LoginRateLimitedException")
        } catch (e: LoginRateLimitedException) {
            assertEquals(429, e.statusCode)
            assertEquals(77L, e.retryAfterSeconds)
        }
    }

    @Test
    fun login500MapsToRequestFailed() {
        val client = clientWith(500, "oops")
        try {
            client.login("https://srv.example", "user", "pass")
            fail("expected LoginRequestFailedException")
        } catch (e: LoginRequestFailedException) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun loginRejectsHttpUrl() {
        val client = HttpLoginClient()
        client.login("http://srv.example", "user", "pass")
    }

    @Test
    fun loginAppendsApiV1PathToBase() {
        var requestedUrl: URL? = null
        val client = HttpLoginClient(connectionFactory = { url ->
            requestedUrl = url
            FakeHttpURLConnection(url, 200, ContractSamples.LOGIN_RESPONSE.toByteArray())
        })
        client.login("https://srv.example:8443/", "user", "pass")
        assertEquals("https://srv.example:8443/api/v1/login", requestedUrl.toString())
    }

    @Test
    fun parseLoginResponseFallsBackToDefaultsForMissingOptionalFields() {
        val client = HttpLoginClient()
        val result = client.parseLoginResponse(
            "https://srv.example",
            """{"token":"t"}"""
        )
        assertEquals("t", result.token)
        assertEquals(0L, result.tokenExpiresAtUtc)
        assertEquals(Protocol.SUPPORTED_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals(ClipConfig.DEFAULT_MAX_TEXT_BYTES, result.maxTextBytes)
        assertEquals(ClipConfig.DEFAULT_HELLO_TIMEOUT_SECONDS, result.helloTimeoutSeconds)
        assertEquals(ClipConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS, result.heartbeatIntervalSeconds)
        assertEquals(ClipConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS, result.heartbeatTimeoutSeconds)
    }

    @Test
    fun parseLoginResponseDetectsHigherProtocolVersion() {
        val client = HttpLoginClient()
        val result = client.parseLoginResponse(
            "https://srv.example",
            """{"token":"t","protocolVersion":2}"""
        )
        assertEquals(2, result.protocolVersion)
        assertTrue(result.protocolVersion > Protocol.SUPPORTED_PROTOCOL_VERSION)
    }
}
