/*
 * TextCascade Android v2 — Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
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

package com.textcascad.v2

object HashUtil {
    fun fnv1a64(input: String): Long = fnv1a64(input.toByteArray(Charsets.UTF_8))

    fun fnv1a64(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** FNV-1a 64 位无符号小写十六进制（协议 hash 字段格式）。 */
    fun fnv1a64Hex(input: String): String = java.lang.Long.toHexString(fnv1a64(input))

    fun fnv1a64Hex(bytes: ByteArray): String = java.lang.Long.toHexString(fnv1a64(bytes))

    // ------------------------------------------------------------------
    // 无分配 UTF-8 工具
    //
    // 只判长度/算哈希时若走 String.toByteArray(UTF_8)，会为整段文本分配一个
    // 用完即弃的字节数组：512KB 文本 1.1MB、2MB 文本 4.2MB。大剪贴板路径上
    // 这类「只为看一眼大小」的临时垃圾足以触发一次 GC。
    // ------------------------------------------------------------------

    /**
     * 与 `input.toByteArray(Charsets.UTF_8).size` 逐字节等价的长度，零分配。
     *
     * 代理项按 CharsetEncoder 的替换语义计：合法代理对 = 4 字节 UTF-8；
     * 未配对的孤立代理项替换为单字节 '?'。已验证与 JDK/ART 结果一致。
     */
    fun utf8Length(input: CharSequence): Int {
        var length = 0
        var i = 0
        val n = input.length
        while (i < n) {
            val ch = input[i]
            when {
                ch.code < 0x80 -> length += 1
                ch.code < 0x800 -> length += 2
                ch.isHighSurrogate() -> {
                    if (i + 1 < n && input[i + 1].isLowSurrogate()) {
                        length += 4
                        i++
                    } else {
                        length += 1
                    }
                }
                ch.isLowSurrogate() -> length += 1
                else -> length += 3
            }
            i++
        }
        return length
    }

    /**
     * FNV-1a 64 位十六进制，键为文本的 UTF-8 字节序列，零分配。
     * 结果与 [fnv1a64Hex] 的 String 重载一致（未配对代理项同样按 '?' 计入）。
     */
    fun fnv1a64HexOfUtf8(input: CharSequence): String {
        var hash = -0x340d631b7bdddcdbL

        fun mix(byte: Int) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }

        fun mixCodePoint(codePoint: Int) {
            when {
                codePoint < 0x80 -> mix(codePoint)
                codePoint < 0x800 -> {
                    mix(0xC0 or (codePoint ushr 6))
                    mix(0x80 or (codePoint and 0x3F))
                }
                codePoint < 0x10000 -> {
                    mix(0xE0 or (codePoint ushr 12))
                    mix(0x80 or ((codePoint ushr 6) and 0x3F))
                    mix(0x80 or (codePoint and 0x3F))
                }
                else -> {
                    mix(0xF0 or (codePoint ushr 18))
                    mix(0x80 or ((codePoint ushr 12) and 0x3F))
                    mix(0x80 or ((codePoint ushr 6) and 0x3F))
                    mix(0x80 or (codePoint and 0x3F))
                }
            }
        }

        var i = 0
        val n = input.length
        while (i < n) {
            val ch = input[i]
            when {
                ch.isHighSurrogate() ->
                    if (i + 1 < n && input[i + 1].isLowSurrogate()) {
                        mixCodePoint(Character.toCodePoint(ch, input[i + 1]))
                        i++
                    } else {
                        mix('?'.code)
                    }
                ch.isLowSurrogate() -> mix('?'.code)
                else -> mixCodePoint(ch.code)
            }
            i++
        }
        return java.lang.Long.toHexString(hash)
    }
}
