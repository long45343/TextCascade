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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class RawWebSocketClient(
    private val url: String,
    private val cookieHeader: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
        fun onError(error: Throwable)
    }

    @Volatile
    private var running = false
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
   private val random = SecureRandom()

    companion object {
        private const val MAX_FRAME_BYTES = 16L * 1024 * 1024
    }

   fun connect() {
        if (running) {
            return
        }
        running = true
        thread(name = "textcascade-ws", isDaemon = true) {
            try {
                openSocket()
                listener.onOpen()
                readLoop()
            } catch (error: Throwable) {
                if (running) {
                    listener.onError(error)
                }
            } finally {
                closeSocket()
                if (running) {
                    listener.onClosed("socket closed")
                }
                running = false
            }
        }
    }

    @Synchronized
    fun sendText(text: String) {
        if (!running) {
            return
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        sendFrame(opcode = 0x1, payload = payload)
    }

    @Synchronized
    fun close() {
        running = false
        runCatching { sendFrame(opcode = 0x8, payload = ByteArray(0)) }
        closeSocket()
    }

    private fun openSocket() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: error("WebSocket URL has no host")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val rawSocket = if (secure) {
            SSLSocketFactory.getDefault().createSocket(host, port) as Socket
        } else {
            Socket(host, port)
        }
        rawSocket.tcpNoDelay = true
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
        output!!.write(request.toByteArray(Charsets.US_ASCII))
        output!!.flush()

        val response = readHttpHeaders()
        check(response.startsWith("HTTP/1.1 101") || response.startsWith("HTTP/1.0 101")) {
            "WebSocket upgrade failed: ${response.lineSequence().firstOrNull().orEmpty()}"
        }
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        check(response.contains("Sec-WebSocket-Accept: $expectedAccept", ignoreCase = true)) {
            "WebSocket accept header mismatch"
        }
    }

    private fun readHttpHeaders(): String {
        val bytes = ArrayList<Byte>()
        var last4 = 0
        while (true) {
            val next = input!!.read()
            check(next != -1) { "Unexpected EOF during WebSocket upgrade" }
            bytes.add(next.toByte())
            last4 = ((last4 shl 8) or next) and 0xffffffff.toInt()
            if (last4 == 0x0d0a0d0a) {
                break
            }
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun readLoop() {
        while (running) {
            val first = input!!.read()
            if (first == -1) {
                break
            }
            val second = input!!.read()
            check(second != -1) { "Unexpected EOF in WebSocket frame" }
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
                throw java.io.IOException("WebSocket frame too large: $length bytes")
            }
           val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x1 -> listener.onText(payload.toString(Charsets.UTF_8))
                0x8 -> {
                    running = false
                    break
                }
                0x9 -> sendFrame(0xA, payload)
                0xA -> Unit
            }
        }
    }

    private fun readUnsignedShort(): Int {
        val b1 = input!!.read()
        val b2 = input!!.read()
        check(b1 != -1 && b2 != -1) { "Unexpected EOF in WebSocket frame length" }
        return (b1 shl 8) or b2
    }

    private fun readLongLength(): Long {
        var result = 0L
        repeat(8) {
            val next = input!!.read()
            check(next != -1) { "Unexpected EOF in WebSocket frame length" }
            result = (result shl 8) or next.toLong()
        }
        return result
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input!!.read(target, offset, target.size - offset)
            check(read != -1) { "Unexpected EOF in WebSocket frame payload" }
            offset += read
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
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

    private fun closeSocket() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
