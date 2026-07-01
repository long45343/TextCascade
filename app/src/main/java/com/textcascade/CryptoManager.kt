/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
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

package com.textcascade

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val AES_GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val IV_BYTES = 16
    private val secureRandom = SecureRandom()

    fun sha3_512LowercaseHex(input: String): String {
        val digest = try {
            java.security.MessageDigest.getInstance("SHA3-512")
                .digest(input.toByteArray(Charsets.UTF_8))
        } catch (_: java.security.NoSuchAlgorithmException) {
            Sha3.sha3_512(input.toByteArray(Charsets.UTF_8))
        }
       return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
   }

    fun derivePasswordKey(
        username: String,
        rawPassword: String,
        saltSuffix: String,
        rounds: Int
    ): ByteArray {
        val salt = "$username$rawPassword$saltSuffix".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(rawPassword.toCharArray(), salt, rounds, AES_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun encrypt(plainText: String, keyBase64: String): EncryptedPayload {
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = ByteArray(IV_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        val encryptedWithTag = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ciphertext = encryptedWithTag.copyOfRange(0, encryptedWithTag.size - 16)
        val tag = encryptedWithTag.copyOfRange(encryptedWithTag.size - 16, encryptedWithTag.size)
        return EncryptedPayload(
            nonce = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            tag = Base64.encodeToString(tag, Base64.NO_WRAP)
        )
    }

    fun decrypt(payload: EncryptedPayload, keyBase64: String): String {
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = Base64.decode(payload.nonce, Base64.DEFAULT)
        val ciphertext = Base64.decode(payload.ciphertext, Base64.DEFAULT)
        val tag = Base64.decode(payload.tag, Base64.DEFAULT)
        val encryptedWithTag = ciphertext + tag
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher.doFinal(encryptedWithTag).toString(Charsets.UTF_8)
    }
}

data class EncryptedPayload(
    val nonce: String,
    val ciphertext: String,
    val tag: String
)

private object Sha3 {
    private const val SHA3_512_RATE_BYTES = 72

    private val roundConstants = longArrayOf(
        0x0000000000000001UL.toLong(),
        0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(),
        0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(),
        0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(),
        0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(),
        0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(),
        0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(),
        0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(),
        0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008008UL.toLong()
    )

    private val rotationOffsets = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    )

    fun sha3_512(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        while (offset + SHA3_512_RATE_BYTES <= input.size) {
            absorbBlock(state, input, offset)
            keccakF1600(state)
            offset += SHA3_512_RATE_BYTES
        }

        val block = ByteArray(SHA3_512_RATE_BYTES)
        val remaining = input.size - offset
        input.copyInto(block, destinationOffset = 0, startIndex = offset, endIndex = input.size)
        block[remaining] = 0x06
        block[SHA3_512_RATE_BYTES - 1] = (block[SHA3_512_RATE_BYTES - 1].toInt() or 0x80).toByte()
        absorbBlock(state, block, 0)
        keccakF1600(state)

        val output = ByteArray(64)
        var outputOffset = 0
        var lane = 0
        while (outputOffset < output.size) {
            val value = state[lane]
            for (i in 0 until 8) {
                if (outputOffset == output.size) {
                    break
                }
                output[outputOffset++] = ((value ushr (8 * i)) and 0xffL).toByte()
            }
            lane++
            if (lane * 8 == SHA3_512_RATE_BYTES && outputOffset < output.size) {
                keccakF1600(state)
                lane = 0
            }
        }
        return output
    }

    private fun absorbBlock(state: LongArray, block: ByteArray, offset: Int) {
        for (lane in 0 until SHA3_512_RATE_BYTES / 8) {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((block[offset + lane * 8 + i].toLong() and 0xffL) shl (8 * i))
            }
            state[lane] = state[lane] xor value
        }
    }

    private fun keccakF1600(state: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)

        for (round in 0 until 24) {
            for (x in 0 until 5) {
                c[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                d[x] = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] = state[x + 5 * y] xor d[x]
                }
            }

            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val source = x + 5 * y
                    val targetX = y
                    val targetY = (2 * x + 3 * y) % 5
                    b[targetX + 5 * targetY] = java.lang.Long.rotateLeft(state[source], rotationOffsets[source])
                }
            }

            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] = b[x + 5 * y] xor (b[((x + 1) % 5) + 5 * y].inv() and b[((x + 2) % 5) + 5 * y])
                }
            }

            state[0] = state[0] xor roundConstants[round]
        }
    }
}
