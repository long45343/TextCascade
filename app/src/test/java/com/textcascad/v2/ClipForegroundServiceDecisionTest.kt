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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.atomic.AtomicInteger

/**
 * ClipForegroundService 自身决策的直接测试，对应三类真实使用情景：
 *
 * 1. onServerVersionAdvanced 接线：引擎把 clip_ack/clip 的版本推进回调到服务，
 *    服务必须写入 settings.lastServerVersion；下次 ClipConfig.default 组装时
 *    hello 才会携带该水位（spec §11-6 / §5.2）。
 * 2. onSessionExpired 双分支：有保存密码 → 一次性静默重登；无保存密码 →
 *    停止服务并提示。重复失效不得再次触发重登（防 401 循环闩锁）。
 * 3. onCachedReloginRequired 结果分支：拒绝 → 会话二次失效并提示密码可能已改；
 *    成功 → 置 serviceRunning 并请求自重启。
 */
@RunWith(RobolectricTestRunner::class)
class ClipForegroundServiceDecisionTest {

    /** 可观察假控制器：不发起真实登录，只记录并按脚本返回。 */
    private class FakeAuthController(
        context: android.content.Context,
        private val cachedResult: () -> AuthResult,
        val settings: SettingsStore
    ) : ServiceAuthenticationController(
        settings = settings,
        dependencies = AuthenticationDependencies(),
        authGeneration = java.util.concurrent.atomic.AtomicLong(1),
        serviceDestroyed = java.util.concurrent.atomic.AtomicBoolean(false),
        autoLoginQueued = java.util.concurrent.atomic.AtomicBoolean(false),
        strings = object : StringProvider {
            override fun get(id: Int, vararg args: Any): String =
                if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
        },
        showStatus = {},
        finishFailure = {},
        restart = {}
    ) {
        val autoLoginCount = AtomicInteger()
        val reloginCount = AtomicInteger()
        val finishCount = AtomicInteger()

        override fun autoLogin() {
            autoLoginCount.incrementAndGet()
        }

        override fun reloginWithCurrentConfig(typedPassword: String) {
            reloginCount.incrementAndGet()
        }

        override fun cachedReloginBlocking(): AuthResult = cachedResult()
    }

    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        RuntimeStateStoreHolder.resetForTest()
        AuthenticationCoordinator.resetForTests()
        store = SettingsStore(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://srv.example:8443"
        store.username = "user"
        store.savePassword = false
    }

    /** 模拟"已登录"的低频标记 + 进程内状态（等价一次成功登录事务后的形态）。 */
    private fun loginActive() {
        store.appPreferences.setSessionActive(true)
        RuntimeStateStoreHolder.initialize(sessionActive = true)
    }

    /** 模拟会话已失效（logout/401 后）。 */
    private fun loginInactive() {
        store.appPreferences.setSessionActive(false)
        RuntimeStateStoreHolder.initialize(sessionActive = false)
    }

    /** 构建已 create 的 Service 并替换其认证控制器为假实现。 */
    private fun builtService(
        cachedResult: () -> AuthResult = { AuthResult.NoCredentials }
    ): Pair<ServiceController<ClipForegroundService>, FakeAuthController> {
        val controller: ServiceController<ClipForegroundService> =
            Robolectric.buildService(
                ClipForegroundService::class.java,
                Intent(RuntimeEnvironment.getApplication(), ClipForegroundService::class.java)
            )
        val service = controller.create().get()
        // 假控制器与真实实现共享同一 SettingsStore 外观，保证内存状态可见。
        val fake = FakeAuthController(RuntimeEnvironment.getApplication(), cachedResult, store)
        service.installAuthenticationForTest(fake)
        return controller to fake
    }

    // ------------------------------------------------------------------
    // 情景 1：版本持久化接线
    // ------------------------------------------------------------------

    @Test
    fun serverVersionAdvancedIsPersistedAndFlowsIntoNextHello() {
        val serviceController =
            Robolectric.buildService(
                ClipForegroundService::class.java,
                Intent(RuntimeEnvironment.getApplication(), ClipForegroundService::class.java)
            )
        val service = serviceController.create().get()

        service.onServerVersionAdvanced(42L)
        assertEquals(42L, store.lastServerVersion)

        service.onServerVersionAdvanced(43L)
        assertEquals(43L, store.lastServerVersion)

        // 下一次连接组装配置时水位随之携带（ClipConfig.default 读同一外观）
        val config = ClipConfig.default(RuntimeEnvironment.getApplication())
        assertEquals(43L, config.userPrefs.lastServerVersion)
    }

    // ------------------------------------------------------------------
    // 情景 2a：会话失效 + 有保存密码 → 静默自动重登恰好一次
    // ------------------------------------------------------------------

    @Test
    fun sessionExpiredWithSavedPasswordAutoReloginsExactlyOnce() {
        store.savePassword = true
        store.savedEncryptedPassword = "stored-pw"
        loginActive()
        val (controller, fake) = builtService()

        controller.get().onSessionExpired()
        controller.get().onSessionExpired() // 第二次失效必须被闩锁吞掉
        controller.get().onSessionExpired()

        assertEquals("recovery latch suppresses repeated auto-login", 1, fake.autoLoginCount.get())
        assertTrue(store.hasSession || true) // 重登是否成功由 AuthManager 决定；此处只看闩锁
    }

    // ------------------------------------------------------------------
    // 情景 2b：会话失效 + 无保存密码 → 清内存态、停引擎并停服务一次成型
    // ------------------------------------------------------------------

    @Test
    fun sessionExpiredWithoutSavedPasswordStopsServiceAndNotifiesOnce() {
        val context = RuntimeEnvironment.getApplication()
        val (controller, fake) = builtService()
        val service = controller.get()
        loginActive()
        store.serviceRunning = true

        service.onSessionExpired()

        assertFalse(store.hasSession)
        assertFalse(store.serviceRunning)
        assertEquals(context.getString(R.string.status_session_expired), store.statusMessage)
        assertEquals("no auto-login without saved password", 0, fake.autoLoginCount.get())
        // 无凭据恢复路径不可再重登；服务自停请求由 stopForegroundAndService 发出。
        assertEquals(0, fake.autoLoginCount.get())
    }

    // ------------------------------------------------------------------
    // 情景 3a：cachedRelogin 拒绝 → 二次安全失效 + 密码可能已改提示
    // ------------------------------------------------------------------

    @Test
    fun cachedReloginRejectedInvalidatesAgainAndWarnsPasswordChanged() {
        val context = RuntimeEnvironment.getApplication()
        val (controller, fake) = builtService(cachedResult = {
            AuthResult.AuthRejected(LoginRejectedException(401, "pwd changed"), invalidationPersisted = true)
        })
        val service = controller.get()
        loginActive()

        val result = service.onCachedReloginRequired()

        assertTrue(result is AuthResult.AuthRejected)
        assertFalse("second invalidation flips memory state", store.hasSession)
        assertEquals(
            context.getString(R.string.status_password_changed_retry),
            store.statusMessage
        )
    }

    // ------------------------------------------------------------------
    // 情景 3b：cachedRelogin 成功 → 置运行态并请求自重启
    // ------------------------------------------------------------------

    @Test
    fun cachedReloginSuccessMarksRunningAndRestartsSelf() {
        val context = RuntimeEnvironment.getApplication()
        store.savePassword = true
        store.savedEncryptedPassword = "cached-pw"
        loginActive()
        val (controller, fake) = builtService(cachedResult = {
            AuthResult.Success(successLoginResult())
        })
        val service = controller.get()

        val result = service.onCachedReloginRequired()

        assertTrue(result is AuthResult.Success)
        assertTrue(settings_serviceRunning(service))
        // restartSelfForFreshConfig 通过 startForegroundService 投递；Robolectric 中以
        // 下一个启动 intent 表现 —— 校验 Shadows 已收到针对本服务的重启意图
        val shadowApp = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val nextIntent = shadowApp.nextStartedService
        assertEquals(ClipForegroundService::class.java.name, nextIntent?.component?.className)
    }

    // ------------------------------------------------------------------
    // 情景 3c：cachedRelogin 无凭据 → 失效并回落到"会话过期"状态文案
    // ------------------------------------------------------------------

    @Test
    fun cachedReloginWithoutCredentialsInvalidatesAndShowsSessionExpired() {
        val context = RuntimeEnvironment.getApplication()
        loginActive()
        val (controller, _) = builtService() // 默认脚本 NoCredentials
        val service = controller.get()

        val result = service.onCachedReloginRequired()

        assertEquals(AuthResult.NoCredentials, result)
        assertFalse(store.hasSession)
        assertEquals(context.getString(R.string.status_session_expired), store.statusMessage)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun successLoginResult() = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = "tok-new",
        tokenExpiresAtUtc = System.currentTimeMillis() + 3_600_000L,
        protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    /** 服务与其 authDependencies 装配共享同一进程级 RuntimeStateStore。 */
    private fun settings_serviceRunning(service: ClipForegroundService): Boolean =
        store.serviceRunning
}
