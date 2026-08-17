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

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

interface StompTransport {
    fun connect()
    fun subscribe(destination: String)
    fun send(destination: String, body: String)
    fun close()
}

class StompClient(
    private val websocketUrl: String,
    private val cookieHeader: String,
    private val listener: Listener,
    private val trustAllCerts: Boolean = false
) : StompTransport, RawWebSocketClient.Listener {
    interface Listener {
        fun onConnected()
        fun onMessage(body: String)
        fun onClosed(reason: String)
        fun onError(error: Throwable)
        // R2: 会话失效透传
        fun onSessionExpired(error: SessionExpiredException) {}
    }

    private val subscriptionCounter = AtomicInteger()
    @Volatile
    private var socket: RawWebSocketClient? = null

    // R6: 跨 WebSocket 消息的 STOMP 帧拼接缓冲，按读偏移消费并在每个文本消息末尾 compact 一次。
    private var receiveBuffer = ByteArray(INITIAL_RECEIVE_BUFFER_BYTES)
    private var receiveReadOffset = 0
    private var receiveWriteOffset = 0
    private var compactCount = 0
    private var fullBufferCopyCount = 0
    private val receiveBufferLock = Any()

    @Synchronized
    override fun connect() {
        val oldSocket = socket
        oldSocket?.close()
        val ws = RawWebSocketClient(websocketUrl, cookieHeader, this, trustAllCerts)
        socket = ws
        runCatching { ws.connect() }.onFailure {
            if (socket === ws) socket = null
            ws.close()
            throw it
        }
    }

    override fun subscribe(destination: String) {
        sendFrame(
            command = "SUBSCRIBE",
            headers = linkedMapOf(
                "id" to "sub-${subscriptionCounter.getAndIncrement()}",
                "destination" to destination
            )
        )
    }

    override fun send(destination: String, body: String) {
        sendFrame(
            command = "SEND",
            headers = linkedMapOf("destination" to destination),
            body = body
        )
    }

    override fun close() {
        val oldSocket = synchronized(this) {
            val current = socket
            socket = null
            synchronized(receiveBufferLock) { resetReceiveBufferLocked() }
            current
        }
        oldSocket?.close()
    }

    override fun onOpen() {
        sendFrame(
            command = "CONNECT",
            headers = linkedMapOf(
                "host" to websocketUrl,
                "accept-version" to "1.0,1.1",
                "heart-beat" to "0,20000"
            )
        )
    }

    override fun onText(text: String) {
        // 心跳帧（仅 \n 或 \r\n）
        if (text.all { it == '\n' || it == '\r' }) {
            return
        }

        val incomingBytes = text.toByteArray(Charsets.UTF_8)
        val frames = mutableListOf<String>()
        val overflow = synchronized(receiveBufferLock) {
            val pendingBytes = receiveWriteOffset - receiveReadOffset
            if (pendingBytes.toLong() + incomingBytes.size > MAX_RECEIVE_BUFFER_BYTES) {
                resetReceiveBufferLocked()
                true
            } else {
                ensureReceiveCapacityLocked(incomingBytes.size)
                System.arraycopy(incomingBytes, 0, receiveBuffer, receiveWriteOffset, incomingBytes.size)
                receiveWriteOffset += incomingBytes.size
                while (true) {
                    val end = receiveBuffer.indexOf(0, receiveReadOffset, receiveWriteOffset)
                    if (end < 0) break
                    val rawFrame = receiveBuffer.copyOfRange(receiveReadOffset, end).toString(Charsets.UTF_8)
                    receiveReadOffset = end + 1
                    if (rawFrame.isNotBlank()) frames += rawFrame
                }
                compactReceiveBufferLocked()
                false
            }
        }
        if (overflow) {
            listener.onError(IllegalStateException("STOMP receive buffer exceeded size cap"))
            return
        }
        for (rawFrame in frames) {
            // R7: 单个畸形帧只跳过，不拖垮整条连接
            try {
                dispatch(StompFrame.parse(rawFrame))
            } catch (e: Exception) {
                Log.w("StompClient", "Skipped malformed STOMP frame: ${rawFrame.take(100)}", e)
            }
        }
    }

    private fun ensureReceiveCapacityLocked(incomingSize: Int) {
        val required = receiveWriteOffset + incomingSize
        if (required <= receiveBuffer.size) return
        compactReceiveBufferLocked()
        if (receiveWriteOffset + incomingSize <= receiveBuffer.size) return
        var newSize = receiveBuffer.size
        while (newSize < receiveWriteOffset + incomingSize) {
            newSize = minOf(MAX_RECEIVE_BUFFER_BYTES.toInt(), newSize * 2)
            if (newSize == receiveBuffer.size) break
        }
        if (receiveWriteOffset + incomingSize > newSize) {
            resetReceiveBufferLocked()
            throw IllegalStateException("STOMP receive buffer exceeded size cap")
        }
        receiveBuffer = receiveBuffer.copyOf(newSize)
    }

    private fun compactReceiveBufferLocked() {
        if (receiveReadOffset == 0) return
        compactCount++
        val remaining = receiveWriteOffset - receiveReadOffset
        if (remaining > 0) {
            System.arraycopy(receiveBuffer, receiveReadOffset, receiveBuffer, 0, remaining)
            fullBufferCopyCount++
        }
        receiveReadOffset = 0
        receiveWriteOffset = remaining
    }

    private fun resetReceiveBufferLocked() {
        receiveReadOffset = 0
        receiveWriteOffset = 0
    }

    internal fun receiveBufferMetrics(): StompBufferMetrics = synchronized(receiveBufferLock) {
        StompBufferMetrics(
            capacity = receiveBuffer.size,
            readOffset = receiveReadOffset,
            writeOffset = receiveWriteOffset,
            compactCount = compactCount,
            fullBufferCopyCount = fullBufferCopyCount
        )
    }

    private fun ByteArray.indexOf(value: Int, fromIndex: Int, toIndex: Int): Int {
        for (index in fromIndex until toIndex) {
            if (this[index].toInt() and 0xff == value) return index
        }
        return -1
    }

    private fun dispatch(frame: StompFrame) {
        when (frame.command) {
            "CONNECTED" -> {
                // R4: 解析 heart-beat 头协商服务端发送间隔
                val heartBeat = frame.headers["heart-beat"]
                val serverTx = heartBeat?.substringBefore(',')?.toLongOrNull() ?: 0L
                socket?.updateRxTimeout(serverTx)
                listener.onConnected()
            }
            "MESSAGE" -> listener.onMessage(frame.body)
            "ERROR" -> listener.onError(IllegalStateException(frame.body.ifBlank { "STOMP error" }))
        }
    }

    override fun onClosed(reason: String) {
        listener.onClosed(reason)
    }

    override fun onError(error: Throwable) {
        listener.onError(error)
    }

    // R2: 会话失效透传
    override fun onSessionExpired(error: SessionExpiredException) {
        listener.onSessionExpired(error)
    }

    private fun sendFrame(
        command: String,
        headers: LinkedHashMap<String, String>,
        body: String = ""
    ) {
        val currentSocket = socket ?: return
        currentSocket.sendText(StompFrame(command, headers, body).marshall())
    }

    companion object {
        private const val INITIAL_RECEIVE_BUFFER_BYTES = 8192
        internal const val MAX_RECEIVE_BUFFER_BYTES = ClipConfig.MAX_TRANSPORT_BYTES
        @Deprecated("Use MAX_RECEIVE_BUFFER_BYTES")
        internal const val MAX_RECEIVE_BUFFER_CHARS = MAX_RECEIVE_BUFFER_BYTES.toInt()
    }
}

internal data class StompBufferMetrics(
    val capacity: Int,
    val readOffset: Int,
    val writeOffset: Int,
    val compactCount: Int,
    val fullBufferCopyCount: Int
)

internal data class StompFrame(
    val command: String,
    val headers: Map<String, String>,
    val body: String
) {
    fun marshall(): String {
        return buildString {
            append(command).append('\n')
            val mutableHeaders = LinkedHashMap(headers)
            mutableHeaders.remove("content-length")
            for ((name, value) in mutableHeaders) {
                append(escapeHeader(name)).append(':').append(escapeHeader(value)).append('\n')
            }
            append("content-length:").append(body.toByteArray(Charsets.UTF_8).size).append('\n')
            append('\n')
            append(body)
            append('\u0000')
        }
    }

    companion object {
        fun parse(raw: String): StompFrame {
            val withoutNull = raw.trimEnd('\u0000')
            val lfSeparator = withoutNull.indexOf("\n\n")
            val crlfSeparator = withoutNull.indexOf("\r\n\r\n")
            val useCrlf = crlfSeparator >= 0 && (lfSeparator < 0 || crlfSeparator < lfSeparator)
            val separator = if (useCrlf) crlfSeparator else lfSeparator
            val eol = if (useCrlf) "\r\n" else "\n"
            val headerPart = if (separator >= 0) withoutNull.substring(0, separator) else withoutNull
            val bodyStart = if (separator >= 0) separator + (eol.length * 2) else withoutNull.length
            val lines = headerPart.split(eol)
            val headers = LinkedHashMap<String, String>()
            for (rawLine in lines.drop(1)) {
                val line = rawLine.trimEnd('\r')
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = unescapeHeader(line.substring(0, colon))
                    val value = unescapeHeader(line.substring(colon + 1))
                    headers[name] = value
                }
            }
            // F4: content-length 精确截取 body
            val contentLength = headers["content-length"]?.toIntOrNull()
            val body = if (contentLength != null && contentLength >= 0) {
                val bodyBytes = withoutNull.substring(bodyStart).toByteArray(Charsets.UTF_8)
                val clamped = minOf(contentLength, bodyBytes.size)
                bodyBytes.copyOfRange(0, clamped).toString(Charsets.UTF_8)
            } else {
                if (separator >= 0) withoutNull.substring(bodyStart) else ""
            }
            val command = lines.firstOrNull().orEmpty().trim()
            require(command.isNotBlank()) { "STOMP frame command cannot be blank" }
            return StompFrame(command, headers, body)
        }
    }

    // R8: STOMP 1.1 头部转义
    private fun escapeHeader(value: String): String {
        if (value.none { it == '\\' || it == '\r' || it == '\n' || it == ':' }) {
            return value
        }
        return buildString(value.length + 8) {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    ':'  -> append("\\c")
                    else -> append(c)
                }
            }
        }
    }
}

// R8: unescapeHeader 作为顶层私有函数，companion object 和实例方法均可调用
private fun unescapeHeader(value: String): String {
    if ('\\' !in value) return value
    return buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> append('\\')
                    'r'  -> append('\r')
                    'n'  -> append('\n')
                    'c'  -> append(':')
                    else -> append(value[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
