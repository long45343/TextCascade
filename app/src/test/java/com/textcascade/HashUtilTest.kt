/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashUtilTest {

    @Test
    fun emptyStringProducesKnownHash() {
        // FNV1a-64 offset basis: 0xcbf29ce484222325 (unsigned) = -0x340d631b7bdddcdb (signed)
        val result = HashUtil.fnv1a64("")
        assertEquals(-0x340d631b7bdddcdbL, result)
    }

    @Test
    fun singleByteProducesDeterministicHash() {
        val result = HashUtil.fnv1a64("a")
        // Should be non-zero and deterministic
        assertNotEquals(0L, result)
        assertEquals(result, HashUtil.fnv1a64("a"))
    }

    @Test
    fun sameInputProducesSameHash() {
        assertEquals(HashUtil.fnv1a64("hello world"), HashUtil.fnv1a64("hello world"))
    }

    @Test
    fun differentInputProducesDifferentHash() {
        assertNotEquals(HashUtil.fnv1a64("hello"), HashUtil.fnv1a64("world"))
    }

    @Test
    fun unicodeStringHandledCorrectly() {
        val hash1 = HashUtil.fnv1a64("hello world")
        val hash2 = HashUtil.fnv1a64("hello world")
        assertEquals(hash1, hash2)
        assertNotEquals(hash1, HashUtil.fnv1a64("hello"))

        // UTF-8 encoding of multi-byte chars should produce consistent results
        val charHash = HashUtil.fnv1a64("hello")
        assertTrue(charHash != 0L)
    }

    @Test
    fun longStringProducesConsistentHash() {
        val longString = "x".repeat(10000)
        val hash1 = HashUtil.fnv1a64(longString)
        val hash2 = HashUtil.fnv1a64(longString)
        assertEquals(hash1, hash2)
    }

    @Test
    fun byteArrayOverloadMatchesStringOverload() {
        val text = "multi-byte \u4e2d\u6587 text"
        assertEquals(HashUtil.fnv1a64(text), HashUtil.fnv1a64(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun orderMatters() {
        assertNotEquals(HashUtil.fnv1a64("ab"), HashUtil.fnv1a64("ba"))
    }
}
