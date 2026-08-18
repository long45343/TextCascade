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

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密（双端约定，服务端对 payload 透明）：
 * - 密钥派生：PBKDF2-HMAC-SHA256，salt = UTF-8(username + "$" + password + "$" + salt)，
 *   迭代 hashRounds，输出 32 字节（AES-256）；PBE 密码为原始密码
 *   （JDK/Conscrypt PBEKeySpec 不允许空 salt，故沿用该结构，仅按新约定调整 salt 构成）。
 * - 载荷：紧凑 JSON {"nonce":"<b64>","ciphertext":"<b64>","tag":"<b64>"};
 *   nonce 生成 16 字节随机，解密兼容 12/16 字节；GCM tag 128 位独立字段；均 Base64。
 */
object CryptoManager {
    private const val AES_GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val NONCE_BYTES = 16
    private val secureRandom = SecureRandom()

    fun derivePasswordKey(
        username: String,
        rawPassword: String,
        salt: String,
        rounds: Int
    ): ByteArray {
        val saltInput = "$username\$$rawPassword\$$salt".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(rawPassword.toCharArray(), saltInput, rounds, AES_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun encrypt(plainText: String, keyBase64: String): EncryptedPayload {
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = ByteArray(NONCE_BYTES)
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
        require(iv.size == 12 || iv.size == 16) { "Unsupported GCM nonce length: ${iv.size}" }
        val ciphertext = Base64.decode(payload.ciphertext, Base64.DEFAULT)
        val tag = Base64.decode(payload.tag, Base64.DEFAULT)
        val encryptedWithTag = ciphertext + tag
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher.doFinal(encryptedWithTag).toString(Charsets.UTF_8)
    }

    /** 序列化为协议载荷 JSON（紧凑、字段顺序 nonce/ciphertext/tag）。 */
    fun encryptedPayloadJson(payload: EncryptedPayload): String {
        return "{\"nonce\":\"${Protocol.jsonEscape(payload.nonce)}\"," +
            "\"ciphertext\":\"${Protocol.jsonEscape(payload.ciphertext)}\"," +
            "\"tag\":\"${Protocol.jsonEscape(payload.tag)}\"}"
    }
}

data class EncryptedPayload(
    val nonce: String,
    val ciphertext: String,
    val tag: String
)
