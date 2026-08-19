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
 * R3: Keystore 降级可见化——putSecret/encryptForCommit 走明文降级时置位
 * 持久化 `security_degraded`；Keystore 正常时不置位。
 */
@RunWith(RobolectricTestRunner::class)
class SecurityDegradedTest {

    private fun newStore(encryptor: (String) -> String?): SettingsStore =
        SettingsStore(RuntimeEnvironment.getApplication(), encryptor = encryptor)

    @Test
    fun putSecretDegradedSetsFlagAndStoresPlaintext() {
        val store = newStore(encryptor = { null })
        store.token = "secret-token"
        assertTrue(store.securityDegraded)
        // 明文落盘：getSecret 透传非加密值
        assertEquals("secret-token", store.token)
    }

    @Test
    fun updateLoginSessionDegradedSetsFlag() {
        val store = newStore(encryptor = { null })
        val committed = store.updateLoginSession(
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
        assertTrue(committed)
        assertTrue(store.securityDegraded)
        assertTrue(store.hasSession)
    }

    @Test
    fun keystoreNormalDoesNotSetFlag() {
        // 模拟加密成功（非 null 即视为 Keystore 可用）
        val store = newStore(encryptor = { v -> "enc:$v" })
        store.token = "secret-token"
        assertFalse(store.securityDegraded)
    }
}
