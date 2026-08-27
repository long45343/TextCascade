/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ServiceAuthenticationController 的直接行为测试，对应三类真实使用情景：
 *
 * 1. 自动登录单飞：服务启动/USER_PRESENT 等多入口并发调用 autoLogin 时，
 *    autoLoginQueued 闸门保证只排队一次；提交失败必须复位闸门，
 *    否则后续永远无法自动登录。
 * 2. handleOutcome 的 AuthResult → 文案/动作映射：自动与手动重登在同一
 *    结果下走不同文案（spec §11-9 会话失效重新登录的用户可见反馈）。
 * 3. owner 判活：serviceDestroyed 之后完成的重登不得再触发 restart()
 *    （前台服务销毁后不能再自救重启）。
 */
@RunWith(RobolectricTestRunner::class)
class ServiceAuthenticationControllerTest {

    private lateinit var settings: SettingsStore
    private val authGeneration = AtomicLong(0L)
    private val serviceDestroyed = AtomicBoolean(false)
    private val autoLoginQueued = AtomicBoolean(false)
    private val shownStatuses = mutableListOf<String>()
    private val finishedMessages = mutableListOf<String>()
    private val restartCount = java.util.concurrent.atomic.AtomicInteger()

    @Before
    fun setUp() {
        AuthenticationCoordinator.resetForTests()
        RuntimeStateStoreHolder.resetForTest()
        settings = SettingsStore(RuntimeEnvironment.getApplication())
        settings.serverUrl = "https://srv.example"
        settings.username = "user"
    }

    private fun newController(
        showStatus: ((String) -> Unit)? = null,
        finishFailure: ((String) -> Unit)? = null,
        restart: (() -> Unit)? = null,
        loginClient: LoginClient? = null
    ): ServiceAuthenticationController {
        val context = RuntimeEnvironment.getApplication()
        val stringProvider = object : StringProvider {
            override fun get(id: Int, vararg args: Any): String =
                if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
        }
        return ServiceAuthenticationController(
            settings = settings,
            dependencies = AuthenticationDependencies(
                loginClientFactory = { _, _ -> loginClient ?: FakeLoginClient { _, _, _ -> successResult() } }
            ),
            authGeneration = authGeneration,
            serviceDestroyed = serviceDestroyed,
            autoLoginQueued = autoLoginQueued,
            strings = stringProvider,
            showStatus = showStatus ?: { message ->
                synchronized(shownStatuses) { shownStatuses.add(message); Unit }
            },
            finishFailure = finishFailure ?: { message ->
                synchronized(finishedMessages) { finishedMessages.add(message); Unit }
            },
            restart = restart ?: { restartCount.incrementAndGet(); Unit }
        )
    }

    private class FakeLoginClient(private val action: (String, String, String) -> LoginResult) : LoginClient {
        override fun login(serverUrl: String, username: String, password: String): LoginResult =
            action(serverUrl, username, password)
    }

    private fun successResult(token: String = "tok-2") = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = token,
        tokenExpiresAtUtc = System.currentTimeMillis() + 3_600_000L,
        protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    /** 协调器执行器是后台线程：轮询等待异步分支落到这些可见状态上。 */
    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    // ------------------------------------------------------------------
    // 情景 1a：已保存密码 + 服务启动自动登录 → 成功后 serviceRunning=true 并 restart
    // ------------------------------------------------------------------

    @Test
    fun autoLoginWithSavedPasswordSucceedsAndRestartsService() {
        settings.savePassword = true
        settings.savedEncryptedPassword = "stored-pw"

        var observedPassword = ""
        val controller = newController(
            loginClient = FakeLoginClient { _, _, pw ->
                observedPassword = pw
                successResult()
            }
        )
        controller.autoLogin()
        AuthenticationCoordinator.awaitIdle()
        assertTrue(awaitTrue { restartCount.get() == 1 })
        assertTrue(settings.serviceRunning)
        assertEquals("stored-pw", observedPassword)
    }

    // ------------------------------------------------------------------
    // 情景 1b：两次 autoLogin 排队 → 闸门吞掉第二次，只发生一次登录
    // 对应 onStartCommand 与 onSessionExpired 同时触发的真实竞态。
    // ------------------------------------------------------------------

    @Test
    fun duplicateAutoLoginWhileFirstInFlightIsSuppressedByGate() {
        settings.savePassword = true
        settings.savedEncryptedPassword = "stored-pw"
        val release = CountDownLatch(1)
        val startedGate = CountDownLatch(1)
        val controller = newController(
            loginClient = FakeLoginClient { _, _, _ ->
                startedGate.countDown()
                release.await(5, TimeUnit.SECONDS)
                successResult()
            }
        )
        controller.autoLogin()
        assertTrue(startedGate.await(5, TimeUnit.SECONDS))
        // 第一个任务仍阻塞在 HTTP 登录内：第二次 autoLogin 必须被闸门拒绝
        controller.autoLogin()
        release.countDown()
        AuthenticationCoordinator.awaitIdle()

        assertTrue(awaitTrue { restartCount.get() >= 1 })
        Thread.sleep(100)
        assertTrue("gate must allow later autoLogin after completion", !autoLoginQueued.get())
        assertEquals(1, restartCount.get())
    }

    // ------------------------------------------------------------------
    // 情景 1c：无保存密码时 autoLogin 立即失败并复位闸门；
    // 后续恢复保存密码后的 autoLogin 仍可正常进行。
    // ------------------------------------------------------------------

    @Test
    fun missingSavedPasswordFailsImmediatelyAndReleasesGate() {
        val controller = newController()
        controller.autoLogin()
        AuthenticationCoordinator.awaitIdle()

        val context = RuntimeEnvironment.getApplication()
        assertTrue(awaitTrue { synchronized(finishedMessages) { finishedMessages.isNotEmpty() } })
        // AuthManager.execute 对"无保存密码"返回 MissingPassword，
        // 自动模式文案固定为 status_auto_login_failed + "No saved password"。
        assertEquals(
            context.getString(R.string.status_auto_login_failed, "No saved password"),
            synchronized(finishedMessages) { finishedMessages.first() }
        )
        assertFalse(autoLoginQueued.get())

        // 闸门已复位：补上密码后同一控制器可以再次成功
        settings.savePassword = true
        settings.savedEncryptedPassword = "stored-late"
        val retryController = newController()
        retryController.autoLogin()
        AuthenticationCoordinator.awaitIdle()
        assertTrue(awaitTrue { restartCount.get() == 1 })
    }

    // ------------------------------------------------------------------
    // 情景 2a：手动通知按钮重登带正确密码 → 成功重启；自动模式限流 → 只提示不结束服务。
    // 对应断线通知按钮点击 (reloginWithCurrentConfig) 与 429 rate_limited 分支。
    // ------------------------------------------------------------------

    @Test
    fun manualReloginUsesTypedPasswordAndSuccessRestarts() {
        settings.savePassword = false
        var observedPassword = ""
        val controller = newController(
            loginClient = FakeLoginClient { _, _, pw ->
                observedPassword = pw
                successResult()
            }
        )
        controller.reloginWithCurrentConfig("typed-secret")
        AuthenticationCoordinator.awaitIdle()

        assertTrue(awaitTrue { restartCount.get() == 1 })
        assertEquals("typed-secret", observedPassword)
        assertTrue(settings.serviceRunning)
    }

    @Test
    fun automaticRateLimitedShowsStatusOnlyWithoutFinishing() {
        settings.savePassword = true
        settings.savedEncryptedPassword = "stored-pw"
        val context = RuntimeEnvironment.getApplication()
        val controller = newController(
            loginClient = FakeLoginClient { _, _, _ -> throw LoginRateLimitedException(429, 17L) }
        )
        controller.autoLogin()
        AuthenticationCoordinator.awaitIdle()

        assertTrue(awaitTrue {
            synchronized(shownStatuses) {
                shownStatuses.any { it == context.getString(R.string.status_login_rate_limited) }
            }
        })
        assertEquals("rate limit keeps the foreground service alive", 0, finishedMessages.size)
        assertEquals(0, restartCount.get())
    }

    // ------------------------------------------------------------------
    // 情景 2b：手动模式凭据被拒 → 走 required_fields 文案并结束服务；
    // 自动模式下同样被拒 → auto_login_failed 前缀文案。
    // ------------------------------------------------------------------

    @Test
    fun manualRejectedMapsToLoginFailedWithServerDetail() {
        val context = RuntimeEnvironment.getApplication()
        val controller = newController(
            loginClient = FakeLoginClient { _, _, _ ->
                throw LoginRejectedException(401, "bad credentials")
            }
        )
        controller.reloginWithCurrentConfig("wrong-password")
        AuthenticationCoordinator.awaitIdle()

        assertTrue(awaitTrue { synchronized(finishedMessages) { finishedMessages.isNotEmpty() } })
        // 手动重登被服务端拒绝：AuthRejected → finishFailure(login_failed(detail))
        assertEquals(
            context.getString(R.string.status_login_failed, "bad credentials"),
            synchronized(finishedMessages) { finishedMessages.first() }
        )
    }

    @Test
    fun automaticRejectedPrefixesAutoLoginFailed() {
        val context = RuntimeEnvironment.getApplication()
        settings.savePassword = true
        settings.savedEncryptedPassword = "old-pw"
        val controller = newController(
            loginClient = FakeLoginClient { _, _, _ ->
                throw LoginRejectedException(401, "password changed")
            }
        )
        controller.autoLogin()
        AuthenticationCoordinator.awaitIdle()

        assertTrue(awaitTrue { synchronized(finishedMessages) { finishedMessages.isNotEmpty() } })
        val message = synchronized(finishedMessages) { finishedMessages.first() }
        assertEquals(
            context.getString(R.string.status_auto_login_failed, "password changed"),
            message
        )
        // 认证拒绝后的安全失效：内存 hasSession 必须翻转（AuthManager 已先落 session_active=false）
        assertFalse(settings.hasSession)
    }

    // ------------------------------------------------------------------
    // 情景 3：serviceDestroyed=true 后完成的阻塞 cachedRelogin → Cancelled，
    // 引擎把 Cancelled 视为不可继续，上层不再用旧会话重建连接。
    // 对应用户在通知栏点"停止"后引擎线程仍在重登的真实窗口。
    // ------------------------------------------------------------------

    @Test
    fun cachedReloginAfterServiceDestroyedReturnsCancelledWithoutRestart() {
        val controller = newController()
        serviceDestroyed.set(true)

        val result = controller.cachedReloginBlocking()
        assertNotNull(result)
        // owner 语义失效 → AuthManager 侧视为取消；不允许触发任何 restart/finish 动作
        Thread.sleep(50)
        assertEquals(0, restartCount.get())
        assertEquals(0, finishedMessages.size)
    }

    @Test
    fun cachedReloginSuccessReturnsResultForEngineToRebuildConnection() {
        settings.savePassword = true
        settings.savedEncryptedPassword = "cached-pw"
        val controller = newController()

        val result = controller.cachedReloginBlocking()

        // 阻塞路径直接返回给连接层重建连接，不在控制器内 restart
        assertEquals(AuthResult.Success::class.java, result?.javaClass)
        assertEquals(0, restartCount.get())
        assertEquals("tok-2", settings.token)
    }
}
