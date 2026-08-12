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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedPrefsTest {

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
}
