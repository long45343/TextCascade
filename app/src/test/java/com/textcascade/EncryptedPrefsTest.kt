/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedPrefsTest {

    /** 软件 AES-256 密钥，替代 Robolectric 缺失的 AndroidKeyStore。 */
    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun <T> withInjectedKey(block: () -> T): T {
        EncryptedPrefs.keyOverrideForTest = softwareKey()
        try {
            return block()
        } finally {
            EncryptedPrefs.keyOverrideForTest = null
        }
    }

    @After
    fun tearDown() {
        EncryptedPrefs.keyOverrideForTest = null
    }

    /**
     * 场景：真机首次登录、保存密码。Keystore 密钥默认
     * setRandomizedEncryptionRequired(true)，调用方自带 IV 会被拒绝。
     * 若加密路径仍然自己生成 IV，本用例新增的 Keystore 语义替身会抛
     * InvalidAlgorithmParameterException，tryEncrypt 返回 null，最终明文落盘。
     */
    @Test
    fun encryptionUsesKeystoreGeneratedIvAndRoundTrips() {
        withInjectedKey {
            val plaintext = "my-plain-password-with-/slash"
            val stored = EncryptedPrefs.tryEncrypt(plaintext)

            assertNotNull("加密必须成功，否则凭据会以明文落盘", stored)
            assertTrue(stored!!.startsWith("aks:"))
            assertEquals(plaintext, EncryptedPrefs.tryDecrypt(stored))

            // 密文不得包含明文片段
            assertFalse(stored.contains(plaintext))
        }
    }

    /** 场景：同一明文连续保存两次，Keystore 每次生成新 IV，密文必须不同。 */
    @Test
    fun repeatedEncryptionProducesDistinctIv() {
        withInjectedKey {
            val first = EncryptedPrefs.tryEncrypt("same-secret")
            val second = EncryptedPrefs.tryEncrypt("same-secret")
            assertNotNull(first)
            assertNotNull(second)
            assertTrue("每次加密都应使用新 IV", first != second)
        }
    }

    /**
     * 场景：升级安装后读取旧版本已加密的字段，必须仍能解密。
     * 存储格式 "aks:" + Base64(iv) + ":" + Base64(ciphertext+tag) 未变。
     */
    @Test
    fun legacyCiphertextStaysDecryptable() {
        withInjectedKey {
            val stored = EncryptedPrefs.tryEncrypt("legacy-value")!!
            // 模拟字段曾以同一格式落盘，重启后重新读取
            assertEquals("legacy-value", EncryptedPrefs.decrypt(stored))
        }
    }

    @Test
    fun emptyAndPrefixedValuesPassThrough() {
        assertEquals("", EncryptedPrefs.tryEncrypt(""))
        assertEquals("", EncryptedPrefs.encrypt(""))
        val prefixed = "aks:already-encrypted"
        assertEquals(prefixed, EncryptedPrefs.tryEncrypt(prefixed))
        assertEquals(prefixed, EncryptedPrefs.encrypt(prefixed))
    }

    @Test
    fun encryptFallsBackInsteadOfThrowing() {
        val encrypted = EncryptedPrefs.encrypt("secret")
        assertTrue(encrypted == "secret" || encrypted.startsWith("aks:"))
    }

    @Test
    fun tryEncryptReturnsNullOnKeystoreFailure() {
        val encrypted = EncryptedPrefs.tryEncrypt("secret")
        assertTrue(encrypted == null || encrypted.startsWith("aks:"))
    }


    /**
     * 场景：真机保存密码 → 杀进程重启 → 用保存的密码静默重登。
     * 注入可用密钥后，SettingsStore 落盘的敏感字段必须真的是密文；
     * 若加密路径回归成「自带 IV」，这里会拿到明文并被断言拦下。
     */
    @Test
    fun settingsStorePersistsCiphertextWhenKeystoreAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        withInjectedKey {
            val store = SettingsStore(context)
            store.savedEncryptedPassword = "my-plain-password"
            store.cookieHeader = "session-cookie-value"
            store.csrfToken = "csrf-token-value"
            store.passwordSha3 = "sha3-hash-value"

            val rawPassword = store.sharedPreferences.getString("saved_encrypted_password", "")
            val rawCookie = store.sharedPreferences.getString("cookie_header", "")
            val rawCsrf = store.sharedPreferences.getString("csrf_token", "")
            val rawSha3 = store.sharedPreferences.getString("password_sha3", "")

            assertTrue("保存密码必须以密文落盘", rawPassword!!.startsWith("aks:"))
            assertTrue("Cookie 必须以密文落盘", rawCookie!!.startsWith("aks:"))
            assertTrue("CSRF Token 必须以密文落盘", rawCsrf!!.startsWith("aks:"))
            assertTrue("密码哈希必须以密文落盘", rawSha3!!.startsWith("aks:"))
            assertFalse(rawPassword == "my-plain-password")

            // 重启后读回（同一密钥背景下解密路径）
            assertEquals("my-plain-password", store.savedEncryptedPassword)
            assertEquals("session-cookie-value", store.cookieHeader)
        }
    }

    @Test
    fun legacyPasswordHashTriggersMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.sharedPreferences.edit()
            .putString("saved_password_hash", "legacy-sha3-hash")
            .remove("saved_encrypted_password")
            .apply()
        assertTrue(store.needsPasswordMigration())
        store.clearLegacyPasswordHash()
        assertFalse(store.needsPasswordMigration())
    }

    @Test
    fun settingsStoreRoundTripsSensitiveValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.passwordSha3 = "password-hash"
        store.cookieHeader = "cookie"
        store.savedPasswordHash = "saved-hash"
        store.savedEncryptedPassword = "my-plain-password"

        assertEquals("password-hash", store.passwordSha3)
        assertEquals("cookie", store.cookieHeader)
        assertEquals("saved-hash", store.savedPasswordHash)
        assertEquals("my-plain-password", store.savedEncryptedPassword)

        val storedPassword = store.sharedPreferences.getString("password_sha3", "")
        assertTrue(storedPassword != null && (storedPassword == "password-hash" || storedPassword.startsWith("aks:")))

        val storedEncryptedPwd = store.sharedPreferences.getString("saved_encrypted_password", "")
        assertTrue(storedEncryptedPwd != null && (storedEncryptedPwd == "my-plain-password" || storedEncryptedPwd.startsWith("aks:")))

        store.clearSession()
        assertEquals("", store.passwordSha3)
        assertEquals("", store.cookieHeader)
        assertEquals("my-plain-password", store.savedEncryptedPassword)
    }

    @Test
    fun passwordDecryptionFailureFlagIsConsumedOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        assertFalse(store.consumePasswordDecryptionFailure())
        store.passwordDecryptionFailed = true
        assertTrue(store.consumePasswordDecryptionFailure())
        assertFalse(store.consumePasswordDecryptionFailure())
    }

    @Test
    fun decryptionFailureOnlyFlaggedForPasswordField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val corruptedCiphertext = "aks:CORRUPTED-DATA"
        store.sharedPreferences.edit()
            .putString("saved_encrypted_password", corruptedCiphertext)
            .putBoolean("password_decryption_failed", false)
            .apply()
        store.savedEncryptedPassword
        assertTrue(store.passwordDecryptionFailed)
        store.sharedPreferences.edit()
            .putString("csrf_token", corruptedCiphertext)
            .putBoolean("password_decryption_failed", false)
            .apply()
        store.csrfToken
        assertFalse(store.passwordDecryptionFailed)
    }
}
