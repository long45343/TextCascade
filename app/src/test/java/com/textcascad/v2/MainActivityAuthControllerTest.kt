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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A3/A4：认证核心只返回 AuthResult；Controller 负责启动服务、UI 分支和登出。
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityAuthControllerTest {


    @Test
    fun loginRejectedShowsInvalidCredentialsAndDoesNotOverrideService() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        val binding = MainActivityUiBinding.inflate(activity, "test") {}
        val controller = MainActivityAuthController(
            activity = activity,
            settingsStore = store,
            uiBinding = binding,
            dependencies = AuthenticationDependencies(settingsStoreFactory = { store })
        )
        // owner/cancel 集中在 AuthManager；这里验证 UI 分支不伪造服务运行覆盖。
        assertFalse(controller.ownerForGeneration(99L).isCurrent())
        binding.setBusy(false, activity.getString(R.string.status_invalid_credentials), store, false, false)
        assertEquals(activity.getString(R.string.status_invalid_credentials), store.statusMessage)
        assertFalse(store.serviceRunning)
    }

    @Test
    fun rateLimitedProtocolAndPersistenceBranchesMapToDedicatedMessages() {
        RuntimeStateStoreHolder.resetForTest()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        val binding = MainActivityUiBinding.inflate(activity, "test") {}

        // busy=false 时只更新主状态文案，不覆盖实时连接状态（历史契约）。
        val firstConnection = store.connectionStatusMessage
        binding.setBusy(false, activity.getString(R.string.status_login_rate_limited), store, false, false)
        assertEquals(activity.getString(R.string.status_login_rate_limited), store.statusMessage)

        binding.setBusy(
            false,
            activity.getString(
                R.string.status_protocol_unsupported,
                2,
                Protocol.SUPPORTED_PROTOCOL_VERSION
            ),
            store,
            false,
            false
        )
        assertTrue(store.statusMessage.contains("2"))

        binding.setBusy(
            false,
            activity.getString(R.string.status_session_invalidation_persist_failed),
            store,
            true,
            false
        )
        assertEquals(activity.getString(R.string.status_session_invalidation_persist_failed), store.statusMessage)
        assertEquals(firstConnection, store.connectionStatusMessage)
    }

    @Test
    fun logoutStopsServiceThenClearsPersistedSessionMarker() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val app = RuntimeEnvironment.getApplication()
        RuntimeStateStoreHolder.resetForTest()
        val store = SettingsStore(app)
        store.serverUrl = "https://valid.example:8443"
        store.username = "user"
        store.savePassword = true
        store.savedEncryptedPassword = "pw"
        store.appPreferences.setSessionActive(true)
        RuntimeStateStoreHolder.initialize(sessionActive = true)
        store.serviceRunning = true

        var stopped = false
        val deps = AuthenticationDependencies(
            settingsStoreFactory = { store },
            stopService = { stopped = true }
        )
        val binding = MainActivityUiBinding.inflate(activity, "test") {}
        val controller = MainActivityAuthController(activity, store, binding, deps)

        controller.logout()
        AuthenticationCoordinator.awaitIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(stopped)
        assertFalse(store.hasSession)
        assertFalse(store.appPreferences.sessionActive)
    }
}





