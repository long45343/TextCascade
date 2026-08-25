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

import com.textcascad.v2.engine.WebSocketFrameCodec
import com.textcascad.v2.engine.WebSocketHandshake
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * 手写 RFC6455 WebSocket 客户端（v2 协议）：
 * - 握手与协议编解码委托 [WebSocketHandshake] 与 [WebSocketFrameCodec]
 * - 401 → SessionExpiredException；400 → 子协议协商失败（致命错误）
 * - 接收看门狗：rxTimeoutMs 由 heartbeatTimeoutSeconds + 10s 派生（防恶意禁用有上下限）
 * - 关闭帧解析 close code 与 reason，回调 onClosed(code, reason)
 */
interface SyncTransport {
    fun connect()
    fun sendText(text: String)
    fun close(code: Int, reason: String)

    /** 默认按 UTF-8 解码后走 sendText；实现可覆写以直发原始字节。 */
    fun sendBytes(bytes: ByteArray) {
        sendText(String(bytes, Charsets.UTF_8))
    }
}

class RawWebSocketClient(
    private val url: String,
    private val bearerToken: String,
    private val listener: Listener,
    private val trustAllCerts: Boolean = false,
    private val pinnedCertSha256: String = "",
    overrideRxTimeoutMs: Long = DEFAULT_RX_TIMEOUT_MS,
    private val maxFrameBytes: Long = ClipConfig.MAX_TRANSPORT_BYTES,
    internal val socketFactory: ((secure: Boolean, host: String, port: Int, trustAll: Boolean) -> Socket)? = null,
    internal val hostnameVerifierFactory: (() -> HostnameVerifier)? = null
) : SyncTransport {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onError(error: Throwable)
        fun onSessionExpired(error: SessionExpiredException)
    }

    @Volatile
    var rxTimeoutMs: Long = DEFAULT_RX_TIMEOUT_MS
        private set

    init {
        require(maxFrameBytes in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_TRANSPORT_BYTES) {
            "maxFrameBytes must be between 1 and ${ClipConfig.MAX_TRANSPORT_BYTES}"
        }
        rxTimeoutMs = overrideRxTimeoutMs.coerceIn(MINIMUM_RX_TIMEOUT_MS, MAXIMUM_RX_TIMEOUT_MS)
    }

    private val running = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var input: BufferedInputStream? = null
    @Volatile
    private var output: BufferedOutputStream? = null
    private val random = SecureRandom()

    // 半开连接看门狗
    @Volatile
    private var lastRxTime: Long = 0
    private var watchdogFuture: ScheduledFuture<*>? = null
    private val watchdogLock = Any()

    companion object {
        const val MAX_FRAME_BYTES = ClipConfig.MAX_TRANSPORT_BYTES
        const val MAX_HTTP_HANDSHAKE_HEADER_BYTES = WebSocketHandshake.MAX_HTTP_HANDSHAKE_HEADER_BYTES
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 15_000
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        const val DEFAULT_RX_TIMEOUT_MS = 120_000L
        const val MINIMUM_RX_TIMEOUT_MS = 15_000L
        const val MAXIMUM_RX_TIMEOUT_MS = 300_000L

        internal val sharedWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "textcascade-watchdog").apply { isDaemon = true }
        }

        /** 看门狗超时：heartbeatTimeoutSeconds + 10s，钳制在 [15s, 300s]。 */
        fun watchdogRxTimeoutMs(heartbeatTimeoutSeconds: Int): Long =
            ((heartbeatTimeoutSeconds.toLong() + 10L) * 1000L)
                .coerceIn(MINIMUM_RX_TIMEOUT_MS, MAXIMUM_RX_TIMEOUT_MS)

        @Throws(IOException::class)
        internal fun readHttpHeadersFromStream(inp: InputStream): String =
            WebSocketHandshake.readHttpHeadersFromStream(inp)

        /** 纯逻辑帧处理器；保留作为向后兼容委托。 */
        @Throws(IOException::class)
        internal fun processIncomingFrame(
            fin: Boolean,
            opcode: Int,
            payload: ByteArray,
            currentFragmentedStream: ByteArrayOutputStream?,
            onText: (String) -> Unit,
            onSendPong: (ByteArray) -> Unit,
            onClose: (Int, String) -> Unit,
            maxMessageBytes: Long = ClipConfig.MAX_TRANSPORT_BYTES
        ): Pair<ByteArrayOutputStream?, Boolean> =
            WebSocketFrameCodec.processIncomingFrame(
                fin, opcode, payload, currentFragmentedStream, onText, onSendPong, onClose, maxMessageBytes
            )
    }

    override fun connect() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        if (!running.compareAndSet(false, true)) {
            return
        }
        closeRequested.set(false)
        thread(name = "textcascade-ws", isDaemon = true) {
            var errorNotified = false
            var sessionExpiredNotified = false
            var closedCode = 1006
            var closedReason = "socket closed"
            try {
                if (!running.get() || closeRequested.get()) return@thread
                openSocket()
                listener.onOpen()
                startWatchdog()
                val closeInfo = readLoop()
                closedCode = closeInfo.first
                closedReason = closeInfo.second
            } catch (error: SessionExpiredException) {
                if (!closeRequested.get()) {
                    sessionExpiredNotified = true
                    listener.onSessionExpired(error)
                }
            } catch (error: Throwable) {
                if (!closeRequested.get()) {
                    errorNotified = true
                    listener.onError(error)
                }
            } finally {
                stopWatchdog()
                closeSocket()
                running.set(false)
                if (!errorNotified && !sessionExpiredNotified && !closeRequested.get()) {
                    listener.onClosed(closedCode, closedReason)
                }
            }
        }
    }

    override fun sendText(text: String) {
        if (!running.get()) {
            throw IOException("WebSocket is not connected")
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        sendFrame(opcode = 0x1, payload = payload)
    }

    override fun sendBytes(bytes: ByteArray) {
        if (!running.get()) {
            throw IOException("WebSocket is not connected")
        }
        sendFrame(opcode = 0x1, payload = bytes)
    }

    override fun close(code: Int, reason: String) {
        closeRequested.set(true)
        running.set(false)
        runCatching {
            val maskKey = ByteArray(4).also(random::nextBytes)
            val closeFrame = WebSocketFrameCodec.encodeCloseFrame(code, reason, maskKey)
            output?.let { out ->
                synchronized(this) {
                    out.write(closeFrame)
                    out.flush()
                }
            }
        }
        stopWatchdog()
        closeSocket()
    }

    private fun openSocket() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        require(secure) { "Only wss:// WebSocket URLs are supported: ${uri.scheme}" }
        val host = uri.host ?: error("WebSocket URL has no host")
        val port = if (uri.port != -1) uri.port else 443

        val rawSocket = socketFactory?.invoke(true, host, port, trustAllCerts) as? SSLSocket
            ?: (TlsFactory.sslSocketFactory(trustAllCerts, pinnedCertSha256).createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        val verifier = hostnameVerifierFactory?.invoke()
            ?: TlsFactory.hostnameVerifier(trustAllCerts, pinnedCertSha256)
            ?: HttpsURLConnection.getDefaultHostnameVerifier()
        if (verifier != null && !verifier.verify(host, rawSocket.session)) {
            rawSocket.close()
            throw SSLPeerUnverifiedException("WebSocket hostname verification failed for host: $host")
        }
        rawSocket.tcpNoDelay = true
        rawSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        socket = rawSocket
        input = BufferedInputStream(rawSocket.getInputStream())
        output = BufferedOutputStream(rawSocket.getOutputStream())

        val wsKey = WebSocketHandshake.generateWebSocketKey(random)
        val request = WebSocketHandshake.buildUpgradeRequest(uri, bearerToken, wsKey)
        writeHandshake(request)

        val response = readHttpHeaders()
        WebSocketHandshake.verifyUpgradeResponse(response, wsKey)

        rawSocket.soTimeout = 0
    }

    @Synchronized
    private fun writeHandshake(request: String) {
        val out = output ?: throw IOException("Socket output stream is null")
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readHttpHeaders(): String {
        val inp = input ?: throw IOException("Socket input stream is null")
        return WebSocketHandshake.readHttpHeadersFromStream(inp)
    }

    private var fragmentedMessageStream: ByteArrayOutputStream? = null

    /** 返回 (closeCode, closeReason)；EOF/异常时由上层兜底 1006。 */
    private fun readLoop(): Pair<Int, String> {
        var closeCode = 1006
        var closeReason = "unexpected EOF"
        while (running.get()) {
            val inp = input ?: break
            val first = inp.read()
            if (first == -1) {
                break
            }
            lastRxTime = System.currentTimeMillis()
            val second = inp.read()
            check(second != -1) { "Unexpected EOF in WebSocket frame" }

            val fin = (first and 0x80) != 0
            val opcode = first and 0x0f
            val masked = (second and 0x80) != 0
            var length = (second and 0x7f).toLong()

            if (length == 126L) {
                length = readUnsignedShort().toLong()
            } else if (length == 127L) {
                length = readLongLength()
            }

            if (length < 0 || length > maxFrameBytes) {
                running.set(false)
                throw IOException("WebSocket frame exceeds transport limit")
            }

            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            try {
                val (nextStream, shouldContinue) = WebSocketFrameCodec.processIncomingFrame(
                    fin = fin,
                    opcode = opcode,
                    payload = payload,
                    currentFragmentedStream = fragmentedMessageStream,
                    onText = listener::onText,
                    onSendPong = { sendFrame(0xA, it) },
                    onClose = { code, reason ->
                        closeCode = code
                        closeReason = reason
                    }
                )
                fragmentedMessageStream = nextStream
                if (!shouldContinue) {
                    running.set(false)
                    break
                }
            } catch (t: Throwable) {
                running.set(false)
                throw t
            }
        }
        return Pair(closeCode, closeReason)
    }

    private fun startWatchdog() {
        lastRxTime = System.currentTimeMillis()
        synchronized(watchdogLock) {
            watchdogFuture?.cancel(false)
            watchdogFuture = sharedWatchdogExecutor.scheduleAtFixedRate({
                if (!running.get()) return@scheduleAtFixedRate
                val elapsed = System.currentTimeMillis() - lastRxTime
                if (elapsed > rxTimeoutMs) {
                    running.set(false)
                    runCatching { socket?.close() }
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

    private fun readUnsignedShort(): Int {
        val inp = input ?: throw IOException("Socket input stream is null")
        val b1 = inp.read()
        val b2 = inp.read()
        check(b1 != -1 && b2 != -1) { "Unexpected EOF in WebSocket frame length" }
        return (b1 shl 8) or b2
    }

    private fun readLongLength(): Long {
        val inp = input ?: throw IOException("Socket input stream is null")
        var result = 0L
        repeat(8) {
            val next = inp.read()
            check(next != -1) { "Unexpected EOF in WebSocket frame length" }
            result = (result shl 8) or next.toLong()
        }
        return result
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val inp = input ?: throw IOException("Socket input stream is null")
            val read = inp.read(target, offset, target.size - offset)
            check(read != -1) { "Unexpected EOF in WebSocket frame payload" }
            offset += read
        }
    }

    @Synchronized
    internal fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        val maskKey = ByteArray(4).also(random::nextBytes)
        WebSocketFrameCodec.writeFrameToStream(out, opcode, payload, maskKey)
    }

    @Synchronized
    internal fun closeSocket() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
