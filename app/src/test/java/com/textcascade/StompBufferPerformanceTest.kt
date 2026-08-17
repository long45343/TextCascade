/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StompBufferPerformanceTest {
    @Test
    fun oneWebSocketTextDispatchesOneThousandFramesInOrder() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = client(onMessage = { bodies += it })
        val input = buildString {
            repeat(1000) { append(StompFrame("MESSAGE", emptyMap(), it.toString()).marshall()) }
        }

        client.onText(input)

        assertEquals(1000, bodies.size)
        assertEquals("0", bodies.first())
        assertEquals("999", bodies.last())
        assertEquals((0 until 1000).map(Int::toString), bodies)
        val metrics = client.receiveBufferMetrics()
        assertTrue(metrics.compactCount <= 1)
        assertTrue(metrics.fullBufferCopyCount <= 1)
        assertTrue(metrics.capacity <= StompClient.MAX_RECEIVE_BUFFER_BYTES)
        client.close()
    }

    @Test
    fun frameSplitAcrossWebSocketTextsIsReassembled() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = client(onMessage = { bodies += it })
        val frame = StompFrame("MESSAGE", emptyMap(), "中文 payload").marshall()
        val split = frame.toByteArray(Charsets.UTF_8).size / 2
        val bytes = frame.toByteArray(Charsets.UTF_8)

        client.onText(bytes.copyOfRange(0, split).toString(Charsets.UTF_8))
        assertTrue(bodies.isEmpty())
        client.onText(bytes.copyOfRange(split, bytes.size).toString(Charsets.UTF_8))

        assertEquals(listOf("中文 payload"), bodies)
        client.close()
        val metrics = client.receiveBufferMetrics()
        assertEquals(0, metrics.readOffset)
        assertEquals(0, metrics.writeOffset)
    }

    @Test
    fun multiFrameAndCrossWebSocketTextFragmentMixedInputsRemainInOrder() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = client(onMessage = { bodies += it })

        val frame1 = StompFrame("MESSAGE", emptyMap(), "frame1").marshall()
        val frame2 = StompFrame("MESSAGE", emptyMap(), "frame2").marshall()
        val frame3 = StompFrame("MESSAGE", emptyMap(), "frame3").marshall()

        // Send frame1 + part of frame2
        val combined = (frame1 + frame2).toByteArray(Charsets.UTF_8)
        val cutPoint = frame1.toByteArray(Charsets.UTF_8).size + 5
        client.onText(combined.copyOfRange(0, cutPoint).toString(Charsets.UTF_8))

        assertEquals(listOf("frame1"), bodies)

        // Send remainder of frame2 + frame3
        val rest = combined.copyOfRange(cutPoint, combined.size).toString(Charsets.UTF_8) + frame3
        client.onText(rest)

        assertEquals(listOf("frame1", "frame2", "frame3"), bodies)
        client.close()
    }

    @Test
    fun malformedFrameIsSkippedAndSubsequentValidFrameDispatches() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = client(onMessage = { bodies += it })

        // Blank command frame triggers exception in parse and is skipped
        val malformedFrame = "\n\nmalformed\u0000"
        val validFrame = StompFrame("MESSAGE", emptyMap(), "valid_body").marshall()

        client.onText(malformedFrame + validFrame)

        assertEquals(listOf("valid_body"), bodies)
        client.close()
    }

    @Test
    fun closeClearsPendingReceiveBuffer() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = client(onMessage = { bodies += it })

        // Send partial frame without closing null byte
        client.onText("MESSAGE\n\npartial")
        assertTrue(bodies.isEmpty())

        // Close client should reset the buffer
        client.close()

        // Send a complete frame after reopening
        client.onText(StompFrame("MESSAGE", emptyMap(), "fresh_start").marshall())
        assertEquals(listOf("fresh_start"), bodies)
        client.close()
    }

    @Test
    fun overflowReportsOnceAndClearsTheBuffer() {
        val errors = AtomicInteger()
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val client = StompClient(
            websocketUrl = "ws://127.0.0.1:8080/ws",
            cookieHeader = "",
            listener = object : StompClient.Listener {
                override fun onConnected() {}
                override fun onMessage(body: String) { bodies += body }
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) { errors.incrementAndGet() }
            }
        )

        client.onText("x".repeat(StompClient.MAX_RECEIVE_BUFFER_BYTES.toInt() + 1))
        client.onText(StompFrame("MESSAGE", emptyMap(), "ok").marshall())

        assertEquals(1, errors.get())
        assertEquals(listOf("ok"), bodies)
        client.close()
    }

    private fun client(onMessage: (String) -> Unit): StompClient = StompClient(
        websocketUrl = "ws://127.0.0.1:8080/ws",
        cookieHeader = "",
        listener = object : StompClient.Listener {
            override fun onConnected() {}
            override fun onMessage(body: String) { onMessage(body) }
            override fun onClosed(reason: String) {}
            override fun onError(error: Throwable) {}
        }
    )
}
