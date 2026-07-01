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

import java.util.concurrent.atomic.AtomicInteger

class StompClient(
    private val websocketUrl: String,
    private val cookieHeader: String,
    private val listener: Listener
) : RawWebSocketClient.Listener {
    interface Listener {
        fun onConnected()
        fun onMessage(body: String)
        fun onClosed(reason: String)
        fun onError(error: Throwable)
    }

    private val subscriptionCounter = AtomicInteger()
    private var socket: RawWebSocketClient? = null

    fun connect() {
        val ws = RawWebSocketClient(websocketUrl, cookieHeader, this)
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
        if (text == "\n") {
            socket?.sendText("\n")
            return
        }
        val frame = StompFrame.parse(text)
        when (frame.command) {
            "CONNECTED" -> listener.onConnected()
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

    private fun sendFrame(
        command: String,
        headers: LinkedHashMap<String, String>,
        body: String = ""
    ) {
        socket?.sendText(StompFrame(command, headers, body).marshall())
    }
}

private data class StompFrame(
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
                append(name).append(':').append(value).append('\n')
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
            val body = if (separator >= 0) withoutNull.substring(separator + 2) else ""
            val lines = headerPart.split('\n')
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon)] = line.substring(colon + 1)
                }
            }
            return StompFrame(lines.firstOrNull().orEmpty().trim(), headers, body)
        }
    }
}
