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
}
