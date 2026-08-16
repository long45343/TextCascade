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

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class RawWebSocketClient(
    private val url: String,
    private val cookieHeader: String,
    private val listener: Listener,
    private val trustAllCerts: Boolean = false
) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
        fun onError(error: Throwable)
        // R2: 会话失效回调（401/403），不触发 onError
        fun onSessionExpired(error: SessionExpiredException) {}
    }

    @Volatile
    private var running = false
    @Volatile
    private var closeRequested = false
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var input: BufferedInputStream? = null
    @Volatile
    private var output: BufferedOutputStream? = null
    private val random = SecureRandom()

    // R4: 半开连接看门狗
    @Volatile
    private var lastRxTime: Long = 0
    private var watchdogFuture: ScheduledFuture<*>? = null
    private val watchdogLock = Any()
    @Volatile
    var rxTimeoutMs: Long = DEFAULT_RX_TIMEOUT_MS
        private set

    companion object {
        const val MAX_FRAME_BYTES = 16L * 1024 * 1024
        const val MAX_HTTP_HANDSHAKE_HEADER_BYTES = 64 * 1024
        // R5: 握手超时
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 15_000
        // R4: 看门狗
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val DEFAULT_RX_TIMEOUT_MS = 120_000L
        private const val MINIMUM_RX_TIMEOUT_MS = 45_000L

        internal val sharedWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "textcascade-watchdog").apply { isDaemon = true }
        }

        // R16 / RW3-T7: 纯输入流握手头读取逻辑
        @Throws(IOException::class)
        internal fun readHttpHeadersFromStream(inp: InputStream): String {
            val buffer = ByteArrayOutputStream(256)
            var last4 = 0
            while (true) {
                val next = inp.read()
                check(next != -1) { "Unexpected EOF during WebSocket upgrade" }
                buffer.write(next)
                if (buffer.size() > MAX_HTTP_HANDSHAKE_HEADER_BYTES) {
                    throw IOException("WebSocket handshake header too large")
                }
                last4 = ((last4 shl 8) or next) and 0xffffffff.toInt()
                if (last4 == 0x0d0a0d0a) break
            }
            return buffer.toByteArray().toString(Charsets.ISO_8859_1)
        }

        // RB12 / RW3-T8: 纯逻辑帧处理器
        @Throws(IOException::class)
        internal fun processIncomingFrame(
            fin: Boolean,
            opcode: Int,
            payload: ByteArray,
            currentFragmentedStream: ByteArrayOutputStream?,
            onText: (String) -> Unit,
            onSendPong: (ByteArray) -> Unit,
            maxMessageBytes: Long = MAX_FRAME_BYTES
        ): Pair<ByteArrayOutputStream?, Boolean> {
            when (opcode) {
                0x0 -> {
                    val stream = currentFragmentedStream
                        ?: throw IOException("Received WebSocket continuation frame without initial frame")
                    if (stream.size() + payload.size > maxMessageBytes) {
                        throw IOException("Fragmented WebSocket message exceeded size limit")
                    }
                    stream.write(payload)
                    return if (fin) {
                        val fullText = stream.toByteArray().toString(Charsets.UTF_8)
                        onText(fullText)
                        Pair(null, true)
                    } else {
                        Pair(stream, true)
                    }
                }
                0x1 -> {
                    if (currentFragmentedStream != null) {
                        throw IOException("Received new WebSocket text frame before previous fragmented message completed")
                    }
                    return if (fin) {
                        onText(payload.toString(Charsets.UTF_8))
                        Pair(null, true)
                    } else {
                        val newStream = ByteArrayOutputStream().apply {
                            write(payload)
                        }
                        Pair(newStream, true)
                    }
                }
                0x2 -> {
                    throw IOException("Binary WebSocket frames (0x2) are not supported")
                }
                0x8 -> {
                    check(fin) { "Control frames cannot be fragmented" }
                    check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                    return Pair(currentFragmentedStream, false)
                }
                0x9 -> {
                    check(fin) { "Control frames cannot be fragmented" }
                    check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                    onSendPong(payload)
                    return Pair(currentFragmentedStream, true)
                }
                0xA -> {
                    check(fin) { "Control frames cannot be fragmented" }
                    check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                    return Pair(currentFragmentedStream, true)
                }
                else -> {
                    throw IOException("Unsupported WebSocket opcode: $opcode")
                }
            }
        }
    }

    fun connect() {
        if (running) {
            return
        }
        running = true
        closeRequested = false
        thread(name = "textcascade-ws", isDaemon = true) {
            var errorNotified = false
            var sessionExpiredNotified = false
            try {
                openSocket()
                listener.onOpen()
                startWatchdog()
                readLoop()
            } catch (error: SessionExpiredException) {
                // F1: 会话过期只发专门回调，不再追加 onClosed；主动关闭时跳过
                if (!closeRequested) {
                    sessionExpiredNotified = true
                    listener.onSessionExpired(error)
                }
            } catch (error: Throwable) {
                // F1: 异常只发 onError，不再追加 onClosed；主动关闭时跳过
                if (!closeRequested) {
                    errorNotified = true
                    listener.onError(error)
                }
            } finally {
                stopWatchdog()
                closeSocket()
                running = false
                // F1: 主动关闭不回调；异常/会话过期已通知上层；仅正常关闭才回调 onClosed
                if (!errorNotified && !sessionExpiredNotified && !closeRequested) {
                    listener.onClosed("socket closed")
                }
            }
        }
    }

    fun sendText(text: String) {
        if (!running) {
            // R10: 不再静默返回，抛异常让上层知道发送失败
            throw IOException("WebSocket is not connected")
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        sendFrame(opcode = 0x1, payload = payload)
    }

    fun close() {
        // F1: 主动关闭不触发上层回调，避免误触发重连
        closeRequested = true
        running = false
        runCatching { sendFrame(opcode = 0x8, payload = ByteArray(0)) }
        stopWatchdog()
        closeSocket()
    }

    // R4: 由 StompClient 在收到 CONNECTED 帧后调用
    fun updateRxTimeout(serverHeartbeatMs: Long) {
        val negotiated = maxOf(serverHeartbeatMs, 20_000L)
        rxTimeoutMs = maxOf(2L * negotiated, MINIMUM_RX_TIMEOUT_MS)
    }

    private fun openSocket() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: error("WebSocket URL has no host")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80

        // R5: 设置连接超时，防止 DNS/TCP 挂起
        val rawSocket = if (secure) {
            val factory = if (trustAllCerts) createTrustAllFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sslSocket
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }
        rawSocket.tcpNoDelay = true
        // R5: 握手阶段设置读取超时
        rawSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        socket = rawSocket
        input = BufferedInputStream(rawSocket.getInputStream())
        output = BufferedOutputStream(rawSocket.getOutputStream())

        val path = buildString {
            append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrBlank()) {
                append('?').append(uri.rawQuery)
            }
        }
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host)
            if (uri.port != -1) {
                append(':').append(port)
            }
            append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            if (cookieHeader.isNotBlank()) {
                append("Cookie: ").append(cookieHeader).append("\r\n")
            }
            append("\r\n")
        }
        writeHandshake(request)

        val response = readHttpHeaders()
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        val statusCode = statusLine.substringAfter(" ").substringBefore(" ").toIntOrNull() ?: 0

        // R2: 检测 401/403 会话过期
        if (statusCode == 401 || statusCode == 403) {
            throw SessionExpiredException(statusCode)
        }

        check(response.startsWith("HTTP/1.1 101") || response.startsWith("HTTP/1.0 101")) {
            "WebSocket upgrade failed: $statusLine"
        }
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        check(response.contains("Sec-WebSocket-Accept: $expectedAccept", ignoreCase = true)) {
            "WebSocket accept header mismatch"
        }

        // R5: 握手成功后重置 soTimeout，由看门狗负责半开检测
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
        return readHttpHeadersFromStream(inp)
    }

    private var fragmentedMessageStream: ByteArrayOutputStream? = null

    private fun readLoop() {
        while (running) {
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

            if (length > MAX_FRAME_BYTES) {
                running = false
                throw IOException("WebSocket frame too large: $length bytes")
            }

            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            try {
                val (nextStream, shouldContinue) = processIncomingFrame(
                    fin = fin,
                    opcode = opcode,
                    payload = payload,
                    currentFragmentedStream = fragmentedMessageStream,
                    onText = listener::onText,
                    onSendPong = { sendFrame(0xA, it) }
                )
                fragmentedMessageStream = nextStream
                if (!shouldContinue) {
                    running = false
                    break
                }
            } catch (t: Throwable) {
                running = false
                throw t
            }
        }
    }

    // R4: 半开连接看门狗
    private fun startWatchdog() {
        lastRxTime = System.currentTimeMillis()
        synchronized(watchdogLock) {
            watchdogFuture?.cancel(false)
            watchdogFuture = sharedWatchdogExecutor.scheduleAtFixedRate({
                if (!running) return@scheduleAtFixedRate
                val elapsed = System.currentTimeMillis() - lastRxTime
                if (elapsed > rxTimeoutMs) {
                    running = false
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
        out.write(0x80 or opcode)
        val maskKey = ByteArray(4).also(random::nextBytes)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size <= 65535 -> {
                out.write(0x80 or 126)
                out.write((payload.size ushr 8) and 0xff)
                out.write(payload.size and 0xff)
            }
            else -> {
                out.write(0x80 or 127)
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((size ushr shift) and 0xff).toInt())
                }
            }
        }
        out.write(maskKey)
        for (i in payload.indices) {
            out.write(payload[i].toInt() xor maskKey[i % 4].toInt())
        }
        out.flush()
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

    // F5: 信任所有证书的 SSL 工厂（仅用于内网自签证书场景）
    private fun createTrustAllFactory(): SSLSocketFactory {
        val trustAllManager = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllManager, SecureRandom())
        return sslContext.socketFactory
    }
}
