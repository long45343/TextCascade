/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CryptoManagerTest {

    // ---------------- PBKDF2-HMAC-SHA256（双端约定输入） ----------------

    /** 独立参考实现：PBKDF2-HMAC-SHA256（RFC 2898 / RFC 8018 §5.2）。 */
    private fun pbkdf2Manual(password: String, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val blocks = (dkLen + 31) / 32
        val output = ByteArray(blocks * 32)
        for (blockIndex in 1..blocks) {
            // U_1 = PRF(P, S || INT(i))：salt 在前、块序号在后（RFC 8018 §5.2）
            val u = ByteArray(salt.size + 4)
            salt.copyInto(u, 0)
            u[salt.size] = ((blockIndex ushr 24) and 0xff).toByte()
            u[salt.size + 1] = ((blockIndex ushr 16) and 0xff).toByte()
            u[salt.size + 2] = ((blockIndex ushr 8) and 0xff).toByte()
            u[salt.size + 3] = (blockIndex and 0xff).toByte()
            var t = mac.doFinal(u)
            val block = t.copyOf()
            repeat(iterations - 1) {
                t = mac.doFinal(t)
                for (i in block.indices) block[i] = (block[i].toInt() xor t[i].toInt()).toByte()
            }
            block.copyInto(output, (blockIndex - 1) * 32)
        }
        return output.copyOf(dkLen)
    }

    @Test
    fun deriveKeyMatchesReferenceImplementationWithCombinedSalt() {
        val rounds = 4096
        val key = CryptoManager.derivePasswordKey("user", "pass", "salt", rounds)
        val reference = pbkdf2Manual(
            password = "pass",
            salt = "user\$pass\$salt".toByteArray(Charsets.UTF_8),
            iterations = rounds,
            dkLen = 32
        )
        assertTrue(key.contentEquals(reference))
        assertEquals(32, key.size)
    }

    @Test
    fun deriveKeyIsDeterministicAndInputSensitive() {
        val a = CryptoManager.derivePasswordKey("user", "pass", "salt", 1000)
        val b = CryptoManager.derivePasswordKey("user", "pass", "salt", 1000)
        val c = CryptoManager.derivePasswordKey("user", "pass2", "salt", 1000)
        val d = CryptoManager.derivePasswordKey("user2", "pass", "salt", 1000)
        val e = CryptoManager.derivePasswordKey("user", "pass", "salt2", 1000)
        assertTrue(a.contentEquals(b))
        assertNotEquals(Base64.encodeToString(a, Base64.NO_WRAP), Base64.encodeToString(c, Base64.NO_WRAP))
        assertNotEquals(Base64.encodeToString(a, Base64.NO_WRAP), Base64.encodeToString(d, Base64.NO_WRAP))
        assertNotEquals(Base64.encodeToString(a, Base64.NO_WRAP), Base64.encodeToString(e, Base64.NO_WRAP))
    }

    // ---------------- AES-256-GCM 载荷 ----------------

    @Test
    fun encryptDecryptRoundtrip() {
        val keyBase64 = Base64.encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000),
            Base64.NO_WRAP
        )
        val payload = CryptoManager.encrypt("你好 world 🌍", keyBase64)
        // nonce 生成 12 字节（GCM 标准 IV）
        assertEquals(12, Base64.decode(payload.nonce, Base64.DEFAULT).size)
        // tag 128 位（16 字节）
        assertEquals(16, Base64.decode(payload.tag, Base64.DEFAULT).size)
        assertEquals("你好 world 🌍", CryptoManager.decrypt(payload, keyBase64))
    }

    @Test
    fun decryptAccepts12ByteNonce() {
        val keyBase64 = Base64.encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000),
            Base64.NO_WRAP
        )
        // 手工用 12 字节 IV 加密，构造与协议一致的载荷
        val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val encryptedWithTag = cipher.doFinal("legacy-12b-nonce".toByteArray(Charsets.UTF_8))
        val payload = EncryptedPayload(
            nonce = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(
                encryptedWithTag.copyOfRange(0, encryptedWithTag.size - 16),
                Base64.NO_WRAP
            ),
            tag = Base64.encodeToString(
                encryptedWithTag.copyOfRange(encryptedWithTag.size - 16, encryptedWithTag.size),
                Base64.NO_WRAP
            )
        )
        assertEquals("legacy-12b-nonce", CryptoManager.decrypt(payload, keyBase64))
    }

    @Test
    fun encryptedPayloadJsonFieldOrderAndFormat() {
        val keyBase64 = Base64.encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000),
            Base64.NO_WRAP
        )
        val payload = CryptoManager.encrypt("foobar", keyBase64)
        val json = CryptoManager.encryptedPayloadJson(payload)
        val obj = org.json.JSONObject(json)
        assertEquals(payload.nonce, obj.getString("nonce"))
        assertEquals(payload.ciphertext, obj.getString("ciphertext"))
        assertEquals(payload.tag, obj.getString("tag"))
        assertEquals(3, obj.length())
        // 紧凑（无空格）
        assertTrue(!json.contains(": ") && !json.contains(", "))
    }

    @Test
    fun decryptFailsOnTamperedCiphertext() {
        val keyBase64 = Base64.encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000),
            Base64.NO_WRAP
        )
        val payload = CryptoManager.encrypt("foobar", keyBase64)
        val tampered = EncryptedPayload(
            nonce = payload.nonce,
            ciphertext = Base64.encodeToString(
                Base64.decode(payload.ciphertext, Base64.DEFAULT).let {
                    it.copyOf().also { arr -> arr[0] = (arr[0].toInt() xor 1).toByte() }
                },
                Base64.NO_WRAP
            ),
            tag = payload.tag
        )
        var threw = false
        try {
            CryptoManager.decrypt(tampered, keyBase64)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}


