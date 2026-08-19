/*
 * TextCascade Android v2 - Native clipboard sync client
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

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore + AES-256-GCM 加密 SharedPreferences 中的敏感字段。
 *
 * 加密格式: "aks:" + Base64(iv) + ":" + Base64(ciphertext+tag)
 * 无前缀的值视为存量明文，由调用方负责迁移。
 */
object EncryptedPrefs {

    private const val KEY_ALIAS = "textcascade_secret_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "aks:"

    private val key: SecretKey by lazy { getOrCreateKey() }

    private fun getOrCreateKey(): SecretKey {
        return try {
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step1 KeyStore.getInstance")
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step2 keyStore.load")
            keyStore.load(null)
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step3 keyStore.getKey")
            val existing = keyStore.getKey(KEY_ALIAS, null)
            if (existing != null) {
                android.util.Log.i("EncryptedPrefs", "getOrCreateKey: existing key found")
                return existing as SecretKey
            }
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step4 KeyGenerator.getInstance")
            val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step5 KeyGenParameterSpec")
            generator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: step6 generateKey")
            val generated = generator.generateKey()
            android.util.Log.i("EncryptedPrefs", "getOrCreateKey: success")
            generated
        } catch (e: Throwable) {
            android.util.Log.e("EncryptedPrefs", "getOrCreateKey FAILED", e)
            throw e
        }
    }

    /** 加密明文；失败返回 null（调用方负责降级）。 */
    fun tryEncrypt(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        if (plaintext.startsWith(PREFIX)) return plaintext
        return runCatching {
            // Android Keystore (API 29+) 要求 GCM 模式下由 Keystore 自动生成 IV，
            // 不允许调用方提供 IV（否则抛 InvalidAlgorithmParameterException）。
            // 加密后从 cipher.iv 取出 IV 与密文一起存储。
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            PREFIX +
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.onFailure { e ->
            android.util.Log.e("EncryptedPrefs", "tryEncrypt FAILED for plaintext(len=${plaintext.length})", e)
        }.getOrNull()
    }

    /** 加密明文；失败时返回原文（降级到明文存储）。 */
    fun encrypt(plaintext: String): String {
        return tryEncrypt(plaintext) ?: plaintext
    }

    /** 解密；无前缀原样返回（迁移路径）。 */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return tryDecryptInternal(stored)
            ?: throw java.security.GeneralSecurityException("Decryption failed")
    }

    /** 解密；失败返回 null，不抛异常（迁移友好）。 */
    fun tryDecrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return tryDecryptInternal(stored)
    }

    private fun tryDecryptInternal(stored: String): String? {
        return runCatching {
            val parts = stored.substring(PREFIX.length).split(":", limit = 2)
            check(parts.size == 2) { "Invalid encrypted format" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** 判断值是否已加密。 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
