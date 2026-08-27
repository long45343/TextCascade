/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.OutboundMessageResult
import com.textcascad.v2.engine.OutboundPayloadCodec
import com.textcascad.v2.engine.SyncStateStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3：Codec 只返回纯结果与状态码，不再回调状态或查询连接。
 */
class OutboundPayloadCodecTest {

    private class FakeClipboard(var text: String? = null) : com.textcascad.v2.engine.ClipboardAccess {
        override fun readText(): String? = text
        override fun writeText(text: String) {
            this.text = text
        }
    }

    private fun config(
        maxTextBytes: Long = 512_000L,
        localMaxClipboardBytes: Long = 512_000L,
        cipherEnabled: Boolean = false
    ): ClipConfig = ClipConfig(
        session = ServerSession("https://srv.example", "user", "tok", 0L, "client-1", "Client One"),
        userPrefs = UserPrefs(
            maxTextBytes,
            10,
            20,
            60,
            0L,
            false,
            false,
            localMaxClipboardBytes
        ),
        cryptoMaterial = CryptoMaterial("", 1000, "salt", cipherEnabled, false)
    )

    private fun newCodec(
        config: ClipConfig = config(),
        clipboardText: String? = null,
        nowMs: () -> Long = { FIXED_NOW },
        encrypt: (String) -> String? = { it },
        initialServerVersion: Long = 0L
    ): Pair<OutboundPayloadCodec, SyncStateStore> {
        val state = SyncStateStore(initialServerVersion)
        return OutboundPayloadCodec(
            config,
            nowMs,
            FakeClipboard(clipboardText),
            state,
            encrypt
        ) to state
    }

    @Test
    fun helloOmitsSnapshotWhenClipboardEmptyOrBlank() {
        val first = newCodec(clipboardText = null, initialServerVersion = 7L).first.buildHelloMessageBytes()
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 7L, null),
            first
        )
        val second = newCodec(clipboardText = "   ").first.buildHelloMessageBytes()
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null),
            second
        )
    }

    @Test
    fun helloCarriesSnapshotWhenNonEmpty() {
        val codec = newCodec(clipboardText = "foobar").first
        val expected = Protocol.SnapshotPayload(
            "foobar",
            false,
            HashUtil.fnv1a64Hex("foobar"),
            Protocol.utcNowString(FIXED_NOW)
        )
        assertArrayEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, expected),
            codec.buildHelloMessageBytes()
        )
    }

    @Test
    fun helloDropsSnapshotWhenEncryptionFailsOrServerLimitExceeded() {
        assertEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null).toList(),
            newCodec(clipboardText = "x", encrypt = { null }).first.buildHelloMessageBytes().toList()
        )
        val limitedConfig = config(maxTextBytes = 2L)
        assertEquals(
            Protocol.helloMessageBytes("client-1", "Client One", 0L, null).toList(),
            newCodec(limitedConfig, clipboardText = "abc").first.buildHelloMessageBytes().toList()
        )
    }

    @Test
    fun suppressionIsCheckedBeforeRateLimitAndRemainsSilent() {
        val (codec, state) = newCodec()
        state.markRemoteApplied(HashUtil.fnv1a64Hex("foobar"))
        state.sendPausedUntilMs = FIXED_NOW + 500_000L
        assertEquals(OutboundMessageResult.Suppressed, codec.buildClipMessage("foobar", "test"))
    }

    @Test
    fun rateLimitedReturnsPureReasonForEngineStatus() {
        val (codec, state) = newCodec()
        state.sendPausedUntilMs = FIXED_NOW + 500_000L
        assertEquals(OutboundMessageResult.RateLimited, codec.buildClipMessage("text", "test"))
    }

    @Test
    fun plainTooLargeAndEchoResultsDoNotFormatStatusInsideCodec() {
        val codec = newCodec(config(maxTextBytes = 10L)).first
        assertEquals(OutboundMessageResult.TooLargePlain, codec.buildClipMessage("12345678901", "test"))

        val (echoCodec, echoState) = newCodec()
        echoState.markRemoteApplied(HashUtil.fnv1a64Hex("foobar"))
        repeat(2) {
            assertEquals(OutboundMessageResult.Suppressed, echoCodec.buildClipMessage("foobar", "test"))
        }
    }

    @Test
    fun encryptionFailedAndTooLargeEncryptedAreDistinctReasons() {
        val failed = newCodec(encrypt = { null }).first
        assertEquals(OutboundMessageResult.EncryptionFailed, failed.buildClipMessage("secret", "test"))

        val expanded = newCodec(config(maxTextBytes = 1024L), encrypt = { it + "x".repeat(200) }).first
        assertEquals(
            OutboundMessageResult.TooLargeEncrypted,
            expanded.buildClipMessage("y".repeat(900), "test")
        )
    }

    @Test
    fun readyBytesMatchLegacyStringPath() {
        val result = newCodec().first.buildClipMessage("local text", "test")
        assertTrue(result is OutboundMessageResult.Ready)
        result as OutboundMessageResult.Ready
        assertEquals(HashUtil.fnv1a64Hex("local text"), result.hashHex)
        val bodyText = String(result.body, Charsets.UTF_8)
        assertTrue(bodyText.startsWith("{\"type\":\"clip\",\"id\":\""))
        val id = bodyText.substringAfter("\"id\":\"").substringBefore("\"")
        assertEquals(
            Protocol.clipMessage(id, "local text", false, result.hashHex),
            bodyText
        )
    }

    companion object {
        private const val FIXED_NOW = 1_700_000_000_000L
    }
}


