package com.textcascad.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebSocketFrameCodecTest {

    @Test
    fun textFrameEncodeAndProcess() {
        var received: String? = null
        val payload = "hello world".toByteArray(Charsets.UTF_8)
        val (stream, continueLoop) = WebSocketFrameCodec.processIncomingFrame(
            fin = true,
            opcode = 0x1,
            payload = payload,
            currentFragmentedStream = null,
            onText = { received = it },
            onSendPong = {},
            onClose = { _, _ -> }
        )
        assertNull(stream)
        assertTrue(continueLoop)
        assertEquals("hello world", received)
    }

    @Test
    fun fragmentedFramesReassembly() {
        var received: String? = null
        val part1 = WebSocketFrameCodec.processIncomingFrame(
            fin = false,
            opcode = 0x1,
            payload = "Hello, ".toByteArray(Charsets.UTF_8),
            currentFragmentedStream = null,
            onText = { received = it },
            onSendPong = {},
            onClose = { _, _ -> }
        )
        assertTrue(part1.second)
        val part2 = WebSocketFrameCodec.processIncomingFrame(
            fin = true,
            opcode = 0x0,
            payload = "TextCascade!".toByteArray(Charsets.UTF_8),
            currentFragmentedStream = part1.first,
            onText = { received = it },
            onSendPong = {},
            onClose = { _, _ -> }
        )
        assertNull(part2.first)
        assertTrue(part2.second)
        assertEquals("Hello, TextCascade!", received)
    }

    @Test
    fun closeFrameEncodeAndDecode() {
        var receivedCode = 0
        var receivedReason = ""
        val maskKey = byteArrayOf(1, 2, 3, 4)
        val encoded = WebSocketFrameCodec.encodeCloseFrame(1000, "normal", maskKey)
        // 验证 encoded 长度与结构
        assertTrue(encoded.isNotEmpty())

        val reasonBytes = "normal".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((1000 shr 8) and 0xff).toByte()
        payload[1] = (1000 and 0xff).toByte()
        reasonBytes.copyInto(payload, 2)

        val (_, continueLoop) = WebSocketFrameCodec.processIncomingFrame(
            fin = true,
            opcode = 0x8,
            payload = payload,
            currentFragmentedStream = null,
            onText = {},
            onSendPong = {},
            onClose = { code, reason ->
                receivedCode = code
                receivedReason = reason
            }
        )
        assertFalse(continueLoop)
        assertEquals(1000, receivedCode)
        assertEquals("normal", receivedReason)
    }

    @Test(expected = IOException::class)
    fun continuationWithoutInitialFrameThrows() {
        WebSocketFrameCodec.processIncomingFrame(
            fin = true,
            opcode = 0x0,
            payload = "orphaned".toByteArray(),
            currentFragmentedStream = null,
            onText = {},
            onSendPong = {},
            onClose = { _, _ -> }
        )
    }
}
