/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReadLogsFallbackDegradeTest {

    private lateinit var app: Application
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        TextCascadeApplication.resetForTest()
        app = RuntimeEnvironment.getApplication()
        settings = SettingsStore(app)
        settings.clearSession()
        settings.serverUrl = "https://example.com:8443"
        settings.username = "test"
        settings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://example.com:8443",
                token = "test-token",
                tokenExpiresAtUtc = System.currentTimeMillis() + 60_000L,
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60
            )
        )
    }

    @After
    fun tearDown() {
        TextCascadeApplication.resetForTest()
    }

    @Test
    fun detectingStateDoesNotStartLogcat() {
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.DETECTING)
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(BackgroundStatus.DETECTING.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun activeStateStopsLogcatAndShowsBackgroundAvailable() {
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.ACTIVE)
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(BackgroundStatus.ACTIVE.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun inactiveWithReadLogsGrantedStartsLogcatFallback() {
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(BackgroundStatus.INACTIVE.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun inactiveWithoutReadLogsShowsReadLogsNotGranted() {
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(BackgroundStatus.READ_LOGS_NOT_GRANTED.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun transitionFromInactiveToActiveImmediatelyStopsLogcat() {
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()
        assertEquals(BackgroundStatus.INACTIVE.name, settings.backgroundStatus)

        // 状态转为 ACTIVE
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.ACTIVE)
        assertEquals(BackgroundStatus.ACTIVE.name, settings.backgroundStatus)

        controller.destroy()
    }
}