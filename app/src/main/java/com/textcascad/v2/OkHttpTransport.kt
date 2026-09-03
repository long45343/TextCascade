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

import java.io.EOFException
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 基于 OkHttp 的 SyncTransport 实现（替代手写 RFC6455 客户端）：
 * - 握手、帧编解码、TLS、WS 协议层 ping/pong 与客户端掩码由 OkHttp 承担
 * - 401/403 → SessionExpired；400 → 子协议协商失败（普通退避，保持现行为）
 * - 发送超时 writeTimeout(2s)：半开连接上的写入 ~2s 内暴露并触发重连
 * - 接收看门狗：rxTimeoutMs 由 heartbeatIntervalSeconds + 10s 派生（防恶意禁用有上下限）
 * - readTimeout 必须为 0：服务端 30s 应用层 JSON ping，任何有限读超时会杀健康空闲连接
 */
class OkHttpTransport(
    private val url: String,
    private val bearerToken: String,
    private val listener: SyncTransport.Listener,
    private val trustAllCerts: Boolean = false,
    private val pinnedCertSha256: String = "",
    overrideRxTimeoutMs: Long = DEFAULT_RX_TIMEOUT_MS,
    private val maxFrameBytes: Long = ClipConfig.MAX_TRANSPORT_BYTES,
    /** 测试接缝：注入预构建的 OkHttpClient（如 MockWebServer/TLS 自签场景）；null 时用默认构建。 */
    internal val clientFactory: ((WebSocketListener) -> OkHttpClient)? = null
) : SyncTransport {

    @Volatile
    var rxTimeoutMs: Long = DEFAULT_RX_TIMEOUT_MS
        private set

    init {
        require(maxFrameBytes in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_TRANSPORT_BYTES) {
            "maxFrameBytes must be between 1 and ${ClipConfig.MAX_TRANSPORT_BYTES}"
        }
        rxTimeoutMs = overrideRxTimeoutMs.coerceIn(MINIMUM_RX_TIMEOUT_MS, MAXIMUM_RX_TIMEOUT_MS)
    }

    private val started = AtomicBoolean(false)
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var isOpen = false
    @Volatile
    private var watchdogTriggered = false

    // 半开连接看门狗
    @Volatile
    private var lastRxTime: Long = 0
    private var watchdogFuture: ScheduledFuture<*>? = null
    private val watchdogLock = Any()

    companion object {
        const val DEFAULT_RX_TIMEOUT_MS = 120_000L
        const val MINIMUM_RX_TIMEOUT_MS = 15_000L
        const val MAXIMUM_RX_TIMEOUT_MS = 300_000L
        const val WRITE_TIMEOUT_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 10_000L

        internal val sharedWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "textcascade-watchdog").apply { isDaemon = true }
        }

        /** 看门狗超时：heartbeatIntervalSeconds + 10s，钳制在 [15s, 300s]（须大于服务端 ping 间隔）。 */
        fun watchdogRxTimeoutMs(heartbeatIntervalSeconds: Int): Long =
            ((heartbeatIntervalSeconds.toLong() + 10L) * 1000L)
                .coerceIn(MINIMUM_RX_TIMEOUT_MS, MAXIMUM_RX_TIMEOUT_MS)

        /**
         * onFailure → Listener 终态的映射：
         * 401/403 → 会话失效；400 → 子协议协商失败（保持普通退避行为）；看门狗触发或
         * 连接建立后的 EOF → onClosed(1006,"unexpected EOF")（等价手写实现 readLoop EOF 兜底）；
         * 其余 → onError。
         */
        internal fun mapFailureToTerminal(
            t: Throwable,
            responseCode: Int?,
            wasOpen: Boolean,
            watchdogTriggered: Boolean
        ): TerminalEvent = when {
            responseCode == 401 || responseCode == 403 -> TerminalEvent.SessionExpired(responseCode)
            responseCode == 400 ->
                TerminalEvent.Error(IOException("WebSocket subprotocol negotiation failed (HTTP 400)"))
            watchdogTriggered -> TerminalEvent.Closed(1006, "unexpected EOF")
            t is EOFException && wasOpen -> TerminalEvent.Closed(1006, "unexpected EOF")
            else -> TerminalEvent.Error(t)
        }
    }

    /** 终态回调的映射结果（internal：MockWebServer 无法可靠模拟服务端断 TCP，映射由单测直接覆盖）。 */
    internal sealed class TerminalEvent {
        data class Closed(val code: Int, val reason: String) : TerminalEvent()
        data class Error(val error: Throwable) : TerminalEvent()
        data class SessionExpired(val statusCode: Int) : TerminalEvent()
    }

    private fun buildClient(okListener: WebSocketListener): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .sslSocketFactory(
            TlsFactory.sslSocketFactory(trustAllCerts, pinnedCertSha256),
            TlsFactory.x509TrustManager(trustAllCerts, pinnedCertSha256)
        )
        .hostnameVerifier(
            TlsFactory.hostnameVerifier(trustAllCerts, pinnedCertSha256)
                ?: HttpsURLConnection.getDefaultHostnameVerifier()
        )
        .build()

    /** 仅支持 wss；内部换 https 形态交给 OkHttp。 */
    private fun httpsUrl(): String {
        val uri = URI(url)
        require(uri.scheme.equals("wss", ignoreCase = true)) {
            "Only wss:// WebSocket URLs are supported: ${uri.scheme}"
        }
        val host = uri.host ?: error("WebSocket URL has no host")
        val port = if (uri.port != -1) uri.port else 443
        return URI("https", uri.userInfo, host, port, uri.rawPath, uri.rawQuery, uri.rawFragment).toString()
    }

    override fun connect() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        val request = Request.Builder()
            .url(httpsUrl())
            .header("Authorization", "Bearer $bearerToken")
            .header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL)
            .build()
        val okListener = OkListener()
        val client = clientFactory?.invoke(okListener) ?: buildClient(okListener)
        webSocket = client.newWebSocket(request, okListener)
    }

    override fun sendText(text: String) {
        val ws = webSocket
        if (!isOpen || ws == null) {
            throw IOException("WebSocket is not connected")
        }
        if (!ws.send(text)) {
            throw IOException("WebSocket is not connected")
        }
    }

    override fun close(code: Int, reason: String) {
        stopWatchdog()
        isOpen = false
        val ws = webSocket
        runCatching { ws?.close(code, reason) }
        runCatching { ws?.cancel() }
    }

    private fun startWatchdog() {
        lastRxTime = System.currentTimeMillis()
        synchronized(watchdogLock) {
            watchdogFuture?.cancel(false)
            watchdogFuture = sharedWatchdogExecutor.scheduleAtFixedRate({
                if (!isOpen) return@scheduleAtFixedRate
                val elapsed = System.currentTimeMillis() - lastRxTime
                if (elapsed > rxTimeoutMs) {
                    watchdogTriggered = true
                    runCatching { webSocket?.cancel() }
                }
            }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopWatchdog() {
        synchronized(watchdogLock) {
            watchdogFuture?.cancel(false)
            watchdogFuture = null
        }
    }

    private inner class OkListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            lastRxTime = System.currentTimeMillis()
            startWatchdog()
            isOpen = true
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastRxTime = System.currentTimeMillis()
            if (text.toByteArray(Charsets.UTF_8).size > maxFrameBytes) {
                runCatching { webSocket.cancel() }
                listener.onError(IOException("WebSocket frame exceeds transport limit"))
                return
            }
            listener.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            lastRxTime = System.currentTimeMillis()
            runCatching { webSocket.cancel() }
            listener.onError(IOException("Binary frames are not supported"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 完成关闭握手；code/reason 由 onClosed 透传服务端原值
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            stopWatchdog()
            isOpen = false
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            stopWatchdog()
            val wasOpen = isOpen
            isOpen = false
            when (val event = mapFailureToTerminal(t, response?.code, wasOpen, watchdogTriggered)) {
                is TerminalEvent.SessionExpired ->
                    listener.onSessionExpired(SessionExpiredException(event.statusCode))
                is TerminalEvent.Closed -> listener.onClosed(event.code, event.reason)
                is TerminalEvent.Error -> listener.onError(event.error)
            }
        }
    }
}
