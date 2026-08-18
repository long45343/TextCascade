/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 协议消息序列化必须与契约样本逐字节一致；解析容错。
 */
@RunWith(RobolectricTestRunner::class)
class ProtocolTest {

    // ---------------- 上行序列化（逐字节） ----------------

    @Test
    fun helloWithoutSnapshotMatchesContractSample() {
        val json = Protocol.helloMessage(
            clientId = ContractSamples.CLIENT_ID,
            clientName = ContractSamples.CLIENT_NAME,
            lastServerVersion = 7L,
            snapshot = null
        )
        assertEquals(ContractSamples.HELLO_NO_SNAPSHOT, json)
    }

    @Test
    fun helloWithSnapshotMatchesContractSample() {
        val json = Protocol.helloMessage(
            clientId = ContractSamples.CLIENT_ID,
            clientName = ContractSamples.CLIENT_NAME,
            lastServerVersion = 7L,
            snapshot = Protocol.SnapshotPayload(
                payload = ContractSamples.PAYLOAD_TEXT,
                encrypted = false,
                hashHex = ContractSamples.HASH_FOOBAR,
                localModifiedAtUtc = ContractSamples.TIME_EXAMPLE
            )
        )
        assertEquals(ContractSamples.HELLO_WITH_SNAPSHOT, json)
    }

    @Test
    fun clipMessageMatchesContractSample() {
        val json = Protocol.clipMessage(
            id = ContractSamples.CLIP_ID,
            payload = ContractSamples.PAYLOAD_TEXT,
            encrypted = false,
            hashHex = ContractSamples.HASH_FOOBAR
        )
        assertEquals(ContractSamples.CLIP, json)
    }

    @Test
    fun pongMessageMatchesContractSample() {
        assertEquals(ContractSamples.PONG, Protocol.pongMessage(ContractSamples.TIME_EXAMPLE))
    }

    @Test
    fun loginMessageMatchesContractSample() {
        assertEquals(ContractSamples.LOGIN_REQUEST, Protocol.loginMessage("user", "pass"))
    }

    @Test
    fun jsonEscapeHandlesSpecialCharacters() {
        assertEquals("""a\"b\\c\nd""", Protocol.jsonEscape("a\"b\\c\nd"))
        assertEquals("\\u0001", Protocol.jsonEscape(""))
    }

    // ---------------- 下行解析 ----------------

    @Test
    fun parseWelcomeNullLatest() {
        val message = Protocol.parseServerMessage(ContractSamples.WELCOME_NULL)
        assertTrue(message is Protocol.ServerMessage.Welcome)
        assertNull((message as Protocol.ServerMessage.Welcome).latest)
    }

    @Test
    fun parseWelcomeWithLatest() {
        val message = Protocol.parseServerMessage(ContractSamples.WELCOME_LATEST)
        val welcome = message as Protocol.ServerMessage.Welcome
        val latest = welcome.latest!!
        assertEquals(9L, latest.version)
        assertEquals(ContractSamples.PAYLOAD_TEXT, latest.payload)
        assertEquals(false, latest.encrypted)
        assertEquals(ContractSamples.HASH_FOOBAR, latest.hashHex)
    }

    @Test
    fun parseServerClip() {
        val message = Protocol.parseServerMessage(ContractSamples.SERVER_CLIP) as Protocol.ServerMessage.Clip
        assertEquals(10L, message.version)
        assertEquals(ContractSamples.PAYLOAD_TEXT, message.payload)
        assertEquals(false, message.encrypted)
        assertEquals(ContractSamples.HASH_FOOBAR, message.hashHex)
    }

    @Test
    fun parseClipAck() {
        val message = Protocol.parseServerMessage(ContractSamples.CLIP_ACK) as Protocol.ServerMessage.ClipAck
        assertEquals(ContractSamples.CLIP_ID, message.id)
        assertEquals(11L, message.version)
    }

    @Test
    fun parsePing() {
        val message = Protocol.parseServerMessage(ContractSamples.PING) as Protocol.ServerMessage.Ping
        assertEquals(ContractSamples.TIME_EXAMPLE, message.serverTimeUtc)
    }

    @Test
    fun parseBye() {
        val message = Protocol.parseServerMessage(ContractSamples.BYE) as Protocol.ServerMessage.Bye
        assertEquals("server_shutdown", message.reason)
    }

    @Test
    fun parseError() {
        val message = Protocol.parseServerMessage(ContractSamples.ERROR_TEXT_TOO_LARGE) as Protocol.ServerMessage.Error
        assertEquals("text_too_large", message.code)
        assertEquals("clip exceeds maxTextBytes", message.message)
    }

    @Test
    fun parseToleratesUnknownFields() {
        val message = Protocol.parseServerMessage(ContractSamples.ERROR_UNKNOWN_FIELD_TOLERANT) as Protocol.ServerMessage.Clip
        assertEquals(3L, message.version)
        assertEquals("p", message.payload)
    }

    @Test
    fun parseUnknownTypeReturnsUnknown() {
        val message = Protocol.parseServerMessage("""{"type":"future_thing"}""")
        assertTrue(message is Protocol.ServerMessage.Unknown)
    }

    // ---------------- 时间 ----------------

    @Test
    fun utcNowStringEndsWithZ() {
        val value = Protocol.utcNowString(1770000000123L)
        assertTrue(value.endsWith("Z"))
        assertTrue(value.length >= 20)
    }

    /**
     * 回归：服务端 TryGetUtcDateTime 仅接受 "O"（7 位小数）或秒级
     * yyyy-MM-ddTHH:mm:ssZ；可变小数位（.1Z/.12Z/.123Z）会被判 invalid_message，
     * 导致 pong 被拒 → heartbeat_timeout 断连（2026-08-18 真机事故）。
     */
    @Test
    fun utcNowStringIsSecondPrecisionWithoutFraction() {
        val serverAccepted = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
        // 覆盖会被 Instant.toString 输出为 .1/.12/.123/.000 变体的毫秒值
        val samples = listOf(
            1770000000123L, // .123
            1770000000100L, // .1
            1770000000120L, // .12
            1770000000000L, // 无小数
            1770000000999L  // .999
        )
        for (sample in samples) {
            val value = Protocol.utcNowString(sample)
            assertTrue("unexpected timestamp: $value", serverAccepted.matches(value))
        }
    }

    @Test
    fun parseUtcAcceptsIsoAndEpochMillis() {
        assertEquals(1770009600000L, Protocol.parseUtcToEpochMillis("2026-02-02T05:20:00Z"))
        assertEquals(123456L, Protocol.parseUtcToEpochMillis("123456"))
        assertNull(Protocol.parseUtcToEpochMillis("not-a-time"))
        assertNull(Protocol.parseUtcToEpochMillis(null))
    }

    /** 登录响应 expiresAtUtc 为 DateTimeOffset.ToString("O")（7 位小数 + +00:00 偏移）。 */
    @Test
    fun parseUtcAcceptsServerOffsetForm() {
        val text = "2026-09-17T00:00:00.0000000+00:00"
        val expected = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        assertEquals(expected, Protocol.parseUtcToEpochMillis(text))
    }
}
