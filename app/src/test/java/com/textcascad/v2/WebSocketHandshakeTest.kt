package com.textcascad.v2.engine

import com.textcascad.v2.SessionExpiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

@RunWith(RobolectricTestRunner::class)
class WebSocketHandshakeTest {

    @Test
    fun buildUpgradeRequestContainsAllRequiredHeaders() {
        val uri = URI("wss://example.com:8443/api/v1/sync?foo=bar")
        val req = WebSocketHandshake.buildUpgradeRequest(uri, "my-token", "dGhlIHNhbXBsZSBub25jZQ==")
        assertTrue(req.startsWith("GET /api/v1/sync?foo=bar HTTP/1.1\r\n"))
        assertTrue(req.contains("Host: example.com:8443\r\n"))
        assertTrue(req.contains("Upgrade: websocket\r\n"))
        assertTrue(req.contains("Connection: Upgrade\r\n"))
        assertTrue(req.contains("Sec-WebSocket-Version: 13\r\n"))
        assertTrue(req.contains("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"))
        assertTrue(req.contains("Sec-WebSocket-Protocol: textcascade.v1\r\n"))
        assertTrue(req.contains("Authorization: Bearer my-token\r\n"))
        assertTrue(req.endsWith("\r\n\r\n"))
    }

    @Test
    fun verifyUpgradeResponseSucceedsOnValidAccept() {
        val wsKey = "dGhlIHNhbXBsZSBub25jZQ=="
        // RFC 6455 示例: Key="dGhlIHNhbXBsZSBub25jZQ==" -> Accept="s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        WebSocketHandshake.verifyUpgradeResponse(response, wsKey)
    }

    @Test(expected = SessionExpiredException::class)
    fun verifyUpgradeResponseThrowsOn401() {
        val response = "HTTP/1.1 401 Unauthorized\r\n\r\n"
        WebSocketHandshake.verifyUpgradeResponse(response, "dummy")
    }

    @Test(expected = IOException::class)
    fun verifyUpgradeResponseThrowsOn400SubprotocolFailed() {
        val response = "HTTP/1.1 400 Bad Request\r\n\r\n"
        WebSocketHandshake.verifyUpgradeResponse(response, "dummy")
    }

    @Test(expected = IllegalStateException::class)
    fun verifyUpgradeResponseThrowsOnAcceptMismatch() {
        val response = "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong-accept\r\n\r\n"
        WebSocketHandshake.verifyUpgradeResponse(response, "dGhlIHNhbXBsZSBub25jZQ==")
    }

    @Test
    fun readHttpHeadersFromStreamParsesCorrectly() {
        val stream = ByteArrayInputStream("HTTP/1.1 101 Switching Protocols\r\n\r\nEXTRA".toByteArray(Charsets.ISO_8859_1))
        val headers = WebSocketHandshake.readHttpHeadersFromStream(stream)
        assertEquals("HTTP/1.1 101 Switching Protocols\r\n\r\n", headers)
        assertEquals('E'.code, stream.read())
    }
}
