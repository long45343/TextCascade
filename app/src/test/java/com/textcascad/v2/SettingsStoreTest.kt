/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.SharedPreferences
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
        assertEquals("", store.token)
        assertFalse(store.appPreferences.sessionActive)
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
        // 调用方必须先同步提交低频 session_active=false，才允许更新内存状态。
        assertFalse(store.markSessionInvalid(sessionActiveCommitted = false))
        assertTrue(store.hasSession)
        assertTrue(store.markSessionInvalid(sessionActiveCommitted = true))
        assertEquals(false, store.hasSession)
        // token 值保留（便于诊断），会话标志失效
        assertEquals("tok-1", store.token)
    }

    @Test
    fun commitFailureReturnsFalse() {
        var fail = false
        val store = SettingsStore(
            RuntimeEnvironment.getApplication(),
            commitEditor = { editor ->
                if (fail) false else editor.commit()
            }
        )
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

    @Test
    fun registerAndUnregisterListenersSeparatePersistenceFromRuntime() {
        val store = newStore()
        val changedKeys = mutableListOf<String>()
        var runtimeChanges = 0
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) changedKeys.add(key)
        }
        val runtimeListener = object : RuntimeStateStore.Listener {
            override fun onChanged() {
                runtimeChanges++
            }
        }

        store.registerListener(preferenceListener)
        store.registerRuntimeListener(runtimeListener)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        store.serverUrl = "https://changed.example"
        store.statusMessage = "Connected"
        Thread.sleep(20)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(changedKeys.contains("server_url"))
        assertTrue(runtimeChanges > 0)
        // 运行时状态不再落盘。
        assertEquals(false, store.sharedPreferences.getBoolean("connection_status_message", false))

        changedKeys.clear()
        runtimeChanges = 0
        store.unregisterListener(preferenceListener)
        store.unregisterRuntimeListener(runtimeListener)

        store.serverUrl = "https://changed2.example"
        store.statusMessage = "Disconnected"
        Thread.sleep(20)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(changedKeys.isEmpty())
        assertEquals(0, runtimeChanges)
    }

    @Test
    fun runtimeStateDoesNotPersistTransientFieldsOrServiceRunning() {
        RuntimeStateStoreHolder.resetForTest()
        val app = RuntimeEnvironment.getApplication()
        RuntimeStateStoreHolder.initialize(sessionActive = true, securityDegraded = true)
        val store = SettingsStore(app)
        assertTrue(store.hasSession)
        assertTrue(store.securityDegraded)

        // 低频持久标记只在登录事务中写入；这里模拟已提交的登录结果。
        store.appPreferences.setSessionActive(true)

        store.serviceRunning = true
        store.statusMessage = "connecting"
        store.connectionStatusMessage = "connected"
        store.backgroundStatus = BackgroundStatus.ACTIVE.name

        val persistedPrefs = store.sharedPreferences
        assertFalse(persistedPrefs.contains("service_running"))
        assertFalse(persistedPrefs.contains("status_message"))
        assertFalse(persistedPrefs.contains("connection_status_message"))
        assertFalse(persistedPrefs.contains("background_status"))
        assertFalse(persistedPrefs.contains("has_session"))
        assertTrue(store.appPreferences.sessionActive)

        // Application 级共享：另一个 Facade 实例看到同一内存状态。
        assertEquals(true, SettingsStore(app).serviceRunning)
    }

    @Test
    fun clearSessionCommitsLowFrequencyMarkerBeforeResettingMemory() {
        RuntimeStateStoreHolder.resetForTest()
        val app = RuntimeEnvironment.getApplication()
        val store = SettingsStore(app).apply {
            updateLoginSession(
                SessionSnapshot(
                    serverUrl = "https://srv.example",
                    token = "tok",
                    tokenExpiresAtUtc = 42L,
                    maxTextBytes = 512_000L,
                    helloTimeoutSeconds = 10,
                    heartbeatIntervalSeconds = 20,
                    heartbeatTimeoutSeconds = 60
                )
            )
            serviceRunning = true
        }
        assertTrue(store.hasSession)
        assertTrue(store.appPreferences.sessionActive)
        assertTrue(store.clearSession())
        assertFalse(store.appPreferences.sessionActive)
        assertFalse(store.hasSession)
        assertFalse(store.serviceRunning)
        assertEquals("", store.token)
    }

    @Test
    fun clearSessionFailureKeepsMemoryState() {
        var fail = false
        val store = SettingsStore(
            RuntimeEnvironment.getApplication(),
            commitEditor = { editor -> if (fail) false else editor.commit() }
        ).apply {
            updateLoginSession(
                SessionSnapshot(
                    serverUrl = "https://srv.example",
                    token = "tok",
                    tokenExpiresAtUtc = 42L,
                    maxTextBytes = 512_000L,
                    helloTimeoutSeconds = 10,
                    heartbeatIntervalSeconds = 20,
                    heartbeatTimeoutSeconds = 60
                )
            )
            serviceRunning = true
        }
        fail = true
        assertFalse(store.clearSession())
        assertTrue(store.hasSession)
        assertTrue(store.serviceRunning)
        fail = false
        assertTrue(store.clearSession())
    }
}







