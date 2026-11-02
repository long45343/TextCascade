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
    fun rateLimitCheckedBeforeEchoAndBothRemainSilent() {
        val (codec, state) = newCodec()
        state.markRemoteApplied(HashUtil.fnv1a64Hex("foobar"))
        state.sendPausedUntilMs = FIXED_NOW + 500_000L
        // 限流优先于回显抑制（v2.3.5 起抑制仅走 hash 池，不再有一次性旗子）
        assertEquals(OutboundMessageResult.RateLimited, codec.buildClipMessage("foobar", "test"))
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

    /**
     * 场景：用户复制中文/emoji 文本。长度判定与 hash 现在走零分配路径（按 UTF-8
     * 逐码元计算，不生成字节数组），结果必须与「编码后按字节数判定」完全一致，
     * 否则同一段中文会被误判超限、或算出的 hash 与远端去重表对不上。
     */
    @Test
    fun utf8ByteLimitMatchesByteArraySemanticsForNonAsciiText() {
        // 40 字节业务上限
        val limited = config(maxTextBytes = 40L)
        val codec = newCodec(limited).first

        // "中" 是 3 字节 UTF-8：13 个 = 39 字节（应通过），14 个 = 42 字节（应超限）
        val thirteen = "中".repeat(13)
        val fourteen = "中".repeat(14)
        assertEquals(39, thirteen.toByteArray(Charsets.UTF_8).size)
        assertTrue(codec.buildClipMessage(thirteen, "test") is OutboundMessageResult.Ready)
        assertEquals(
            OutboundMessageResult.TooLargePlain,
            codec.buildClipMessage(fourteen, "test")
        )

        // emoji 4 字节：10 个 = 40 字节（边界内），11 个 = 44 字节（超限）
        assertTrue(codec.buildClipMessage("😀".repeat(10), "test") is OutboundMessageResult.Ready)
        assertEquals(
            OutboundMessageResult.TooLargePlain,
            codec.buildClipMessage("😀".repeat(11), "test")
        )
    }

    /**
     * 场景：复制中文后，远端把同样内容回写一次。回显抑制按 hash 匹配，
     * 零分配 hash 必须与编码后计算的 hash 相同，否则会重复回传（回环）。
     */
    @Test
    fun echoSuppressionHitsForNonAsciiTextViaZeroAllocationHash() {
        val (codec, state) = newCodec()
        for (text in listOf("中文内容", "emoji😀混排", "含\"引号\\和\n换行")) {
            state.markRemoteApplied(HashUtil.fnv1a64Hex(text))
            assertEquals(
                "echo must be suppressed for ${text.length} chars",
                OutboundMessageResult.Suppressed,
                codec.buildClipMessage(text, "test")
            )
        }
    }

    companion object {
        private const val FIXED_NOW = 1_700_000_000_000L
    }
}


