/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * SessionRefresher 仍是登录 + 统一会话提交的唯一共享函数；
 * AuthManager 只做统一结果与 owner/single-flight 封装。
 */
@RunWith(RobolectricTestRunner::class)
class SessionRefresherTest {

    private class FakeLoginClient(private val action: (String, String, String) -> LoginResult) : LoginClient {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun login(serverUrl: String, username: String, password: String): LoginResult {
            calls.add(Triple(serverUrl, username, password))
            return action(serverUrl, username, password)
        }
    }

    private fun successResult(version: Int = 1) = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = "tok-2",
        tokenExpiresAtUtc = 42L,
        protocolVersion = version,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    private val FAKE_KEY = "ZmFrZS1kZXJpdmVkLWtleQ=="

    @Test
    fun refreshPersistsSnapshotDerivedKeyAndSessionActiveInOneCommit() {
        RuntimeStateStoreHolder.resetForTest()
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.derivedKeyBase64 = "old-key"

        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val outcome = SessionRefresher(store, deriveKeyBase64 = { FAKE_KEY }).refresh(
            loginClient = fake,
            password = "pw",
            savedPassword = "pw"
        )
        assertTrue(outcome is AuthResult.Success)
        assertEquals("https://srv.example", store.serverUrl)
        assertEquals("tok-2", store.token)
        assertEquals(42L, store.tokenExpiresAtUtc)
        assertEquals("pw", store.savedEncryptedPassword)
        assertEquals(true, store.hasSession)
        assertTrue(store.appPreferences.sessionActive)
        assertEquals(FAKE_KEY, store.derivedKeyBase64)
        assertEquals(listOf(Triple("https://srv.example", "user", "pw")), fake.calls)
    }

    @Test
    fun nullSavedPasswordDoesNotClearStoredPassword() {
        val store = SettingsStore(RuntimeEnvironment.getApplication()).apply {
            serverUrl = "https://srv.example"
            username = "user"
            savePassword = true
            savedEncryptedPassword = "stored"
        }
        val outcome = SessionRefresher(store, deriveKeyBase64 = { "" }).refresh(
            FakeLoginClient { _, _, _ -> successResult() },
            "stored",
            savedPassword = null
        )
        assertTrue(outcome is AuthResult.Success)
        assertEquals("stored", store.savedEncryptedPassword)
    }

    @Test
    fun protocolTooHighRefusesWithoutImplicitConnectOrActiveMarker() {
        val store = SettingsStore(RuntimeEnvironment.getApplication()).apply {
            serverUrl = "https://srv.example"
            username = "user"
        }
        val outcome = SessionRefresher(store, deriveKeyBase64 = { FAKE_KEY }).refresh(
            FakeLoginClient { _, _, _ -> successResult(Protocol.SUPPORTED_PROTOCOL_VERSION + 1) },
            "pw",
            savedPassword = null
        )
        assertTrue(outcome is AuthResult.ProtocolUnsupported)
        assertEquals("", store.token)
        assertEquals(false, store.hasSession)
        assertFalseMark(store)
    }

    private fun assertFalseMark(store: SettingsStore) {
        org.junit.Assert.assertFalse(store.appPreferences.sessionActive)
    }
}

