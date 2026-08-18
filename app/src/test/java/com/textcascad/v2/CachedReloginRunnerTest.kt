/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CachedReloginRunnerTest {

    private class FakeLoginClient(var result: ((String, String, String) -> LoginResult)) : LoginClient {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun login(serverUrl: String, username: String, password: String): LoginResult {
            calls.add(Triple(serverUrl, username, password))
            return result(serverUrl, username, password)
        }
    }

    private fun successResult() = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = "tok-2",
        tokenExpiresAtUtc = 42L,
        protocolVersion = 1,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    @Test
    fun successUpdatesSessionWithNewToken() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.savePassword = true
        store.savedEncryptedPassword = "pw"
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val outcome = CachedReloginRunner(store, fake).execute()
        assertTrue(outcome is CachedReloginResult.Success)
        assertEquals(Triple("https://srv.example", "user", "pw"), fake.calls.single())
        assertEquals("tok-2", store.token)
        assertEquals(42L, store.tokenExpiresAtUtc)
        assertEquals(true, store.hasSession)
    }

    @Test
    fun missingSavedPasswordReturnsNoCredentials() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        assertEquals(CachedReloginResult.NoCredentials, CachedReloginRunner(store, fake).execute())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun authFailureMappedFrom401() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.savedEncryptedPassword = "pw"
        val fake = FakeLoginClient { _, _, _ -> throw LoginRejectedException(401, "invalid_credentials") }
        assertEquals(CachedReloginResult.AuthFailure, CachedReloginRunner(store, fake).execute())
    }

    @Test
    fun rateLimitedMappedFrom429() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.savedEncryptedPassword = "pw"
        val fake = FakeLoginClient { _, _, _ -> throw LoginRateLimitedException(429, 33L) }
        val outcome = CachedReloginRunner(store, fake).execute()
        assertTrue(outcome is CachedReloginResult.RateLimited)
        assertEquals(33L, (outcome as CachedReloginResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun networkErrorMappedToTransientFailure() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.savedEncryptedPassword = "pw"
        val fake = FakeLoginClient { _, _, _ -> throw java.io.IOException("network down") }
        val outcome = CachedReloginRunner(store, fake).execute()
        assertTrue(outcome is CachedReloginResult.TransientFailure)
    }
}
