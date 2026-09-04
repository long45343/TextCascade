/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * HttpLoginClient 与 MockWebServer 的真实 HTTPS 回环测试（纯 JVM，不使用 Robolectric——
 * Robolectric 的 conscrypt 与 okhttp-tls 自定义 TrustManager 组合下 TLS 校验不可靠，
 * OkHttpTransportTest 同款模式）。覆盖：状态/头/体透传、超限保护、301 不跟随重定向、
 * 401/429/500 映射、trustAll 与证书 pinning 经真实 TlsFactory 装配、默认客户端配置断言。
 * 登录成功解析（org.json，仅 200 路径）在 Robolectric 的 LoginClientTest 中覆盖。
 *
 * 两类注入客户端均显式 followRedirects(false)，镜像生产默认客户端；
 * Hostname 一律放行——桌面 JVM 的默认 HostnameVerifier 与本机 JDK 组合不可靠
 * （生产为 Android；TlsFactory 对 pinning/trustAll 的设计本就放行 Hostname）。
 */
class LoginClientHttpTest {

    private lateinit var server: MockWebServer

    private val serverCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serverUrl(): String = "https://localhost:${server.port}"

    private fun loginUrl(): String = "${serverUrl()}${Protocol.LOGIN_PATH}"

    /** 信任 MockWebServer 证书的注入客户端（行为测试用；TLS 细节由 TlsFactoryTest 覆盖）。 */
    private fun clientWithTrustedServer(): HttpLoginClient = HttpLoginClient(
        trustAllCerts = false,
        clientFactory = {
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    )

    /** 经真实 TlsFactory 装配（factory + X509TrustManager）的注入客户端（trustAll/pinning 测试用）。 */
    private fun clientWithTlsFactory(trustAllCerts: Boolean, pinnedSha256: String = ""): HttpLoginClient =
        HttpLoginClient(
            trustAllCerts = trustAllCerts,
            pinnedCertSha256 = pinnedSha256,
            clientFactory = {
                OkHttpClient.Builder()
                    .sslSocketFactory(
                        TlsFactory.sslSocketFactory(trustAllCerts, pinnedSha256),
                        TlsFactory.x509TrustManager(trustAllCerts, pinnedSha256)
                    )
                    .hostnameVerifier { _, _ -> true }
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            }
        )

    @Test
    fun defaultClientConfigMatchesLegacyBehavior() {
        val ok = HttpLoginClient().buildDefaultClient()
        assertEquals(5000, ok.connectTimeoutMillis)
        assertEquals(5000, ok.readTimeoutMillis)
        assertFalse(ok.followRedirects)
        assertFalse(ok.followSslRedirects)
    }

    @Test
    fun requestReturnsStatusBodyAndRetryAfter() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":"rate_limited"}""")
                .setHeader("Retry-After", "77")
        )
        val client = clientWithTrustedServer()
        val result = client.request(loginUrl(), "POST", "{}", "application/json")
        assertEquals(429, result.statusCode)
        assertEquals("""{"error":"rate_limited"}""", result.body)
        assertEquals(77L, result.retryAfterSeconds)
        assertEquals("https://localhost:${server.port}/api/v1/login", result.finalUri.toString())
    }

    @Test
    fun requestSendsPostMethodHeadersAndBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = clientWithTrustedServer()
        client.request(loginUrl(), "POST", Protocol.loginMessage("user", "pass"), "application/json")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/login", recorded.path)
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals(Protocol.loginMessage("user", "pass"), recorded.body.readUtf8())
    }

    @Test
    fun oversizedSuccessBodyFailsWithSizeLimit() {
        val oversized = "a".repeat((ClipConfig.MAX_TRANSPORT_BYTES + 1L).toInt())
        server.enqueue(MockResponse().setResponseCode(200).setBody(oversized))
        val client = clientWithTrustedServer()
        try {
            client.request(loginUrl(), "POST", "{}", "application/json")
            fail("expected IOException for oversized body")
        } catch (e: IOException) {
            assertEquals("HTTP response exceeds size limit", e.message)
        }
    }

    @Test
    fun oversizedErrorBodyFailsWithSizeLimit() {
        val oversized = "a".repeat((64L * 1024L + 1L).toInt())
        server.enqueue(MockResponse().setResponseCode(500).setBody(oversized))
        val client = clientWithTrustedServer()
        try {
            client.request(loginUrl(), "POST", "{}", "application/json")
            fail("expected IOException for oversized error body")
        } catch (e: IOException) {
            assertEquals("HTTP response exceeds size limit", e.message)
        }
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(301).setBody("moved").setHeader("Location", "https://example.com/moved")
        )
        val client = clientWithTlsFactory(trustAllCerts = true)
        try {
            client.login(serverUrl(), "user", "pass")
            fail("expected LoginRequestFailedException")
        } catch (e: LoginRequestFailedException) {
            assertEquals(301, e.statusCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun login401MapsToRejected() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials"}"""))
        val client = clientWithTrustedServer()
        try {
            client.login(serverUrl(), "user", "wrong")
            fail("expected LoginRejectedException")
        } catch (e: LoginRejectedException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun login429MapsToRateLimitedWithRetryAfter() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":"rate_limited"}""")
                .setHeader("Retry-After", "77")
        )
        val client = clientWithTrustedServer()
        try {
            client.login(serverUrl(), "user", "pass")
            fail("expected LoginRateLimitedException")
        } catch (e: LoginRateLimitedException) {
            assertEquals(429, e.statusCode)
            assertEquals(77L, e.retryAfterSeconds)
        }
    }

    @Test
    fun login500MapsToRequestFailed() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val client = clientWithTrustedServer()
        try {
            client.login(serverUrl(), "user", "pass")
            fail("expected LoginRequestFailedException")
        } catch (e: LoginRequestFailedException) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun trustAllClientConnectsThroughRealTlsWiring() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ContractSamples.LOGIN_RESPONSE))
        val client = clientWithTlsFactory(trustAllCerts = true)
        val result = client.request(loginUrl(), "POST", "{}", "application/json")
        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("tok-123"))
    }

    @Test
    fun pinnedCertAcceptsMatchingPin() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ContractSamples.LOGIN_RESPONSE))
        val pin = TlsFactory.computeCertSha256Hex(serverCertificate.certificate)
        val client = clientWithTlsFactory(trustAllCerts = false, pinnedSha256 = pin)
        val result = client.request(loginUrl(), "POST", "{}", "application/json")
        assertEquals(200, result.statusCode)
    }

    @Test
    fun pinnedCertRejectsWrongPin() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = clientWithTlsFactory(trustAllCerts = false, pinnedSha256 = "A".repeat(64))
        try {
            client.request(loginUrl(), "POST", "{}", "application/json")
            fail("expected pin verification failure")
        } catch (e: IOException) {
            assertTrue("unexpected: $e", e.message!!.contains("pin", ignoreCase = true))
        }
    }
}
