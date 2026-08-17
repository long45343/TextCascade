/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextCascadeV041HardeningTest {
    @Test
    fun clipboardLimitsUse512KiBDefaultAndCleanHistoricalValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.sharedPreferences.edit()
            .putLong("max_size_bytes", 0L)
            .putLong("local_max_clipboard_bytes", ClipConfig.MAX_CLIPBOARD_BYTES + 1)
            .apply()

        assertEquals(ClipConfig.DEFAULT_MAX_SIZE_BYTES, store.maxSizeBytes)
        assertEquals(ClipConfig.MAX_CLIPBOARD_BYTES, store.localMaxClipboardBytes)
        assertEquals(ClipConfig.MAX_CLIPBOARD_BYTES, ClipConfig.clampClipboardLimit(Long.MAX_VALUE))
    }

    @Test
    fun loginSessionIsCommittedAsOneLogicalSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.clearSession()
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.test",
                websocketUrl = "ws://example.test/clipsocket",
                passwordSha3 = "sha3",
                hashedPasswordBase64 = "key",
                csrfToken = "csrf",
                cookieHeader = "sid=1",
                maxSizeBytes = ClipConfig.MAX_CLIPBOARD_BYTES + 1,
                savedPassword = "password"
            )
        )

        assertTrue(store.hasSession)
        assertEquals("ws://example.test/clipsocket", store.websocketUrl)
        assertEquals(ClipConfig.MAX_CLIPBOARD_BYTES, store.maxSizeBytes)
        assertEquals("password", store.savedEncryptedPassword)

        store.clearSession()
        assertFalse(store.hasSession)
        assertEquals("", store.websocketUrl)
    }

    @Test
    fun failedSessionCommitDoesNotReportLoggedIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context) { false }

        assertFalse(
            store.updateLoginSession(
                SessionSnapshot(
                    serverUrl = "http://example.test",
                    websocketUrl = "ws://example.test/clipsocket",
                    passwordSha3 = "sha3",
                    hashedPasswordBase64 = "key",
                    csrfToken = "csrf",
                    cookieHeader = "sid=1",
                    maxSizeBytes = ClipConfig.DEFAULT_MAX_SIZE_BYTES
                )
            )
        )
        assertFalse(store.hasSession)
    }

    @Test(expected = IllegalArgumentException::class)
    fun websocketFrameLimitRejectsInvalidConstructorValue() {
        RawWebSocketClient(
            url = "ws://127.0.0.1:8080/ws",
            cookieHeader = "",
            listener = emptyWebSocketListener(),
            maxFrameBytes = ClipConfig.MAX_TRANSPORT_BYTES + 1
        )
    }

    @Test
    fun fragmentedMessageChecksInitialFrameBeforeAppending() {
        var stream: java.io.ByteArrayOutputStream? = null
        var thrown = false
        try {
            RawWebSocketClient.processIncomingFrame(
                fin = false,
                opcode = 0x1,
                payload = ByteArray(9),
                currentFragmentedStream = stream,
                onText = {},
                onSendPong = {},
                maxMessageBytes = 8
            )
        } catch (_: java.io.IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun websocketMaskingWorksAcross8192ByteChunkBoundary() {
        val output = ByteArrayOutputStream()
        val client = RawWebSocketClient(
            url = "ws://127.0.0.1:8080/ws",
            cookieHeader = "",
            listener = emptyWebSocketListener()
        )
        val outputField = RawWebSocketClient::class.java.getDeclaredField("output").apply { isAccessible = true }
        outputField.set(client, BufferedOutputStream(output))
        val payload = ByteArray(8193) { (it * 31).toByte() }

        client.sendFrame(0x1, payload)

        val frame = output.toByteArray()
        var index = 2
        val marker = frame[1].toInt() and 0x7f
        val length = when (marker) {
            126 -> ((frame[index++].toInt() and 0xff) shl 8) or (frame[index++].toInt() and 0xff)
            127 -> error("test payload should use 16-bit length")
            else -> marker
        }
        assertEquals(payload.size, length)
        val mask = frame.copyOfRange(index, index + 4)
        index += 4
        val decoded = ByteArray(length)
        for (i in decoded.indices) {
            decoded[i] = (frame[index + i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        assertTrue(payload.contentEquals(decoded))
        client.close()
    }

    @Test
    fun stompReceiveBufferCountsUtf8Bytes() {
        val errors = AtomicInteger()
        val client = StompClient(
            websocketUrl = "ws://127.0.0.1:8080/ws",
            cookieHeader = "",
            listener = object : StompClient.Listener {
                override fun onConnected() {}
                override fun onMessage(body: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) { errors.incrementAndGet() }
            }
        )
        client.onText("中".repeat((ClipConfig.MAX_TRANSPORT_BYTES / 3 + 1).toInt()))
        assertEquals(1, errors.get())
        client.close()
    }

    @Test
    fun stoppedEngineDropsPendingRemoteClipboardWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val connected = AtomicInteger()
        val engine = TextSyncEngine(
            context = context,
            config = ClipConfig.default(context).copy(cipherEnabled = false),
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() { connected.incrementAndGet(); listener.onConnected() }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            clipboardWriter = { writes.add(it) }
        )
        try {
            engine.start()
            waitUntil { connected.get() == 1 }
            engine.onMessage(JsonUtil.clipMessage("remote", "text"))
            engine.stop()
            ShadowLooper.idleMainLooper()
            assertTrue(writes.isEmpty())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun tlsFactoryProvidesBothExplicitPolicies() {
        assertNotNull(TlsFactory.sslSocketFactory(false))
        assertNotNull(TlsFactory.sslSocketFactory(true))
    }

    private fun emptyWebSocketListener() = object : RawWebSocketClient.Listener {
        override fun onOpen() {}
        override fun onText(text: String) {}
        override fun onClosed(reason: String) {}
        override fun onError(error: Throwable) {}
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
        assertTrue(condition())
    }
}
