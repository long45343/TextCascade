/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 帧处理 / 握手头读取 / 看门狗超时推导。
 */
class RawWebSocketClientFrameTest {

    private fun headers(bytes: ByteArray): InputStream = ByteArrayInputStream(bytes)

    @Test
    fun readHttpHeadersStopsAtCrlfCrlf() {
        val input = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\nEXTRA"
        val stream = headers(input.toByteArray(Charsets.ISO_8859_1))
        val read = RawWebSocketClient.readHttpHeadersFromStream(stream)
        assertTrue(read.endsWith("\r\n\r\n"))
        assertTrue(read.startsWith("HTTP/1.1 101"))
        // 多余字节仍在流中
        assertEquals('E'.code, stream.read())
    }

    @Test(expected = IOException::class)
    fun readHttpHeadersRejectsOversizedHeader() {
        val huge = ByteArray(RawWebSocketClient.MAX_HTTP_HANDSHAKE_HEADER_BYTES + 16) { 'a'.code.toByte() }
        RawWebSocketClient.readHttpHeadersFromStream(headers(huge))
    }

    @Test
    fun textFrameDispatchesOnText() {
        var received: String? = null
        val (stream, continueLoop) = RawWebSocketClient.processIncomingFrame(
            fin = true,
            opcode = 0x1,
            payload = "hello".toByteArray(),
            currentFragmentedStream = null,
            onText = { received = it },
            onSendPong = {},
            onClose = { _, _ -> }
        )
        assertNull(stream)
        assertTrue(continueLoop)
        assertEquals("hello", received)
    }

    @Test
    fun fragmentedTextFramesAreReassembled() {
        var received: String? = null
        val first = RawWebSocketClient.processIncomingFrame(
            fin = false, opcode = 0x1,
            payload = "foo".toByteArray(), currentFragmentedStream = null,
            onText = { received = it }, onSendPong = {}, onClose = { _, _ -> }
        )
        assertTrue(first.second)
        val second = RawWebSocketClient.processIncomingFrame(
            fin = true, opcode = 0x0,
            payload = "bar".toByteArray(), currentFragmentedStream = first.first,
            onText = { received = it }, onSendPong = {}, onClose = { _, _ -> }
        )
        assertNull(second.first)
        assertTrue(second.second)
        assertEquals("foobar", received)
    }

    @Test
    fun closeFrameParsesCodeAndReason() {
        var closeCode = 0
        var closeReason = ""
        val reasonBytes = "going_away".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((1001 shr 8) and 0xff).toByte()
        payload[1] = (1001 and 0xff).toByte()
        reasonBytes.copyInto(payload, 2)
        val (_, continueLoop) = RawWebSocketClient.processIncomingFrame(
            fin = true, opcode = 0x8,
            payload = payload, currentFragmentedStream = null,
            onText = {}, onSendPong = {},
            onClose = { code, reason -> closeCode = code; closeReason = reason }
        )
        assertEquals(1001, closeCode)
        assertEquals("going_away", closeReason)
        assertFalse(continueLoop)
    }

    @Test(expected = IOException::class)
    fun binaryFramesRejected() {
        RawWebSocketClient.processIncomingFrame(
            fin = true, opcode = 0x2, payload = ByteArray(0),
            currentFragmentedStream = null, onText = {}, onSendPong = {}, onClose = { _, _ -> }
        )
    }

    @Test
    fun watchdogRxTimeoutIsHeartbeatTimeoutPlus10Clamped() {
        // 常规：heartbeatTimeout 60 → 70s
        assertEquals(70_000L, RawWebSocketClient.watchdogRxTimeoutMs(60))
        // 恶意超大值：钳制 300s 上限
        assertEquals(300_000L, RawWebSocketClient.watchdogRxTimeoutMs(1_000_000))
        // 极小值：钳制 15s 下限
        assertEquals(15_000L, RawWebSocketClient.watchdogRxTimeoutMs(1))
    }
}
