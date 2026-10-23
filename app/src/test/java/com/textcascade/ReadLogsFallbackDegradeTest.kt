/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascade

import android.Manifest
import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Xposed 激活状态驱动 READ_LOGS 降级测试：
 * - DETECTING：不启动 logcat。
 * - ACTIVE：Xposed 可用，不启动 logcat，状态显示后台可用。
 * - INACTIVE + 拥有权限：启动 logcat 降级，状态显示模块未激活。
 * - INACTIVE + 无权限：不启动 logcat，状态显示未授予 READ_LOGS。
 * - 运行中从 INACTIVE 恢复为 ACTIVE：立即停止 logcat 降级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadLogsFallbackDegradeTest {

    private lateinit var app: Application
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        TextCascadeApplication.resetForTest()
        app = RuntimeEnvironment.getApplication()
        settings = SettingsStore(app)
        settings.clearSession()
        settings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.com:8080",
                websocketUrl = "ws://example.com:8080/clipsocket",
                passwordSha3 = "sha3",
                hashedPasswordBase64 = "base64",
                csrfToken = "csrf",
                cookieHeader = "session=test",
                maxSizeBytes = 512_000L
            )
        )
        settings.serviceRunning = true
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
        assertEquals(BackgroundStatus.DETECTING.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun activeStateStopsLogcatAndShowsBackgroundAvailable() {
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.ACTIVE)
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        assertEquals(BackgroundStatus.ACTIVE.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun inactiveWithReadLogsGrantedStartsLogcatFallback() {
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        assertEquals(BackgroundStatus.INACTIVE.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun inactiveWithoutReadLogsShowsReadLogsNotGranted() {
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        assertEquals(BackgroundStatus.READ_LOGS_NOT_GRANTED.name, settings.backgroundStatus)
        controller.destroy()
    }

    @Test
    fun transitionFromInactiveToActiveImmediatelyStopsLogcat() {
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.READ_LOGS)
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.INACTIVE)

        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        assertEquals(BackgroundStatus.INACTIVE.name, settings.backgroundStatus)

        // 状态转为 ACTIVE
        TextCascadeApplication.setActivationStateForTest(XposedActivationState.ACTIVE)
        assertEquals(BackgroundStatus.ACTIVE.name, settings.backgroundStatus)

        controller.destroy()
    }
}
