/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * BootReceiver 开机自启决策，对应 R3 重构后的真实启动情景：
 *
 * 设备开机完成（ACTION_BOOT_COMPLETED）时，是否拉起同步前台服务取决于四个条件的合取：
 * relaunchOnBoot 开启 && 低频 session_active 标记为真 && serverUrl 与 token 均非空。
 * `serviceRunning` 是进程运行态、重启后必然为 false，不得参与判断。
 *
 * 每个缺失条件对应一种真实用户状态：
 * - 未勾选"开机自启" → 不启动；
 * - 曾登录但已登出/被踢（session_active=false）→ 不启动；
 * - 已登录但 token 为空（登录事务未提交完整）→ 不启动；
 * - 全部满足 → 启动服务。
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RuntimeStateStoreHolder.resetForTest()
    }

    private fun loggedIn() {
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example:8443"
        store.username = "user"
        store.token = "tok-1"
        store.appPreferences.setSessionActive(true)
        RuntimeStateStoreHolder.initialize(sessionActive = true)
        // 进程运行态必须保持为 false：重启后它不参与恢复决策。
        assertFalse(store.serviceRunning)
    }

    private fun startedServiceCount(): Int {
        val app = context.applicationContext as android.app.Application
        val shadow = Shadows.shadowOf(app)
        var n = 0
        while (true) {
            // peek-first 模式：先窥视再消费，避免误吞不相关意图导致死循环
            val intent = shadow.nextStartedService ?: break
            if (intent.component?.className == ClipForegroundService::class.java.name) n++
        }
        return n
    }

    @Test
    fun bootWithAllConditionsMetStartsSyncService() {
        val store = SettingsStore(context).apply {
            relaunchOnBoot = true
        }
        loggedIn()

        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(1, startedServiceCount())
    }

    @Test
    fun bootWithoutRelaunchOnBootDoesNotStartService() {
        SettingsStore(context).apply { relaunchOnBoot = false }
        loggedIn()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0, startedServiceCount())
    }

    @Test
    fun bootWithLoggedOutSessionMarkerDoesNotStartService() {
        SettingsStore(context).apply { relaunchOnBoot = true }
        loggedIn()
        // 用户已登出：低频标记在 clearSession/logout 事务中被置 false，
        // 重启后内存 hasSession 由该标记初始化为 false。
        loginOuted()
        org.junit.Assert.assertFalse(
            "precondition: in-memory hasSession must be false after logout",
            SettingsStore(context).hasSession
        )

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0, startedServiceCount())
    }

    private fun loginOuted() {
        store_appPrefs().setSessionActive(false)
        RuntimeStateStoreHolder.initialize(sessionActive = false)
    }

    private fun store_appPrefs() = SettingsStore(context).appPreferences

    @Test
    fun bootWithBlankTokenDoesNotStartServiceEvenIfSessionActiveMarked() {
        val store = SettingsStore(context)
        store.relaunchOnBoot = true
        store.serverUrl = "https://srv.example:8443"
        store.token = ""
        store.appPreferences.setSessionActive(true)
        RuntimeStateStoreHolder.initialize(sessionActive = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals("token-less session must not auto-start", 0, startedServiceCount())
    }

    @Test
    fun nonBootActionIsIgnored() {
        val store = SettingsStore(context)
        store.relaunchOnBoot = true
        loggedIn()

        receiver_onReceive(Intent(Intent.ACTION_SCREEN_ON))

        assertEquals(0, startedServiceCount())
    }

    private fun receiver_onReceive(intent: Intent) {
        BootReceiver().onReceive(context, intent)
    }
}
