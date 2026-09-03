/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OkHttpTransport 与 MockWebServer 的真实 WebSocket 回环测试：
 * 升级透传、close code 映射（1001/1000）、401/400 握手失败映射、EOF→1006、
 * 帧超限拦截；writeTimeout 端到端对内核缓冲敏感，按 spec 退化为配置断言。
 */
class OkHttpTransportTest {

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

    private class RecordingListener : SyncTransport.Listener {
        val texts = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        @Volatile var closedCode: Int? = null
        @Volatile var closedReason: String? = null
        @Volatile var sessionExpired: SessionExpiredException? = null
        private val opened = CountDownLatch(1)
        private val textLatch = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val errored = CountDownLatch(1)
        private val expired = CountDownLatch(1)
        val terminal = CountDownLatch(1)

        override fun onOpen() { opened.countDown() }
        override fun onText(text: String) { texts.add(text); textLatch.countDown() }
        override fun onClosed(code: Int, reason: String) {
            closedCode = code
            closedReason = reason
            closed.countDown()
            terminal.countDown()
        }
        override fun onError(error: Throwable) { errors.add(error); errored.countDown(); terminal.countDown() }
        override fun onSessionExpired(error: SessionExpiredException) {
            sessionExpired = error
            expired.countDown()
            terminal.countDown()
        }

        fun awaitOpen(seconds: Long = 10L) = opened.await(seconds, TimeUnit.SECONDS)
        fun awaitText(seconds: Long = 10L) = textLatch.await(seconds, TimeUnit.SECONDS)
        fun awaitClosed(seconds: Long = 10L) = closed.await(seconds, TimeUnit.SECONDS)
        fun awaitError(seconds: Long = 10L) = errored.await(seconds, TimeUnit.SECONDS)
        fun awaitExpired(seconds: Long = 10L) = expired.await(seconds, TimeUnit.SECONDS)
        fun awaitTerminal(seconds: Long = 10L) = terminal.await(seconds, TimeUnit.SECONDS)
    }

    private fun testClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun newTransport(
        listener: RecordingListener,
        maxFrameBytes: Long = ClipConfig.MAX_TRANSPORT_BYTES
    ): OkHttpTransport = OkHttpTransport(
        url = "wss://localhost:${server.port}/api/v1/sync",
        bearerToken = "tok-1",
        listener = listener,
        overrideRxTimeoutMs = OkHttpTransport.DEFAULT_RX_TIMEOUT_MS,
        maxFrameBytes = maxFrameBytes,
        clientFactory = { testClient() }
    )

    @Test
    fun upgradePassesThroughOpenAndText() {
        val serverSocketRef = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        val serverSocketLatch = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverSocketRef.set(webSocket)
                    serverSocketLatch.countDown()
                }
            })
        )
        val listener = RecordingListener()
        val transport = newTransport(listener)
        transport.connect()
        assertTrue(listener.awaitOpen())

        assertTrue(serverSocketLatch.await(5, TimeUnit.SECONDS))
        val payload = """{"type":"ping","serverTimeUtc":"2026-09-03T00:00:00Z"}"""
        serverSocketRef.get().send(payload)
        assertTrue(listener.awaitText())
        assertEquals(payload, listener.texts.first())
        transport.close(1000, "done")
    }

    @Test
    fun serverClose1001IsPassedThroughAsClosedCode() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.close(1001, "maintenance")
                }
            })
        )
        val listener = RecordingListener()
        val transport = newTransport(listener)
        transport.connect()
        assertTrue(listener.awaitOpen())
        // 客户端完成关闭握手后透传服务端原 code/reason（温和退避的输入）
        assertTrue(listener.awaitClosed())
        assertEquals(1001, listener.closedCode)
        assertEquals("maintenance", listener.closedReason)
    }

    @Test
    fun handshake401MapsToSessionExpired() {
        server.enqueue(MockResponse().setResponseCode(401))
        val listener = RecordingListener()
        val transport = newTransport(listener)
        transport.connect()
        assertTrue(listener.awaitExpired())
        assertEquals(401, listener.sessionExpired?.statusCode)
        assertNull(listener.closedCode)
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun handshake400MapsToErrorWithSubprotocolMessage() {
        server.enqueue(MockResponse().setResponseCode(400))
        val listener = RecordingListener()
        val transport = newTransport(listener)
        transport.connect()
        assertTrue(listener.awaitError())
        val message = listener.errors.first().message.orEmpty()
        assertTrue("actual message: $message", message.contains("HTTP 400"))
    }

    @Test
    fun failureMappingCoversAllTerminalBranches() {
        // MockWebServer 无法可靠模拟「服务端升级后断 TCP」且该路径依赖测试 JVM 的 TLS 环境稳定性，
        // 终态映射按纯函数覆盖；实机 ADB 清单覆盖真实半开场景。
        val eof = java.io.EOFException("unexpected EOF in websocket frame")
        // 升级后服务端断 TCP（无 close 帧）→ 1006
        assertEquals(
            OkHttpTransport.TerminalEvent.Closed(1006, "unexpected EOF"),
            OkHttpTransport.mapFailureToTerminal(eof, responseCode = null, wasOpen = true, watchdogTriggered = false)
        )
        // 握手阶段 EOF（尚未 open）→ onError
        assertEquals(
            OkHttpTransport.TerminalEvent.Error(eof),
            OkHttpTransport.mapFailureToTerminal(eof, responseCode = null, wasOpen = false, watchdogTriggered = false)
        )
        // 看门狗 cancel（服务端静默半开）→ 1006，不区分异常类型
        assertEquals(
            OkHttpTransport.TerminalEvent.Closed(1006, "unexpected EOF"),
            OkHttpTransport.mapFailureToTerminal(java.io.IOException("canceled"), responseCode = null, wasOpen = true, watchdogTriggered = true)
        )
        // 401/403 → 会话失效
        assertEquals(
            OkHttpTransport.TerminalEvent.SessionExpired(401),
            OkHttpTransport.mapFailureToTerminal(eof, responseCode = 401, wasOpen = false, watchdogTriggered = false)
        )
        assertEquals(
            OkHttpTransport.TerminalEvent.SessionExpired(403),
            OkHttpTransport.mapFailureToTerminal(eof, responseCode = 403, wasOpen = false, watchdogTriggered = false)
        )
        // 400 → 子协议协商失败（普通退避，与手写实现消息一致）
        val event = OkHttpTransport.mapFailureToTerminal(eof, responseCode = 400, wasOpen = false, watchdogTriggered = false)
        assertTrue(event is OkHttpTransport.TerminalEvent.Error)
        assertTrue((event as OkHttpTransport.TerminalEvent.Error).error.message.orEmpty().contains("HTTP 400"))
        // 其余异常 → onError 透传
        val reset = java.io.IOException("connection reset")
        assertEquals(
            OkHttpTransport.TerminalEvent.Error(reset),
            OkHttpTransport.mapFailureToTerminal(reset, responseCode = null, wasOpen = true, watchdogTriggered = false)
        )
    }

    @Test
    fun oversizedInboundFrameCancelsAndReportsError() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("x".repeat(2048))
                }
            })
        )
        val listener = RecordingListener()
        val transport = newTransport(listener, maxFrameBytes = 1024L)
        transport.connect()
        assertTrue(listener.awaitOpen())
        assertTrue(listener.awaitError())
        assertTrue(listener.errors.first().message.orEmpty().contains("exceeds transport limit"))
        transport.close(1000, "done")
    }

    @Test
    fun watchdogAndWriteTimeoutDerivationMatchSpec() {
        // 阈值 = heartbeatIntervalSeconds + 10s，钳制 [15s, 300s]；须大于服务端 30s ping 间隔
        assertEquals(40_000L, OkHttpTransport.watchdogRxTimeoutMs(30))
        assertEquals(30_000L, OkHttpTransport.watchdogRxTimeoutMs(20))
        assertEquals(15_000L, OkHttpTransport.watchdogRxTimeoutMs(1))
        assertEquals(300_000L, OkHttpTransport.watchdogRxTimeoutMs(10_000_000))
        // writeTimeout：半开连接上的写入 ~2s 暴露（端到端对内核缓冲敏感，实机清单覆盖）
        assertEquals(2_000L, OkHttpTransport.WRITE_TIMEOUT_MS)
    }

    @Test
    fun sendOnNeverOpenedTransportThrows() {
        server.enqueue(MockResponse().setResponseCode(400))
        val listener = RecordingListener()
        val transport = newTransport(listener)
        transport.connect()
        assertTrue(listener.awaitError())
        try {
            transport.sendText("""{"type":"hello"}""")
            throw AssertionError("sendText must throw when not connected")
        } catch (expected: java.io.IOException) {
            assertEquals("WebSocket is not connected", expected.message)
        }
        transport.close(1000, "done")
    }
}
