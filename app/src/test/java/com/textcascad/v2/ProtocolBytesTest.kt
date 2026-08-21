/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Bytes 序列化契约：*MessageBytes 输出必须与旧格式化逻辑
 * （jsonEscape 转义 + 字符串拼接 + UTF_8 编码）逐字节一致。
 *
 * 「payload 仅编码一次」由实现结构保证：appendJsonString 逐字符判断后直写
 * UTF-8 字节，不生成转义后的中间 String，也不对 payload 做第二次 toByteArray；
 * 若出现双重转义/双重编码（\u0001 变成 \\u0001，或非 ASCII 变成 C2 81 型
 * mojibake），下面的逐字节断言会失败。
 *
 * 内存对比估算（512KB / 2MB 大文本，量级级结论，非堆测量断言）：
 * - 旧路径（String 版 + 调用方再 toByteArray）：N 字符 payload 峰值同时存活
 *   jsonEscape 中间 String（ART UTF-16 ≈2N B）+ 消息 String（≈2N B）+
 *   UTF-8 数组（ASCII ≈N B / 中文 ≈3N B），合计 ≈5N~7N B；
 * - 新路径（Bytes 版）：仅一个 ByteArrayOutputStream 的 UTF-8 缓冲
 *   （倍增扩容瞬时峰值 ×~1.5），合计 ≈1.5N~4.5N B。
 * - 例：512KB ASCII 文本（N≈52 万）：旧 ≈2.5~3.5MB → 新 ≈0.8~1MB；
 *   2MB 中文文本（N≈70 万）：旧 ≈3.5~5MB → 新 ≈2~3MB。
 * - 结论：调用方切换到 Bytes 版本后，大文本峰值内存约降至原来的 1/2~1/3，
 *   并消除 escaped 中间 String 与消息 String 两次全量拷贝。
 */
class ProtocolBytesTest {

    private val specialValues = listOf(
        "",
        "foobar",
        "中文剪贴板内容·测试",
        "quote\"single'tick\"",
        "back\\slash\\path",
        "ctrl\b\t\n\r\u000C and del\u007F",
        "emoji😀🎉mixed",
        "混\"合\\text😀\u0001\n"
    )

    /** 旧格式化逻辑的字符串级基准：引号包裹 + jsonEscape + UTF_8 编码。 */
    private fun legacyQuotedBytes(value: String): ByteArray =
        ("\"" + Protocol.jsonEscape(value) + "\"").toByteArray(Charsets.UTF_8)

    private val clientId = "cid\"\\中文😀"
    private val clientName = "设备名\t\"quoted\""
    private val clipId = "id-with\\\"special"
    private val hashHex = "85944171f73967e8"
    private val timeUtc = "2026-08-18T12:00:00Z"

    // ---------------- appendJsonString / toUtf8JsonString ----------------

    @Test
    fun toUtf8JsonStringMatchesLegacyQuotedBytes() {
        for (value in specialValues) {
            assertArrayEquals(legacyQuotedBytes(value), Protocol.toUtf8JsonString(value))
        }
    }

    @Test
    fun appendJsonStringWritesQuotedEscapedBytesIntoExistingStream() {
        val out = ByteArrayOutputStream()
        out.write("k:".toByteArray(Charsets.UTF_8))
        Protocol.appendJsonString(out, "a\"b\\c\ndé😀")
        assertArrayEquals(
            "k:\"a\\\"b\\\\c\\ndé😀\"".toByteArray(Charsets.UTF_8),
            out.toByteArray()
        )
    }

    @Test
    fun controlCharEmitsEscapeSequenceBytesNotDoubleEncoded() {
        assertArrayEquals(
            byteArrayOf(
                '"'.code.toByte(), '\\'.code.toByte(), 'u'.code.toByte(),
                '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(),
                '"'.code.toByte()
            ),
            Protocol.toUtf8JsonString("\u0001")
        )
    }

    /** BMP 外字符按标准 UTF-8 四字节编码（非 CESU-8）。 */
    @Test
    fun supplementaryCharEncodedAsFourUtf8Bytes() {
        assertArrayEquals(
            byteArrayOf(
                '"'.code.toByte(),
                0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(),
                '"'.code.toByte()
            ),
            Protocol.toUtf8JsonString("😀")
        )
    }

    /** 未配对代理项与平台 String.toByteArray(UTF_8) 一致替换为 '?'。 */
    @Test
    fun loneSurrogateReplacedWithQuestionMarkLikePlatformEncoder() {
        val expected = "\"?\"".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, Protocol.toUtf8JsonString("\uD800"))
        assertArrayEquals(expected, Protocol.toUtf8JsonString("\uDFFF"))
        assertArrayEquals(
            ("\"" + Protocol.jsonEscape("\uD800尾") + "\"").toByteArray(Charsets.UTF_8),
            Protocol.toUtf8JsonString("\uD800尾")
        )
    }

    // ---------------- 各消息类型 Bytes 与旧格式化逻辑逐字节一致 ----------------

    @Test
    fun helloMessageBytesWithoutSnapshotMatchLegacyFormatting() {
        val expected = (
            "{\"type\":\"hello\",\"clientId\":\"" + Protocol.jsonEscape(clientId) +
                "\",\"clientName\":\"" + Protocol.jsonEscape(clientName) +
                "\",\"lastServerVersion\":7}"
            ).toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            expected,
            Protocol.helloMessageBytes(clientId, clientName, 7L, null)
        )
    }

    @Test
    fun helloMessageBytesWithSnapshotMatchLegacyFormatting() {
        for (payload in specialValues) {
            val snapshot = Protocol.SnapshotPayload(payload, true, hashHex, timeUtc)
            val expected = (
                "{\"type\":\"hello\",\"clientId\":\"" + Protocol.jsonEscape(clientId) +
                    "\",\"clientName\":\"" + Protocol.jsonEscape(clientName) +
                    "\",\"lastServerVersion\":7" +
                    ",\"snapshot\":{\"payload\":\"" + Protocol.jsonEscape(payload) +
                    "\",\"encrypted\":true,\"hash\":\"" + Protocol.jsonEscape(hashHex) +
                    "\",\"localModifiedAtUtc\":\"" + Protocol.jsonEscape(timeUtc) +
                    "\"}}"
                ).toByteArray(Charsets.UTF_8)
            assertArrayEquals(
                expected,
                Protocol.helloMessageBytes(clientId, clientName, 7L, snapshot)
            )
        }
    }

    @Test
    fun clipMessageBytesMatchLegacyFormatting() {
        for (payload in specialValues) {
            val expected = (
                "{\"type\":\"clip\",\"id\":\"" + Protocol.jsonEscape(clipId) +
                    "\",\"payload\":\"" + Protocol.jsonEscape(payload) +
                    "\",\"encrypted\":false,\"hash\":\"" + Protocol.jsonEscape(hashHex) + "\"}"
                ).toByteArray(Charsets.UTF_8)
            assertArrayEquals(
                expected,
                Protocol.clipMessageBytes(clipId, payload, encrypted = false, hashHex = hashHex)
            )
        }
    }

    @Test
    fun pongMessageBytesMatchLegacyFormatting() {
        for (value in specialValues) {
            val expected = (
                "{\"type\":\"pong\",\"clientTimeUtc\":\"" + Protocol.jsonEscape(value) + "\"}"
                ).toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, Protocol.pongMessageBytes(value))
        }
    }

    @Test
    fun loginMessageBytesMatchLegacyFormatting() {
        for (value in specialValues) {
            val expected = (
                "{\"username\":\"" + Protocol.jsonEscape(value) +
                    "\",\"password\":\"" + Protocol.jsonEscape("pwd\\\"中文") + "\"}"
                ).toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, Protocol.loginMessageBytes(value, "pwd\\\"中文"))
        }
    }

    // ---------------- String 版本委托一致性 ----------------

    @Test
    fun stringVersionsEqualDecodedBytesVersions() {
        val snapshot = Protocol.SnapshotPayload("混\"合\\😀\u0002", false, hashHex, timeUtc)
        assertEquals(
            String(Protocol.helloMessageBytes(clientId, clientName, 7L, snapshot), Charsets.UTF_8),
            Protocol.helloMessage(clientId, clientName, 7L, snapshot)
        )
        assertEquals(
            String(Protocol.clipMessageBytes(clipId, "中文\"payload\\", true, hashHex), Charsets.UTF_8),
            Protocol.clipMessage(clipId, "中文\"payload\\", encrypted = true, hashHex = hashHex)
        )
        assertEquals(
            String(Protocol.pongMessageBytes(timeUtc), Charsets.UTF_8),
            Protocol.pongMessage(timeUtc)
        )
        assertEquals(
            String(Protocol.loginMessageBytes("user\"\\中", "pass😀"), Charsets.UTF_8),
            Protocol.loginMessage("user\"\\中", "pass😀")
        )
    }

    // ---------------- 契约样本字节级回归 ----------------

    @Test
    fun bytesVersionsMatchContractSamplesAsUtf8() {
        assertArrayEquals(
            ContractSamples.HELLO_NO_SNAPSHOT.toByteArray(Charsets.UTF_8),
            Protocol.helloMessageBytes(ContractSamples.CLIENT_ID, ContractSamples.CLIENT_NAME, 7L, null)
        )
        assertArrayEquals(
            ContractSamples.HELLO_WITH_SNAPSHOT.toByteArray(Charsets.UTF_8),
            Protocol.helloMessageBytes(
                ContractSamples.CLIENT_ID,
                ContractSamples.CLIENT_NAME,
                7L,
                Protocol.SnapshotPayload(
                    payload = ContractSamples.PAYLOAD_TEXT,
                    encrypted = false,
                    hashHex = ContractSamples.HASH_FOOBAR,
                    localModifiedAtUtc = ContractSamples.TIME_EXAMPLE
                )
            )
        )
        assertArrayEquals(
            ContractSamples.CLIP.toByteArray(Charsets.UTF_8),
            Protocol.clipMessageBytes(
                ContractSamples.CLIP_ID,
                ContractSamples.PAYLOAD_TEXT,
                encrypted = false,
                hashHex = ContractSamples.HASH_FOOBAR
            )
        )
        assertArrayEquals(
            ContractSamples.PONG.toByteArray(Charsets.UTF_8),
            Protocol.pongMessageBytes(ContractSamples.TIME_EXAMPLE)
        )
        assertArrayEquals(
            ContractSamples.LOGIN_REQUEST.toByteArray(Charsets.UTF_8),
            Protocol.loginMessageBytes("user", "pass")
        )
    }
}
