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

/**
 * R2: 两条登录路径（AuthenticationWorkflow / CachedReloginRunner）
 * 对同一 LoginResult 产生完全一致的会话提交与派生；协议版本过高行为一致。
 */
@RunWith(RobolectricTestRunner::class)
class SessionRefresherTest {

    private class FakeLoginClient(var result: ((String, String, String) -> LoginResult)) : LoginClient {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun login(serverUrl: String, username: String, password: String): LoginResult {
            calls.add(Triple(serverUrl, username, password))
            return result(serverUrl, username, password)
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

    private fun newStore(): SettingsStore {
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://srv.example"
        store.username = "user"
        return store
    }

    private val FAKE_KEY = "ZmFrZS1kZXJpdmVkLWtleQ=="

    @Test
    fun bothPathsProduceIdenticalSessionCommitAndDerivation() {
        // 路径 A: 首次登录（AuthenticationWorkflow）
        val storeA = newStore()
        val fakeA = FakeLoginClient { _, _, _ -> successResult() }
        val workflowOutcome = AuthenticationWorkflow(
            settings = storeA,
            loginClientFactory = { fakeA },
            deriveCredentials = { _, _ -> DerivedCredentials(derivedKeyBase64 = FAKE_KEY) },
            startService = { true },
            setStatus = {},
            isOwnerAlive = { true }
        ).execute(password = "pw", savedPasswordUsed = false, savedPassword = "pw")
        assertTrue(workflowOutcome is AuthenticationOutcome.Success)

        // 路径 B: 静默重登（CachedReloginRunner）
        val storeB = newStore()
        storeB.savePassword = true
        storeB.savedEncryptedPassword = "pw"
        val fakeB = FakeLoginClient { _, _, _ -> successResult() }
        val reloginOutcome = CachedReloginRunner(
            settings = storeB,
            loginClient = fakeB,
            deriveKeyBase64 = { FAKE_KEY }
        ).execute()
        assertTrue(reloginOutcome is CachedReloginResult.Success)

        // 同一 LoginResult → 两条路径的会话提交完全一致
        assertEquals(storeA.token, storeB.token)
        assertEquals(storeA.tokenExpiresAtUtc, storeB.tokenExpiresAtUtc)
        assertEquals(storeA.hasSession, storeB.hasSession)
        assertEquals(storeA.maxTextBytes, storeB.maxTextBytes)
        assertEquals(storeA.helloTimeoutSeconds, storeB.helloTimeoutSeconds)
        assertEquals(storeA.heartbeatIntervalSeconds, storeB.heartbeatIntervalSeconds)
        assertEquals(storeA.heartbeatTimeoutSeconds, storeB.heartbeatTimeoutSeconds)
        assertEquals(storeA.serverUrl, storeB.serverUrl)
        // 派生密钥写入一致（静默重登成功路径同样写入 derivedKeyBase64）
        assertEquals(FAKE_KEY, storeA.derivedKeyBase64)
        assertEquals(storeA.derivedKeyBase64, storeB.derivedKeyBase64)
    }

    @Test
    fun protocolTooHighBothPathsRefuseWithoutImplicitConnect() {
        // 路径 A: 首次登录拒绝并提示升级，不建连
        val storeA = newStore()
        val fakeA = FakeLoginClient { _, _, _ -> successResult(version = Protocol.SUPPORTED_PROTOCOL_VERSION + 1) }
        val workflowOutcome = AuthenticationWorkflow(
            settings = storeA,
            loginClientFactory = { fakeA },
            deriveCredentials = { _, _ -> DerivedCredentials(derivedKeyBase64 = FAKE_KEY) },
            startService = { false },
            setStatus = {},
            isOwnerAlive = { true }
        ).execute(password = "pw", savedPasswordUsed = false, savedPassword = null)
        assertTrue(workflowOutcome is AuthenticationOutcome.ProtocolUnsupported)
        assertEquals(Protocol.SUPPORTED_PROTOCOL_VERSION + 1, (workflowOutcome as AuthenticationOutcome.ProtocolUnsupported).serverVersion)
        // 不隐式建连：会话未提交
        assertEquals(false, storeA.hasSession)
        assertEquals("", storeA.token)

        // 路径 B: 静默重登同样拒绝，会话不提交
        val storeB = newStore()
        storeB.savePassword = true
        storeB.savedEncryptedPassword = "pw"
        val fakeB = FakeLoginClient { _, _, _ -> successResult(version = Protocol.SUPPORTED_PROTOCOL_VERSION + 1) }
        val reloginOutcome = CachedReloginRunner(
            settings = storeB,
            loginClient = fakeB,
            deriveKeyBase64 = { FAKE_KEY }
        ).execute()
        assertTrue(reloginOutcome is CachedReloginResult.TransientFailure)
        assertEquals(false, storeB.hasSession)
        assertEquals("", storeB.token)
    }
}
