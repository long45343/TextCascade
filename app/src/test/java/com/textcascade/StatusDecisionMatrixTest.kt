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

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 运行状态看板（全部正常单行收敛 / 异常逐项明细 / 瞬态消息平滑过渡与 3 秒自动复位）矩阵单测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusDecisionMatrixTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        AuthenticationCoordinator.resetForTests()
        TextCascadeApplication.resetForTest()
        store = SettingsStore(context)
        store.clearSession()
    }

    @After
    fun tearDown() {
        AuthenticationCoordinator.resetForTests()
        TextCascadeApplication.resetForTest()
    }

    private fun setupNormalSession() {
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://srv.example:8080",
                websocketUrl = "ws://srv.example:8080/clipsocket",
                passwordSha3 = "sha3-abc",
                hashedPasswordBase64 = "base64-xyz",
                csrfToken = "csrf-123",
                cookieHeader = "session=123",
                maxSizeBytes = 512_000L
            )
        )
        store.serviceRunning = true
        store.securityDegraded = false
    }

    // ------------------------------------------------------------------
    // 矩阵测试 1：健康状态下的看板单行收敛
    // ------------------------------------------------------------------

    @Test
    fun connectedStateShowsAllNormalText() {
        // 场景：服务运行中、会话有效、已连接、存储安全 → 精炼呈现单行「运行状态：正常」
        setupNormalSession()
        store.statusMessage = context.getString(R.string.status_connected)

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()
        assertTrue(activity.statusTextForTest().text.toString().contains(context.getString(R.string.status_all_normal)))
    }

    @Test
    fun broadcastingStateShowsActionTextWithoutBreakingNormal() {
        // 场景：正在广播（瞬态消息）时不误报异常，大字临时呈现「连接：已连接 - 正在广播」
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

        // 1. 英文文案
        store.statusMessage = "Connected - broadcasting"
        assertEquals(
            context.getString(R.string.status_prefix_connection, "Connected - broadcasting"),
            activity.statusTextForTest().text.toString()
        )

        // 2. 本地化资源文案
        val localizedBroadcasting = context.getString(R.string.status_connected_broadcasting)
        store.statusMessage = localizedBroadcasting
        assertEquals(
            context.getString(R.string.status_prefix_connection, localizedBroadcasting),
            activity.statusTextForTest().text.toString()
        )
    }

    @Test
    fun remoteTextCopiedStateShowsActionTextWithoutBreakingNormal() {
        // 场景：远端复制落地时临时呈现「连接：已复制远端文本」
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

        val localizedCopied = context.getString(R.string.status_remote_text_copied)
        store.statusMessage = localizedCopied
        assertEquals(
            context.getString(R.string.status_prefix_connection, localizedCopied),
            activity.statusTextForTest().text.toString()
        )
    }

    // ------------------------------------------------------------------
    // 矩阵测试 2：异常状态下按需逐项列出明细
    // ------------------------------------------------------------------

    @Test
    fun abnormalStatesListSpecificIssues() {
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

        // A. 连接断开异常
        store.statusMessage = "Disconnected: socket closed"
        assertTrue(activity.statusTextForTest().text.toString().contains("Disconnected: socket closed"))

        // B. 服务已停止
        store.statusMessage = context.getString(R.string.status_connected)
        store.serviceRunning = false
        assertTrue(activity.statusTextForTest().text.toString().contains(context.getString(R.string.service_stopped)))

        // C. 会话未登录
        store.serviceRunning = true
        store.clearSession()
        assertTrue(activity.statusTextForTest().text.toString().contains(context.getString(R.string.session_not_logged_in)))

        // D. 安全存储降级（Keystore 不可用，明文存储）
        setupNormalSession()
        store.statusMessage = context.getString(R.string.status_connected)
        store.securityDegraded = true
        assertTrue(activity.statusTextForTest().text.toString().contains(context.getString(R.string.status_security_degraded)))
    }

    // ------------------------------------------------------------------
    // 时效与复位测试 3：ClipForegroundService 3 秒时钟推进自动复位
    // ------------------------------------------------------------------

    @Test
    fun clipForegroundServiceSchedulesAndResetsTransientActionAfterDelay() {
        val serviceController = Robolectric.buildService(
            ClipForegroundService::class.java,
            Intent(context, ClipForegroundService::class.java)
        )
        val service = serviceController.create().get()
        setupNormalSession()

        // 模拟远端剪贴板文本写入完成
        service.onRemoteTextApplied("Text from remote device")

        // 验证瞬态文案已设置，且复位定时器已被调度
        val expectedCopied = context.getString(R.string.status_remote_text_copied)
        assertEquals(expectedCopied, store.statusMessage)
        assertTrue(service.isActionResetPendingForTest())

        // 推进 1.5 秒（未满 3 秒），仍保持动作文案
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        assertTrue(service.isActionResetPendingForTest())
        assertEquals(expectedCopied, store.statusMessage)

        // 再推进 1.5 秒（达到 3 秒），定时器被执行（isActionResetPending 为 false）
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        assertFalse(service.isActionResetPendingForTest())
        serviceController.destroy()
    }

    @Test
    fun clipForegroundServiceCancelsResetWhenDisconnectedOccurs() {
        val serviceController = Robolectric.buildService(
            ClipForegroundService::class.java,
            Intent(context, ClipForegroundService::class.java)
        )
        val service = serviceController.create().get()
        setupNormalSession()

        // 触发瞬态动作
        service.onRemoteTextApplied("Sample text")
        assertTrue(service.isActionResetPendingForTest())

        // 在 3 秒内发生断线
        service.onStatus("Disconnected: peer closed")

        // 复位调度应当被取消，防止在断线时错误复位回 Connected
        assertFalse(service.isActionResetPendingForTest())
        assertEquals("Disconnected: peer closed", store.statusMessage)
        serviceController.destroy()
    }
}
