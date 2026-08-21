/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ClipboardAccess
import com.textcascad.v2.engine.OutboundMessageResult
import com.textcascad.v2.engine.OutboundPayloadCodec
import com.textcascad.v2.engine.SyncStateStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出站编码器（纯 JUnit）：hello 快照取舍、clip 全部结果变体、
 * Ready 字节与旧 String 路径（Protocol.clipMessage）逐字节一致。
 */
class OutboundPayloadCodecTest {

    private class FakeStringProvider : StringProvider {
        val calls = mutableListOf<Pair<Int, Array<out Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args)
            return "S$id|${args.joinToString("|") { it.toString() }}"
        }
    }

    private class FakeClipboard(var text: String? = null) : ClipboardAccess {
        override fun readText(): String? = text
        override fun writeText(text: String) {
            this.text = text
        }
    }

    private class Harness(
        val codec: OutboundPayloadCodec,
        val state: SyncStateStore,
        val clipboard: FakeClipboard,
        val strings: FakeStringProvider,
        val statuses: List<String>
    )

    private fun config(
        maxTextBytes: Long = 512_000L,
        localMaxClipboardBytes: Long = 512_000L,
        cipherEnabled: Boolean = false
    ): ClipConfig = ClipConfig(
        serverUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        username = "user",
        token = "tok-1",
        tokenExpiresAtUtc = 0L,
        clientId = "client-1",
        clientName = "Client One",
        derivedKeyBase64 = "",
        maxTextBytes = maxTextBytes,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60,
        lastServerVersion = 0L,
        hashRounds = 1000,
        salt = "salt",
        cipherEnabled = cipherEnabled,
        relaunchOnBoot = false,
        websocketStatusNotification = false,
        localMaxClipboardBytes = localMaxClipboardBytes,
        trustAllCerts = false
    )

    private fun newCodec(
        config: ClipConfig = config(),
        clipboardText: String? = null,
        nowMs: () -> Long = { FIXED_NOW },
        connected: Boolean = true,
        encrypt: (String) -> String? = { it },
        initialServerVersion: Long = 0L
    ): Harness {
        val state = SyncStateStore(initialServerVersion)
        val clipboard = FakeClipboard(clipboardText)
        val strings = FakeStringProvider()
        val statuses = mutableListOf<String>()
        val codec = OutboundPayloadCodec(
            config = config,
            nowMs = nowMs,
            clipboard = clipboard,
            state = state,
            stringProvider = strings,
            isConnected = { connected },
            encrypt = encrypt,
            status = { statuses.add(it) }
        )
        return Harness(codec, state, clipboard, strings, statuses)
    }

    // ---------------- hello ----------------

    @Test
    fun helloOmitsSnapshotWhenClipboardEmpty() {
        val h = newCodec(clipboardText = null, initialServerVersion = 7L)
        val bytes = h.codec.buildHelloMessageBytes()
        assertArrayEquals(
            Protocol.helloMessageBytes(clientId = "client-1", clientName = "Client One", lastServerVersion = 7L, snapshot = null),
            bytes
        )
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun helloOmitsSnapshotWhenClipboardBlank() {
        val h = newCodec(clipboardText = "   ")
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null),
            h.codec.buildHelloMessageBytes()
        )
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun helloCarriesSnapshotWhenClipboardNonEmpty() {
        val h = newCodec(clipboardText = "foobar", nowMs = { FIXED_NOW })
        val expectedSnapshot = Protocol.SnapshotPayload(
            payload = "foobar",
            encrypted = false,
            hashHex = HashUtil.fnv1a64Hex("foobar"),
            localModifiedAtUtc = Protocol.utcNowString(FIXED_NOW)
        )
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, expectedSnapshot),
            h.codec.buildHelloMessageBytes()
        )
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun helloDropsSnapshotWhenEncryptionFails() {
        val h = newCodec(clipboardText = "foobar", encrypt = { null })
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null),
            h.codec.buildHelloMessageBytes()
        )
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun helloDropsSnapshotWhenEncryptedPayloadExceedsServerLimit() {
        val h = newCodec(
            config = config(maxTextBytes = 16L),
            clipboardText = "abc",
            encrypt = { it + "x".repeat(20) }
        )
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null),
            h.codec.buildHelloMessageBytes()
        )
        assertTrue(h.statuses.isEmpty())
    }

    // ---------------- clip：结果变体与分支顺序 ----------------

    @Test
    fun clipSuppressionCheckedBeforeRateLimitAndSilent() {
        val h = newCodec()
        h.state.markRemoteApplied(HashUtil.fnv1a64Hex("foobar"))
        h.state.sendPausedUntilMs = FIXED_NOW + 500_000L
        assertEquals(OutboundMessageResult.Suppressed, h.codec.buildClipMessage("foobar", "test"))
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun clipRateLimitedEmitsStatusBeforeConnectedCheck() {
        val h = newCodec(connected = false)
        h.state.sendPausedUntilMs = FIXED_NOW + 500_000L
        assertEquals(OutboundMessageResult.RateLimited, h.codec.buildClipMessage("text", "test"))
        assertEquals(listOf("S${R.string.status_send_rate_limited}|"), h.statuses)
    }

    @Test
    fun clipNotConnectedEmitsIgnoredStatusWithSource() {
        val h = newCodec(connected = false)
        assertEquals(OutboundMessageResult.NotConnected, h.codec.buildClipMessage("text", "test"))
        assertEquals(listOf("S${R.string.status_ignored_not_connected}|test"), h.statuses)
    }

    @Test
    fun clipTooLargeEmitsClipboardTooLargeStatusOnce() {
        val h = newCodec(config = config(maxTextBytes = 10L))
        assertEquals(OutboundMessageResult.TooLarge, h.codec.buildClipMessage("12345678901", "test"))
        assertEquals(listOf("S${R.string.status_clipboard_too_large}|11"), h.statuses)
    }

    @Test
    fun clipEchoOfLastRemoteSuppressedSilentlyAfterFlagConsumed() {
        val h = newCodec()
        h.state.markRemoteApplied(HashUtil.fnv1a64Hex("foobar"))
        assertEquals(OutboundMessageResult.Suppressed, h.codec.buildClipMessage("foobar", "test"))
        assertEquals(OutboundMessageResult.Suppressed, h.codec.buildClipMessage("foobar", "test"))
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun clipEncryptionFailedEmitsStatus() {
        val h = newCodec(encrypt = { null })
        assertEquals(OutboundMessageResult.EncryptionFailed, h.codec.buildClipMessage("secret", "test"))
        assertEquals(listOf("S${R.string.status_encryption_error}|"), h.statuses)
    }

    @Test
    fun clipServerLimitExceededEmitsPlainTextByteSizeStatus() {
        val h = newCodec(config = config(maxTextBytes = 1024L), encrypt = { it + "x".repeat(200) })
        assertEquals(
            OutboundMessageResult.ServerLimitExceeded,
            h.codec.buildClipMessage("y".repeat(900), "test")
        )
        assertEquals(listOf("S${R.string.status_clipboard_too_large}|900"), h.statuses)
    }

    @Test
    fun clipEncodedBodyBeyondTransportLimitEmitsEncodedTooLarge() {
        val transportLimit = ClipConfig.MAX_TRANSPORT_BYTES
        val h = newCodec(config = config(maxTextBytes = transportLimit, localMaxClipboardBytes = transportLimit))
        val text = "a".repeat((transportLimit - 52L).toInt())
        assertEquals(OutboundMessageResult.TooLarge, h.codec.buildClipMessage(text, "test"))
        assertEquals(listOf("S${R.string.status_encoded_too_large}|"), h.statuses)
    }

    // ---------------- clip：Ready 与旧 String 路径逐字节一致 ----------------

    @Test
    fun clipReadyBytesMatchLegacyStringPath() {
        val h = newCodec()
        val result = h.codec.buildClipMessage("local text", "test")
        assertTrue(result is OutboundMessageResult.Ready)
        val ready = result as OutboundMessageResult.Ready
        assertEquals(HashUtil.fnv1a64Hex("local text"), ready.hashHex)
        val bodyText = String(ready.body, Charsets.UTF_8)
        assertTrue(bodyText.startsWith("{\"type\":\"clip\",\"id\":\""))
        val id = bodyText.substringAfter("\"id\":\"").substringBefore("\"")
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id)
        )
        assertEquals(
            Protocol.clipMessage(id = id, payload = "local text", encrypted = false, hashHex = ready.hashHex),
            bodyText
        )
        assertTrue(h.statuses.isEmpty())
    }

    @Test
    fun clipReadyCarriesEncryptedPayloadAndCipherFlag() {
        val h = newCodec(config = config(cipherEnabled = true), encrypt = { it.reversed() })
        val result = h.codec.buildClipMessage("secret", "test")
        assertTrue(result is OutboundMessageResult.Ready)
        val ready = result as OutboundMessageResult.Ready
        val bodyText = String(ready.body, Charsets.UTF_8)
        assertEquals(
            Protocol.clipMessage(
                id = bodyText.substringAfter("\"id\":\"").substringBefore("\""),
                payload = "terces",
                encrypted = true,
                hashHex = HashUtil.fnv1a64Hex("secret")
            ),
            bodyText
        )
        assertTrue(h.statuses.isEmpty())
    }

    companion object {
        private const val FIXED_NOW = 1_700_000_000_000L
    }
}
