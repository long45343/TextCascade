/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FNV-1a 64 官方测试向量 + 协议 hex 格式。
 * 向量来源：https://datatracker.ietf.org/doc/html/draft-eastlake-fnv（FNV 测试向量）。
 */
class HashUtilTest {

    @Test
    fun fnv1a64EmptyString() {
        assertEquals(0xcbf29ce484222325UL.toLong(), HashUtil.fnv1a64(""))
    }

    @Test
    fun fnv1a64SingleA() {
        assertEquals(0xaf63dc4c8601ec8cUL.toLong(), HashUtil.fnv1a64("a"))
    }

    @Test
    fun fnv1a64Foobar() {
        assertEquals(0x85944171f73967e8UL.toLong(), HashUtil.fnv1a64("foobar"))
    }

    @Test
    fun hexIsLowercaseUnsigned() {
        assertEquals("85944171f73967e8", HashUtil.fnv1a64Hex("foobar"))
        assertEquals("cbf29ce484222325", HashUtil.fnv1a64Hex(""))
        // 负值（高位为 1）必须输出无符号形式且为小写
        val hex = HashUtil.fnv1a64Hex("different input")
        assertEquals(16, hex.length)
        assertEquals(hex, hex.lowercase())
    }

    // ------------------------------------------------------------------
    // 无分配 UTF-8 工具：必须与 String.toByteArray(UTF_8) 完全等价
    // 场景：复制大段文本时只判长度/算哈希，不再为此生成整段字节数组
    // ------------------------------------------------------------------

    /** 覆盖 ASCII、CJK、emoji（代理对）、控制字符、孤立代理项与混合文本。 */
    private val utf8Corpus = listOf(
        "",
        "a",
        "foobar",
        "中文剪贴板内容·测试",
        "quote\"single'tick\"",
        "back\\slash\\path",
        "ctrl\b\t\n\r\u000C and del\u007F",
        "emoji😀🎉mixed",
        "混\"合\\text😀\u0001\n",
        "\uD800",           // 孤立高代理项 → '?'
        "\uDFFF",           // 孤立低代理项 → '?'
        "\uD800尾",         // 高代理项 + CJK
        "尾\uDFFF",         // CJK + 低代理项
        "\uD800\uD800",     // 两个高代理项
        "\uD83D\uDE00",     // 合法代理对（😀）
        "a\u0000b",
        "\uFFFF"            // 非字符 BMP（3 字节 UTF-8）
    )

    @Test
    fun utf8LengthMatchesToByteArrayForAllCorpusCases() {
        for (value in utf8Corpus) {
            assertEquals(
                "utf8Length mismatch for ${value.length} chars",
                value.toByteArray(Charsets.UTF_8).size,
                HashUtil.utf8Length(value)
            )
        }
    }

    @Test
    fun utf8LengthMatchesToByteArrayForLargeMixedText() {
        // 场景：接近 2MB 上限的混合文本，逐段核对分块边界的代理对处理
        val builder = StringBuilder()
        repeat(20_000) { builder.append("中文😀abc") }
        val text = builder.toString()
        assertEquals(text.toByteArray(Charsets.UTF_8).size, HashUtil.utf8Length(text))
    }

    @Test
    fun fnv1a64HexOfUtf8MatchesByteArrayOverload() {
        // 场景：出站判断回显/去重时用文本直接算 hash，结果必须与编码后算的一致，
        // 否则同一条内容会在「本地发送」与「远端回显」两条路径上算出不同 hash
        for (value in utf8Corpus) {
            assertEquals(
                "hash mismatch for ${value.length} chars",
                HashUtil.fnv1a64Hex(value.toByteArray(Charsets.UTF_8)),
                HashUtil.fnv1a64HexOfUtf8(value)
            )
        }
    }

    @Test
    fun fnv1a64HexOfUtf8MatchesOfficialVectors() {
        // 官方向量同样必须成立（等价实现不能只是自洽）
        assertEquals("85944171f73967e8", HashUtil.fnv1a64HexOfUtf8("foobar"))
        assertEquals("cbf29ce484222325", HashUtil.fnv1a64HexOfUtf8(""))
        assertEquals("af63dc4c8601ec8c", HashUtil.fnv1a64HexOfUtf8("a"))
    }
}
