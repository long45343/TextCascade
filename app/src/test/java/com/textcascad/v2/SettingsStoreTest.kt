/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private fun newStore(): SettingsStore = SettingsStore(RuntimeEnvironment.getApplication())

    @Test
    fun defaults() {
        val store = newStore()
        assertEquals(ClipConfig.DEFAULT_SERVER_URL, store.serverUrl)
        assertEquals("", store.username)
        assertEquals(ClipConfig.DEFAULT_HASH_ROUNDS, store.hashRounds)
        assertEquals("", store.salt)
        assertEquals(ClipConfig.DEFAULT_MAX_TEXT_BYTES, store.localMaxClipboardBytes)
        assertEquals(ClipConfig.DEFAULT_MAX_TEXT_BYTES, store.maxTextBytes)
        assertEquals(true, store.cipherEnabled)
        assertEquals(false, store.savePassword)
        assertEquals(false, store.relaunchOnBoot)
        assertEquals(false, store.websocketStatusNotification)
        assertEquals(false, store.trustAllCerts)
        assertEquals("", store.token)
        assertEquals(0L, store.tokenExpiresAtUtc)
        assertEquals("", store.derivedKeyBase64)
        assertEquals("", store.savedEncryptedPassword)
        // lastServerVersion 初始 0（不用 -1/null）
        assertEquals(0L, store.lastServerVersion)
        assertEquals(false, store.hasSession)
    }

    @Test
    fun lastServerVersionClampedToUnsigned() {
        val store = newStore()
        store.lastServerVersion = -5L
        assertEquals(0L, store.lastServerVersion)
        store.lastServerVersion = 99L
        assertEquals(99L, store.lastServerVersion)
    }

    @Test
    fun updateLoginSessionPersistsTokenAndParams() {
        val store = newStore()
        val committed = store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example/",
                token = "tok-1",
                tokenExpiresAtUtc = 1000L,
                maxTextBytes = 300_000L,
                helloTimeoutSeconds = 8,
                heartbeatIntervalSeconds = 18,
                heartbeatTimeoutSeconds = 55,
                savedPassword = "pw"
            )
        )
        assertTrue(committed)
        assertEquals("https://srv.example", store.serverUrl)
        assertEquals("tok-1", store.token)
        assertEquals(1000L, store.tokenExpiresAtUtc)
        assertEquals(300_000L, store.maxTextBytes)
        assertEquals(8, store.helloTimeoutSeconds)
        assertEquals(18, store.heartbeatIntervalSeconds)
        assertEquals(55, store.heartbeatTimeoutSeconds)
        assertEquals(true, store.hasSession)
        assertEquals("pw", store.savedEncryptedPassword)
    }

    @Test
    fun clearingSavedPasswordKeepsDerivedKey() {
        val store = newStore()
        store.derivedKeyBase64 = "keydata"
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example",
                token = "tok-1",
                tokenExpiresAtUtc = 1000L,
                maxTextBytes = 300_000L,
                helloTimeoutSeconds = 8,
                heartbeatIntervalSeconds = 18,
                heartbeatTimeoutSeconds = 55,
                savedPassword = "pw"
            )
        )
        assertEquals("pw", store.savedEncryptedPassword)
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example",
                token = "tok-2",
                tokenExpiresAtUtc = 2000L,
                maxTextBytes = 300_000L,
                helloTimeoutSeconds = 8,
                heartbeatIntervalSeconds = 18,
                heartbeatTimeoutSeconds = 55,
                savedPassword = ""
            )
        )
        assertEquals("", store.savedEncryptedPassword)
        // 派生密钥与会话参数保留，以便继续解密收件
        assertEquals("keydata", store.derivedKeyBase64)
        assertEquals("tok-2", store.token)
        assertEquals(true, store.hasSession)
    }

    @Test
    fun clearSessionRemovesTokenButKeepsClientIdAndDerivedKey() {
        val store = newStore()
        val clientId = store.clientId()
        store.derivedKeyBase64 = "keydata"
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example",
                token = "tok-1",
                tokenExpiresAtUtc = 1000L,
                maxTextBytes = 300_000L,
                helloTimeoutSeconds = 8,
                heartbeatIntervalSeconds = 18,
                heartbeatTimeoutSeconds = 55,
                savedPassword = "pw"
            )
        )
        assertTrue(store.clearSession())
        assertEquals("", store.token)
        assertEquals(0L, store.tokenExpiresAtUtc)
        assertEquals(false, store.hasSession)
        assertEquals(false, store.serviceRunning)
        assertEquals("", store.statusMessage)
        assertEquals(clientId, store.clientId())
        assertEquals("keydata", store.derivedKeyBase64)
    }

    @Test
    fun markSessionInvalidKeepsTokenValue() {
        val store = newStore()
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example",
                token = "tok-1",
                tokenExpiresAtUtc = 1000L,
                maxTextBytes = 300_000L,
                helloTimeoutSeconds = 8,
                heartbeatIntervalSeconds = 18,
                heartbeatTimeoutSeconds = 55
            )
        )
        assertTrue(store.markSessionInvalid())
        assertEquals(false, store.hasSession)
        // token 值保留（便于诊断），会话标志失效
        assertEquals("tok-1", store.token)
    }

    @Test
    fun commitFailureReturnsFalse() {
        var fail = false
        val store = SettingsStore(RuntimeEnvironment.getApplication()) { editor ->
            if (fail) false else editor.commit()
        }
        fail = true
        assertFalse(store.clearSession())
        fail = false
        assertTrue(store.clearSession())
    }

    @Test
    fun clientIdStableAndUuidV4() {
        val store = newStore()
        val first = store.clientId()
        assertNotNull(first)
        assertEquals(first, store.clientId())
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first))
    }

    @Test
    fun clientNameIsModelWithoutSpaces() {
        val store = newStore()
        val name = store.clientName()
        assertTrue(name.isNotBlank())
        assertTrue(!name.contains(" "))
    }
}
