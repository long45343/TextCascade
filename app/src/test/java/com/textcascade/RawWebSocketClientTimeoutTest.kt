/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Test

class RawWebSocketClientTimeoutTest {
    private val listener = object : RawWebSocketClient.Listener {
        override fun onOpen() {}
        override fun onText(text: String) {}
        override fun onClosed(reason: String) {}
        override fun onError(error: Throwable) {}
    }

    @Test
    fun heartbeatTimeoutIsClampedToSafeBounds() {
        val client = RawWebSocketClient("ws://example.com/clipsocket", "", listener)

        client.updateRxTimeout(0L)
        assertEquals(45_000L, client.rxTimeoutMs)

        client.updateRxTimeout(30_000L)
        assertEquals(60_000L, client.rxTimeoutMs)

        client.updateRxTimeout(150_000L)
        assertEquals(300_000L, client.rxTimeoutMs)

        client.updateRxTimeout(Long.MAX_VALUE)
        assertEquals(300_000L, client.rxTimeoutMs)
    }
}
