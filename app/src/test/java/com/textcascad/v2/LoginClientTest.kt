/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HttpLoginClient 的登录响应解析与请求形态测试（Robolectric：parseLoginResponse 依赖平台 org.json）。
 * 网络层用 OkHttp 拦截器短路返回预置响应，不发起真实 socket/TLS；
 * 真实 TLS/流式行为（重定向不跟随、超限、pinning、401/429/500 映射）由纯 JVM 的
 * LoginClientHttpTest 覆盖——Robolectric 的 conscrypt 与 okhttp-tls 自定义
 * TrustManager 组合下 TLS 校验不可靠，不做 socket 级测试。
 */
@RunWith(RobolectricTestRunner::class)
class LoginClientTest {

    /** 记录最终发出的请求并以预置响应短路（不连接网络）。 */
    private class FakeResponseInterceptor(
        private val status: Int,
        private val body: String,
        private val headers: Map<String, String> = emptyMap()
    ) : Interceptor {
        var lastRequest: Request? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            lastRequest = chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody(null))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
        }
    }

    private var lastInterceptor: FakeResponseInterceptor? = null

    private fun clientWith(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpLoginClient {
        lastInterceptor = FakeResponseInterceptor(status, body, headers)
        return HttpLoginClient(clientFactory = {
            OkHttpClient.Builder().addInterceptor(checkNotNull(lastInterceptor)).build()
        })
    }

    private fun sentRequest(): Request = checkNotNull(checkNotNull(lastInterceptor).lastRequest)

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

        val request = sentRequest()
        assertEquals("POST", request.method)
        assertEquals("https://srv.example/api/v1/login", request.url.toString())
        // 应用层拦截器位于 BridgeInterceptor 之前：Content-Type 仍由请求体携带（无 charset 后缀）
        assertEquals("application/json", request.body?.contentType()?.toString())
        assertEquals("application/json", request.header("Accept"))
        val buffer = Buffer()
        checkNotNull(request.body).writeTo(buffer)
        assertEquals(Protocol.loginMessage("user", "pass"), buffer.readUtf8())
    }

    @Test(expected = IllegalArgumentException::class)
    fun loginRejectsHttpUrl() {
        val client = HttpLoginClient()
        client.login("http://srv.example", "user", "pass")
    }

    @Test
    fun loginAppendsApiV1PathToBase() {
        val client = clientWith(200, ContractSamples.LOGIN_RESPONSE)
        client.login("https://srv.example:8443/", "user", "pass")
        assertEquals("https://srv.example:8443/api/v1/login", sentRequest().url.toString())
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

    @Test
    fun loginRejectsNonJsonBody() {
        val client = clientWith(200, "not-json")
        try {
            client.login("https://srv.example", "user", "pass")
            fail("expected IllegalStateException for non-JSON body")
        } catch (e: IllegalStateException) {
            assertEquals("Login endpoint returned non-JSON body", e.message)
        }
    }
}
