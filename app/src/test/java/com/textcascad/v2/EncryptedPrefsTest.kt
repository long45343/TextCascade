/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric 环境无 AndroidKeyStore：验证迁移/降级语义。
 * 真机上的加密往返由集成验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsTest {

    @Test
    fun isEncryptedDetectsPrefix() {
        assertTrue(EncryptedPrefs.isEncrypted("aks:xxx:yyy"))
        assertFalse(EncryptedPrefs.isEncrypted("plain"))
        assertFalse(EncryptedPrefs.isEncrypted(""))
    }

    @Test
    fun tryDecryptPassesThroughNonEncryptedValue() {
        assertEquals("plain", EncryptedPrefs.tryDecrypt("plain"))
        assertEquals("", EncryptedPrefs.tryDecrypt(""))
    }

    @Test
    fun decryptPassesThroughNonEncryptedValue() {
        assertEquals("legacy", EncryptedPrefs.decrypt("legacy"))
    }

    @Test
    fun tryDecryptReturnsNullForMalformedEncryptedValue() {
        assertNull(EncryptedPrefs.tryDecrypt("aks:nocolon"))
    }

    @Test
    fun tryDecryptReturnsNullForGarbagePayload() {
        assertNull(EncryptedPrefs.tryDecrypt("aks:!!!!:????"))
    }

    @Test
    fun decryptThrowsForGarbagePayload() {
        var threw = false
        try {
            EncryptedPrefs.decrypt("aks:!!!!:????")
        } catch (_: java.security.GeneralSecurityException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun encryptWithoutKeystoreDegradesToPlaintext() {
        // Robolectric 无 AndroidKeyStore：tryEncrypt 失败返回 null，encrypt 降级原文
        val value = "secret"
        val stored = EncryptedPrefs.encrypt(value)
        assertTrue(stored == value || EncryptedPrefs.isEncrypted(stored))
        assertEquals(value, EncryptedPrefs.tryDecrypt(stored))
    }
}
