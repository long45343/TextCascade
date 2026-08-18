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
}
