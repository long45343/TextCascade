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

class StompClient(
    private val websocketUrl: String,
    private val cookieHeader: String,
    private val listener: Listener,
    private val trustAllCerts: Boolean = false
) : RawWebSocketClient.Listener {
    interface Listener {
        fun onConnected()
        fun onMessage(body: String)
        fun onClosed(reason: String)
        fun onError(error: Throwable)
        // R2: 会话失效透传
        fun onSessionExpired(error: SessionExpiredException) {}
    }

    private val subscriptionCounter = AtomicInteger()
    private var socket: RawWebSocketClient? = null

    // R6: 跨 WebSocket 消息的 STOMP 帧拼接缓冲
    private val receiveBuffer = StringBuilder()
    private val receiveBufferLock = Any()

    fun connect() {
        val ws = RawWebSocketClient(websocketUrl, cookieHeader, this, trustAllCerts)
        socket = ws
        ws.connect()
    }

    fun subscribe(destination: String) {
        sendFrame(
            command = "SUBSCRIBE",
            headers = linkedMapOf(
                "id" to "sub-${subscriptionCounter.getAndIncrement()}",
                "destination" to destination
            )
        )
    }

    fun send(destination: String, body: String) {
        sendFrame(
            command = "SEND",
            headers = linkedMapOf("destination" to destination),
            body = body
        )
    }

    fun close() {
        socket?.close()
        socket = null
        synchronized(receiveBufferLock) { receiveBuffer.setLength(0) }
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

        synchronized(receiveBufferLock) {
            receiveBuffer.append(text)
            // R6: 缓冲上限保护
            if (receiveBuffer.length > MAX_RECEIVE_BUFFER_CHARS) {
                receiveBuffer.setLength(0)
                listener.onError(IllegalStateException("STOMP receive buffer exceeded size cap"))
                return
            }
            // 逐个解析以 \0 分隔的完整帧
            while (true) {
                val end = receiveBuffer.indexOf('\u0000')
                if (end < 0) break
                val rawFrame = receiveBuffer.substring(0, end)
                receiveBuffer.delete(0, end + 1)
                if (rawFrame.isBlank()) continue
                // R7: 单个畸形帧只跳过，不拖垮整条连接
                try {
                    val frame = StompFrame.parse(rawFrame)
                    dispatch(frame)
                } catch (e: Exception) {
                    Log.w("StompClient", "Skipped malformed STOMP frame: ${rawFrame.take(100)}", e)
                }
            }
        }
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
        socket?.sendText(StompFrame(command, headers, body).marshall())
    }

    companion object {
        // R6: STOMP 帧级累积缓冲上限（2M chars）
        internal const val MAX_RECEIVE_BUFFER_CHARS = 2 * 1024 * 1024
    }
}

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
            val separator = withoutNull.indexOf("\n\n")
            val headerPart = if (separator >= 0) withoutNull.substring(0, separator) else withoutNull
            val bodyStart = if (separator >= 0) separator + 2 else withoutNull.length
            val lines = headerPart.split('\n')
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
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
            return StompFrame(lines.firstOrNull().orEmpty().trim(), headers, body)
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
