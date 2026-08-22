package com.textcascad.v2.engine

import android.util.Base64
import com.textcascad.v2.Protocol
import com.textcascad.v2.SessionExpiredException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

object WebSocketHandshake {
    const val MAX_HTTP_HANDSHAKE_HEADER_BYTES = 64 * 1024
    private const val WS_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun generateWebSocketKey(random: SecureRandom = SecureRandom()): String {
        val keyBytes = ByteArray(16).also(random::nextBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }

    fun buildUpgradeRequest(
        uri: URI,
        bearerToken: String,
        wsKey: String,
        subprotocol: String = Protocol.SUBPROTOCOL
    ): String {
        val host = uri.host ?: error("WebSocket URL has no host")
        val port = if (uri.port != -1) uri.port else 443
        val path = buildString {
            append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrBlank()) {
                append('?').append(uri.rawQuery)
            }
        }
        return buildString {
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
            append("Sec-WebSocket-Protocol: ").append(subprotocol).append("\r\n")
            append("Authorization: Bearer ").append(bearerToken).append("\r\n")
            append("\r\n")
        }
    }

    @Throws(IOException::class)
    fun readHttpHeadersFromStream(inp: InputStream, maxBytes: Int = MAX_HTTP_HANDSHAKE_HEADER_BYTES): String {
        val buffer = ByteArrayOutputStream(256)
        var last4 = 0
        while (true) {
            val next = inp.read()
            check(next != -1) { "Unexpected EOF during WebSocket upgrade" }
            buffer.write(next)
            if (buffer.size() > maxBytes) {
                throw IOException("WebSocket handshake header too large")
            }
            last4 = ((last4 shl 8) or next) and 0xffffffff.toInt()
            if (last4 == 0x0d0a0d0a) break
        }
        return buffer.toByteArray().toString(Charsets.ISO_8859_1)
    }

    @Throws(IOException::class, SessionExpiredException::class)
    fun verifyUpgradeResponse(response: String, wsKey: String) {
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        val statusCode = statusLine.substringAfter(" ").substringBefore(" ").toIntOrNull() ?: 0

        if (statusCode == 401 || statusCode == 403) {
            throw SessionExpiredException(statusCode)
        }
        if (statusCode == 400) {
            throw IOException("WebSocket subprotocol negotiation failed (HTTP 400): $statusLine")
        }
        check(response.startsWith("HTTP/1.1 101") || response.startsWith("HTTP/1.0 101")) {
            "WebSocket upgrade failed: $statusLine"
        }
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_ACCEPT_GUID).toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        check(response.contains("Sec-WebSocket-Accept: $expectedAccept", ignoreCase = true)) {
            "WebSocket accept header mismatch"
        }
    }
}
