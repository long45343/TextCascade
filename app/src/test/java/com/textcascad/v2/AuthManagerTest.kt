/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * AuthManager 集中 owner、single-flight 与统一 AuthResult；
 * 前台/后台/cached relogin 成功动作分别由 Controller 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class AuthManagerTest {

    private class FakeLoginClient(
        private val action: (String, String, String) -> LoginResult
    ) : LoginClient {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun login(serverUrl: String, username: String, password: String): LoginResult {
            calls.add(Triple(serverUrl, username, password))
            return action(serverUrl, username, password)
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        AuthenticationCoordinator.resetForTests()
        context = RuntimeEnvironment.getApplication()
        RuntimeStateStoreHolder.resetForTest()
    }

    private fun newStore(commitSucceeds: Boolean = true): SettingsStore {
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        return store
    }

    private fun successResult() = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = "tok-2",
        tokenExpiresAtUtc = 42L,
        protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    @Test
    fun foregroundSubmitSuccessPersistsSessionAndRunsControllerAction() {
        val store = newStore().apply { savePassword = true; savedEncryptedPassword = "saved" }
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val manager = AuthManager(store, loginClientOverride = fake)
        val serviceStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        val result = manager.submit(
            replaceActive = true,
            owner = AuthOwner.ALWAYS,
            password = "",
            savedPasswordUsed = true,
            savedPassword = null,
            onSuccess = {
                serviceStarted.set(true)
                store.serviceRunning = true
            }
        )
        AuthenticationCoordinator.awaitIdle()

        assertTrue(result is AuthResult.Success)
        assertTrue(serviceStarted.get())
        assertEquals("tok-2", store.token)
        assertTrue(store.hasSession)
        assertTrue(store.serviceRunning)
    }

    @Test
    fun missingOwnerAndMissingPasswordReturnUnifiedResults() {
        val store = newStore()
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val manager = AuthManager(store, loginClientOverride = fake)

        val cancelled = manager.submit(
            replaceActive = true,
            owner = AuthOwner { false },
            password = "pw",
            savedPasswordUsed = false,
            savedPassword = null
        )
        val missing = manager.submit(
            replaceActive = true,
            owner = AuthOwner.ALWAYS,
            password = "",
            savedPasswordUsed = false,
            savedPassword = null
        )
        assertTrue(cancelled is AuthResult.Cancelled)
        assertTrue(missing is AuthResult.MissingPassword)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun cachedReloginNoCredentialsDoesNotCallClient() {
        val store = SettingsStore(context)
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val result = AuthManager(store, loginClientOverride = fake).cachedRelogin()
        assertEquals(AuthResult.NoCredentials, result)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun cachedReloginSuccessCommitsSessionWithSavedPassword() {
        val store = newStore().apply {
            savePassword = true
            savedEncryptedPassword = "stored-password"
        }
        val fake = FakeLoginClient { _, _, password ->
            assertEquals("stored-password", password)
            successResult()
        }
        val result = AuthManager(store, loginClientOverride = fake).cachedRelogin()

        assertTrue(result is AuthResult.Success)
        assertEquals("tok-2", store.token)
        assertEquals(true, store.hasSession)
    }

    @Test
    fun authFailureMapsToAuthRejectedAndInvalidatesPersistedMarker() {
        val context = RuntimeEnvironment.getApplication()
        RuntimeStateStoreHolder.resetForTest()
        val store = SettingsStore(context).apply {
            serverUrl = "https://srv.example"
            username = "user"
            savePassword = true
            savedEncryptedPassword = "pw"
        }
        val fake = FakeLoginClient { _, _, _ -> throw LoginRejectedException(401, "bad") }
        val manager = AuthManager(store, loginClientOverride = fake)

        val submitted = manager.submit(
            replaceActive = true,
            owner = AuthOwner.ALWAYS,
            password = "",
            savedPasswordUsed = true,
            savedPassword = null
        )
        val rejected = submitted as AuthResult.AuthRejected
        assertTrue(rejected.invalidationPersisted)
        assertFalse(store.hasSession)
        // 低频标记随拒绝事务写入持久层。
        assertTrue(!SettingsStore(context).appPreferences.sessionActive ||
            !store.appPreferences.sessionActive)
    }

    @Test
    fun rateLimitedCarriesRetryAfterAndInvalidatesActiveSession() {
        val context = RuntimeEnvironment.getApplication()
        RuntimeStateStoreHolder.resetForTest()
        val store = SettingsStore(context).apply {
            serverUrl = "https://srv.example"
            username = "user"
            savePassword = true
            savedEncryptedPassword = "pw"
        }
        val fake = FakeLoginClient { _, _, _ -> throw LoginRateLimitedException(429, 33L) }
        val manager = AuthManager(store, loginClientOverride = fake)
        val submitted = manager.submit(
            replaceActive = true,
            owner = AuthOwner.ALWAYS,
            password = "",
            savedPasswordUsed = true,
            savedPassword = null
        )
        assertEquals(AuthResult.RateLimited(33L), submitted)
        assertFalse(store.hasSession)
    }

    @Test
    fun submitBlockingBusyReturnsFailedForEngineCacheRelogin() {
        val gate = java.util.concurrent.CountDownLatch(1)
        AuthenticationCoordinator.submit(replaceActive = false) { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val store = newStore().apply {
            savePassword = true
            savedEncryptedPassword = "pw"
        }
        val fake = FakeLoginClient { _, _, _ -> successResult() }
        val result = AuthManager(store, loginClientOverride = fake).cachedRelogin()
        gate.countDown()
        AuthenticationCoordinator.awaitIdle()
        assertTrue(result is AuthResult.Failed)
    }
}



