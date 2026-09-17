/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.app.Notification
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationAndUiStatusTest {

    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        TextCascadeApplication.resetForTest()
        val app = RuntimeEnvironment.getApplication()
        store = SettingsStore(app)
        store.clearSession()
    }

    @After
    fun tearDown() {
        TextCascadeApplication.resetForTest()
    }

    @Test
    fun notificationTitleIsTextCascadeAndContentTextHandlesActiveAndNonActive() {
        val app = RuntimeEnvironment.getApplication()
        val controller = NotificationController(app)

        // 1. ACTIVE 或空状态时只包含连接状态
        val activeNotif = controller.buildForTest("Connected", backgroundStatusText = null)
        assertEquals("TextCascade", activeNotif.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
        assertEquals("Connected", activeNotif.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString())

        // 2. 非 ACTIVE 状态时用逗号分隔单行展示
        val inactiveNotif = controller.buildForTest("Connected", backgroundStatusText = "Xposed module inactive")
        assertEquals("TextCascade", inactiveNotif.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
        assertEquals("Connected, Xposed module inactive", inactiveNotif.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString())

        val detectingNotif = controller.buildForTest("Connecting", backgroundStatusText = "Detecting...")
        assertEquals("Connecting, Detecting...", detectingNotif.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString())
    }

    @Test
    fun mainActivityUpdatesIndependentBackgroundStatusTextView() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.2.0") {}

        store.connectionStatusMessage = "Connected"
        store.backgroundStatus = BackgroundStatus.ACTIVE.name

        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertTrue(binding.statusText.text.contains("Connected"))
        assertEquals(
            activity.getString(R.string.background_status_summary, activity.getString(R.string.background_status_active)),
            binding.backgroundStatusText.text.toString()
        )

        // 切换为 INACTIVE
        store.backgroundStatus = BackgroundStatus.INACTIVE.name
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertEquals(
            activity.getString(R.string.background_status_summary, activity.getString(R.string.background_status_inactive)),
            binding.backgroundStatusText.text.toString()
        )

        // 切换为 READ_LOGS_NOT_GRANTED
        store.backgroundStatus = BackgroundStatus.READ_LOGS_NOT_GRANTED.name
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertEquals(
            activity.getString(R.string.background_status_summary, activity.getString(R.string.background_status_read_logs_not_granted)),
            binding.backgroundStatusText.text.toString()
        )

        // 切换为 DETECTING
        store.backgroundStatus = BackgroundStatus.DETECTING.name
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertEquals(
            activity.getString(R.string.background_status_summary, activity.getString(R.string.background_status_detecting)),
            binding.backgroundStatusText.text.toString()
        )
    }

    @Test
    fun mainActivityRespondsToRuntimePreferencesChangeDirectly() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        val store = SettingsStore(activity)

        store.connectionStatusMessage = "Connected"
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val statusView = activity.findViewById<android.widget.TextView>(R.id.status_text)
        assertTrue(statusView.text.contains("Connected"))

        store.connectionStatusMessage = "Disconnected: error"
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(statusView.text.contains("Disconnected: error"))
    }

    @Test
    fun legacyStatusMessageDoesNotOverwriteBackgroundStatus() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.2.0") {}

        store.connectionStatusMessage = "Connecting"
        store.statusMessage = "Legacy Status Message"
        store.backgroundStatus = BackgroundStatus.ACTIVE.name

        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)

        assertEquals(
            activity.getString(R.string.background_status_summary, activity.getString(R.string.background_status_active)),
            binding.backgroundStatusText.text.toString()
        )
    }

    @Test
    fun updateStatusShowsNormalWhenAllNormalAndAbnormalPartsWhenNot() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val binding = MainActivityUiBinding.inflate(activity, "2.2.0") {}

        // 1. 全部正常：服务运行、会话有效、连接成功、安全未降级 -> 运行状态：正常
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://server.example:8443",
                token = "valid-token",
                tokenExpiresAtUtc = System.currentTimeMillis() + 60_000L,
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60
            )
        )
        store.serviceRunning = true
        store.connectionStatusMessage = "Connected"
        store.securityDegraded = false

        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertEquals(activity.getString(R.string.status_all_normal), binding.statusText.text.toString())

        // 2. 异常时仅列出异常部分：断开连接（服务与会话正常） -> 仅列出连接状态异常
        store.connectionStatusMessage = "Disconnected: socket closed"
        binding.updateStatus(store, sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        assertTrue(binding.statusText.text.contains("Disconnected: socket closed"))
        org.junit.Assert.assertFalse(binding.statusText.text.contains(activity.getString(R.string.session_not_logged_in)))
        org.junit.Assert.assertFalse(binding.statusText.text.contains(activity.getString(R.string.service_stopped)))
    }
}
