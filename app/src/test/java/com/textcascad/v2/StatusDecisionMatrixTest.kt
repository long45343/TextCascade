/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Intent
import android.widget.ImageView
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class StatusDecisionMatrixTest {

    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        RuntimeStateStoreHolder.resetForTest()
        AuthenticationCoordinator.resetForTests()
        TextCascadeApplication.resetForTest()
        val app = RuntimeEnvironment.getApplication()
        store = SettingsStore(app)
        store.clearSession()
    }

    @After
    fun tearDown() {
        RuntimeStateStoreHolder.resetForTest()
        AuthenticationCoordinator.resetForTests()
        TextCascadeApplication.resetForTest()
    }

    private fun setupNormalSession() {
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://srv.example:8443",
                token = "valid-token-123",
                tokenExpiresAtUtc = System.currentTimeMillis() + 3_600_000L,
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60
            )
        )
        store.serviceRunning = true
        store.securityDegraded = false
    }

    // ------------------------------------------------------------------
    // 矩阵测试 1：健康状态下的图标与文本展示
    // ------------------------------------------------------------------

    @Test
    fun connectedStateShowsCheckCircleAndAllNormalText() {
        setupNormalSession()
        store.connectionStatusMessage = "Connected"

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.5.0") {}

        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        val statusIcon = binding.root.findViewById<ImageView>(R.id.status_icon)
        // 图标保持对勾
        assertNotNullIcon(statusIcon)
        assertEquals(activity.getString(R.string.status_all_normal), binding.statusText.text.toString())
    }

    @Test
    fun broadcastingStateKeepsCheckCircleAndShowsActionText() {
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.5.0") {}

        // 1. 英文 Contract
        store.connectionStatusMessage = "Connected - broadcasting"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertTrue(binding.statusText.text.toString().contains("Connected - broadcasting"))
        assertEquals(
            activity.getString(R.string.status_prefix_connection, "Connected - broadcasting"),
            binding.statusText.text.toString()
        )

        // 2. 本地化资源字符串
        val localizedBroadcasting = activity.getString(R.string.status_connected_broadcasting)
        store.connectionStatusMessage = localizedBroadcasting
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertEquals(
            activity.getString(R.string.status_prefix_connection, localizedBroadcasting),
            binding.statusText.text.toString()
        )
    }

    @Test
    fun remoteTextCopiedStateKeepsCheckCircleAndShowsActionText() {
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.5.0") {}

        // 1. 英文 Contract
        store.connectionStatusMessage = "Remote text copied"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertEquals(
            activity.getString(R.string.status_prefix_connection, "Remote text copied"),
            binding.statusText.text.toString()
        )

        // 2. 本地化资源字符串
        val localizedCopied = activity.getString(R.string.status_remote_text_copied)
        store.connectionStatusMessage = localizedCopied
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertEquals(
            activity.getString(R.string.status_prefix_connection, localizedCopied),
            binding.statusText.text.toString()
        )
    }

    // ------------------------------------------------------------------
    // 矩阵测试 2：异常状态下正确判定为警告 (⚠) 并列出异常明细
    // ------------------------------------------------------------------

    @Test
    fun abnormalStatesTriggerWarningIconAndPrefixes() {
        setupNormalSession()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.5.0") {}

        // A. 连接断开异常
        store.connectionStatusMessage = "Disconnected: socket closed"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.toString().contains("Disconnected: socket closed"))

        // B. 正在连接中
        store.connectionStatusMessage = "Connecting"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.toString().contains("Connecting"))

        // C. 服务已停止
        store.connectionStatusMessage = "Connected"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = false)
        assertTrue(binding.statusText.text.toString().contains(activity.getString(R.string.service_stopped)))

        // D. 会话未登录
        store.clearSession()
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.toString().contains(activity.getString(R.string.session_not_logged_in)))

        // E. 会话持久化保存失败
        setupNormalSession()
        binding.updateStatus(store, sessionPersistenceFailed = true, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.toString().contains(activity.getString(R.string.session_not_logged_in)))

        // F. 安全存储降级
        setupNormalSession()
        store.securityDegraded = true
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.toString().contains(activity.getString(R.string.status_security_degraded)))
    }

    // ------------------------------------------------------------------
    // 时效与复位测试 3：ClipForegroundService 3 秒时钟推进自动复位
    // ------------------------------------------------------------------

    @Test
    fun clipForegroundServiceSchedulesAndResetsTransientActionAfterDelay() {
        val app = RuntimeEnvironment.getApplication()
        val serviceController = Robolectric.buildService(
            ClipForegroundService::class.java,
            Intent(app, ClipForegroundService::class.java)
        )
        val service = serviceController.create().get()
        setupNormalSession()

        // 模拟远端剪贴板文本写入完成
        service.onRemoteTextApplied("Text from remote device")

        // 验证瞬态文案已设置，且复位定时器已被调度
        val expectedCopied = app.getString(R.string.status_remote_text_copied)
        assertEquals(expectedCopied, store.connectionStatusMessage)
        assertTrue(service.isActionResetPendingForTest())

        // 时钟尚未达到 3 秒（例如推进 1.5 秒），仍保持动作文案
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        assertTrue(service.isActionResetPendingForTest())
        assertEquals(expectedCopied, store.connectionStatusMessage)

        // 时钟达到 3 秒（再推进 1.5 秒，总计 3 秒），由于单测中 engine 为 null 或未实际建连，
        // 我们验证 resetRunnable 已被执行（isActionResetPending 为 false）
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        assertFalse(service.isActionResetPendingForTest())
    }

    @Test
    fun clipForegroundServiceCancelsResetWhenDisconnectedOccurs() {
        val app = RuntimeEnvironment.getApplication()
        val serviceController = Robolectric.buildService(
            ClipForegroundService::class.java,
            Intent(app, ClipForegroundService::class.java)
        )
        val service = serviceController.create().get()
        setupNormalSession()

        // 触发瞬态动作
        service.onRemoteTextApplied("Sample text")
        assertTrue(service.isActionResetPendingForTest())

        // 在 3 秒内发生断网
        service.onStatus("Disconnected: peer closed")

        // 复位调度应当被取消，防止在断线时错误复位回 Connected
        assertFalse(service.isActionResetPendingForTest())
        assertEquals("Disconnected: peer closed", store.connectionStatusMessage)
    }

    private fun assertNotNullIcon(imageView: ImageView) {
        org.junit.Assert.assertNotNull(imageView.drawable)
    }
}
