package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSyncEngineTwoPhaseReconnectTest {

    @Test
    fun cookiePhaseAllowsOnlyTwoScheduledReconnects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(
            websocketUrl = "ws://127.0.0.1:1",
            cookieHeader = "test-cookie"
        )

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("network error"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()

        awaitCondition {
            totalConnects.get() >= 3 && reloginCount.get() >= 1
        }

        assertEquals("Transport should connect 3 times (1 initial + 2 cookie reconnects)", 3, totalConnects.get())
        assertTrue("Cached relogin callback should have been called", reloginCount.get() >= 1)

        engine.stop()
    }

    @Test
    fun transientHttpFailureKeepsCachedReloginPhase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("HTTP 500"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()

        awaitCondition { reloginCount.get() >= 2 }

        assertEquals("WebSocket connects stay at 3", 3, totalConnects.get())
        assertTrue("Relogin retried in HTTP phase", reloginCount.get() >= 2)

        engine.stop()
    }

    @Test
    fun authFailureRetriesCachedRelogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.AuthFailure
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()

        awaitCondition { reloginCount.get() >= 2 }

        assertEquals("WebSocket connects stay at 3", 3, totalConnects.get())
        assertTrue("Relogin should retry on AuthFailure", reloginCount.get() >= 2)

        engine.stop()
    }

    @Test
    fun successfulCookieReconnectResetsAttemptCounter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        var listenerRef: StompClient.Listener? = null
        val totalConnects = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
            },
            stompClientFactory = { _, _, listener, _ ->
                listenerRef = listener
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { totalConnects.get() == 1 }

        listenerRef?.onConnected()
        assertTrue(engine.isConnected)

        listenerRef?.onError(ConnectException("lost"))

        awaitCondition { totalConnects.get() >= 2 }

        assertTrue(totalConnects.get() >= 2)

        engine.stop()
    }

    @Test
    fun forceReconnectRestartsFromCookiePhase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { reloginCount.get() >= 1 }

        val connectsBeforeForce = totalConnects.get()
        assertEquals("Should be 3 connects before forceReconnect", 3, connectsBeforeForce)

        engine.forceReconnect()

        awaitCondition { totalConnects.get() > connectsBeforeForce }
        assertTrue("Force reconnect should trigger immediate connect", totalConnects.get() > connectsBeforeForce)

        engine.stop()
    }

    @Test
    fun noCredentialsFallsBackToSessionExpired() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val expiredCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onSessionExpired() {
                    expiredCount.incrementAndGet()
                }
                override fun onCachedReloginRequired(): CachedReloginResult {
                    return CachedReloginResult.NoCredentials
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { expiredCount.get() >= 1 }

        assertEquals("onSessionExpired should be called once", 1, expiredCount.get())

        engine.stop()
    }

    @Test
    fun successfulCachedReloginStopsOldEngineScheduling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val fakeResult = LoginResult(
            normalizedServerUrl = "http://127.0.0.1:8080",
            websocketUrl = "ws://127.0.0.1:8080/ws",
            passwordSha3 = "sha3",
            hashedPasswordBase64 = "key",
            csrfToken = "csrf",
            cookieHeader = "cookie",
            maxSizeBytes = 1024
        )

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.Success(fakeResult)
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { reloginCount.get() == 1 }

        Thread.sleep(100)
        assertEquals("Websocket connects stay at 3 after success", 3, totalConnects.get())
        assertEquals("Relogin called exactly once on success", 1, reloginCount.get())

        engine.stop()
    }

    @Test
    fun stopCancelsPendingCachedRelogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 100L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        engine.stop()

        assertTrue(engine.isStopped)
        assertEquals("Relogin should not be called after stop", 0, reloginCount.get())

        engine.start()
        assertFalse(engine.isStopped)
        engine.stop()
    }

    // T1: 解锁加速 HTTP 阶段等待，不绕回旧 cookie
    @Test
    fun userPresentAcceleratesPendingCachedReloginWithoutCookieReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { firstDisc ->
                // 第 1、2 次立即走，第 3 次设置长延迟（60s）
                if (totalConnects.get() >= 3) 60L else 0L
            },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()

        // 等待前两次 cookie 重连完成并到达第 3 次（HTTP 排线阶段，有 60s 延迟）
        awaitCondition { totalConnects.get() == 3 }
        assertEquals("Should connect 3 times before user present", 3, totalConnects.get())
        assertEquals("Relogin not called yet due to 60s delay", 0, reloginCount.get())

        // 触发解锁恢复
        engine.reconnectAfterUserPresent()

        // 验证 relogin 在 0s 延迟下被触发，且不会重置到 cookie
        awaitCondition { reloginCount.get() >= 1 }
        assertEquals("Relogin should be called after user present", 1, reloginCount.get())
        assertEquals("Websocket connects stay at 3 without reset", 3, totalConnects.get())

        engine.stop()
    }

    // T2: 重复解锁合并为一次动作
    @Test
    fun repeatedUserPresentRequestsCoalesceToOneAttempt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { if (totalConnects.get() >= 3) 60L else 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { totalConnects.get() == 3 }

        // 连续调用 3 次
        engine.reconnectAfterUserPresent()
        engine.reconnectAfterUserPresent()
        engine.reconnectAfterUserPresent()

        awaitCondition { reloginCount.get() >= 1 }
        Thread.sleep(50)

        assertEquals("Should coalesce to 1 relogin call", 1, reloginCount.get())
        assertEquals("WebSocket connects stay at 3", 3, totalConnects.get())

        engine.stop()
    }

    // T3: callback 执行期间解锁不并发打断或重复登录
    @Test
    fun userPresentDuringCachedReloginDoesNotStartSecondLogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    latch.await(5, TimeUnit.SECONDS)
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()

        // 等待进入第 1 次 relogin 尝试（处于阻塞状态）
        awaitCondition { reloginCount.get() == 1 }

        // 此时在 callback 执行期间触发解锁
        engine.reconnectAfterUserPresent()
        Thread.sleep(50)

        assertEquals("No second relogin call while first is in progress", 1, reloginCount.get())

        // 释放 latch 允许第 1 次 login 结束
        latch.countDown()

        engine.stop()
    }

    // T4: 解锁移动 cookie 尝试不增加额外的 cookie 名额
    @Test
    fun userPresentMovesCookieAttemptWithoutAddingExtraQuota() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 60L }, // 所有重连排队均使用长延迟
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { totalConnects.get() == 1 }

        // 第 1 次 cookie 连接失败后，处于 60s 延迟等待中。调用解锁提速
        engine.reconnectAfterUserPresent()
        awaitCondition { totalConnects.get() == 2 }

        // 第 2 次 cookie 连接失败后，处于 60s 延迟等待中。再次调用解锁提速
        engine.reconnectAfterUserPresent()
        awaitCondition { totalConnects.get() == 3 }

        // 第 3 次 HTTP 重登也处于 60s 延迟等待中。再次调用解锁提速 HTTP 尝试
        engine.reconnectAfterUserPresent()
        awaitCondition { reloginCount.get() >= 1 }

        assertEquals("Total connects must be 3 (1 initial + 2 cookie attempts)", 3, totalConnects.get())
        assertTrue("HTTP relogin triggered without extra cookie quota", reloginCount.get() >= 1)

        engine.stop()
    }

    // T6: 已连接时解锁 no-op
    @Test
    fun userPresentIsNoOpWhenConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        var listenerRef: StompClient.Listener? = null
        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                listenerRef = listener
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L },
            userPresentReconnectDelaySeconds = 0L
        )

        engine.start()
        awaitCondition { totalConnects.get() == 1 }

        listenerRef?.onConnected()
        assertTrue(engine.isConnected)

        engine.reconnectAfterUserPresent()
        Thread.sleep(50)

        assertEquals("No extra connect when connected", 1, totalConnects.get())
        assertEquals("No relogin call when connected", 0, reloginCount.get())

        engine.stop()
    }

    // T7: stop 取消解锁任务
    @Test
    fun stopCancelsUserPresentTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 60L },
            userPresentReconnectDelaySeconds = 10L // 长延迟
        )

        engine.start()
        engine.reconnectAfterUserPresent()
        engine.stop()

        assertTrue(engine.isStopped)
        assertEquals("Relogin not called after stop", 0, reloginCount.get())

        engine.start()
        assertFalse(engine.isStopped)
        engine.stop()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
    }
}
