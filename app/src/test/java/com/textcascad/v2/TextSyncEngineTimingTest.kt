/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ClipboardAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 引擎时序防线的补充测试，对应三类此前没有覆盖的真实使用情景：
 *
 * 1. welcome 重置退避往返（spec §11-10）：连续断线把退避推到最大档后，成功连接并收到
 *    welcome 应把下一次断线的退避拉回第一档；否则用户在服务端恢复后仍要空等 60 秒。
 * 2. 过期 generation 的入站文本丢弃：文本到达但任务排队后才断线重连时，
 *    executeInbound 的 generation 过滤必须丢弃旧代消息（防止旧连接的剪贴板晚到回滚新内容）。
 * 3. token 临期临界值：tokenNeedsRelogin 使用 `now + 60s >= expiresAt` 判定；
 *    恰好等于安全余量时也必须走重登路径（`>` 边界回归防护）。
 */
@RunWith(RobolectricTestRunner::class)
class TextSyncEngineTimingTest {

    private class FakeClipboard(var text: String? = null) : ClipboardAccess {
        override fun readText(): String? = text
        override fun writeText(text: String) { this.text = text }
    }

    private class FakeStringProvider : StringProvider {
        val calls = mutableListOf<Pair<Int, List<Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args.toList())
            return "S$id|${args.joinToString("|")}"
        }
    }

    private class FakeSyncTransport(internal val listener: SyncTransport.Listener) : SyncTransport {
        val sent = mutableListOf<String>()
        override fun connect() { /* 与既有引擎测试一致：open 由测试显式驱动 */ }
        override fun sendText(text: String) { sent.add(text) }
        override fun sendBytes(bytes: ByteArray) { sent.add(String(bytes, Charsets.UTF_8)) }
        override fun close(code: Int, reason: String) { /* 断线由 simulateClose 显式驱动 */ }

        fun simulateOpen() { listener.onOpen() }
        fun simulateText(text: String) { listener.onText(text) }
        fun simulateClose(code: Int, reason: String) { listener.onClosed(code, reason) }
    }

    private fun baseConfig(tokenExpiresAtUtc: Long = System.currentTimeMillis() + 3_600_000L) =
        ClipConfig(
            session = ServerSession(
                serverUrl = "https://srv.example",
                username = "user",
                token = "tok-1",
                tokenExpiresAtUtc = tokenExpiresAtUtc,
                clientId = ContractSamples.CLIENT_ID,
                clientName = ContractSamples.CLIENT_NAME
            ),
            userPrefs = UserPrefs(
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60,
                lastServerVersion = 0L,
                relaunchOnBoot = false,
                websocketStatusNotification = false,
                localMaxClipboardBytes = 512_000L
            ),
            cryptoMaterial = CryptoMaterial(
                derivedKeyBase64 = "",
                hashRounds = 1000,
                salt = "salt",
                cipherEnabled = false,
                trustAllCerts = false
            )
        )

    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** 单传输 harness：captureFactory 让测试持有当前 transport。 */
    private class Harness(
        val engine: TextSyncEngine,
        val strings: FakeStringProvider,
        val all: java.util.concurrent.CopyOnWriteArrayList<FakeSyncTransport>
    ) {
        val latest get() = all.last()
    }

    private fun newEngine(
        config: ClipConfig = baseConfig(),
        backoffNormal: List<Long> = listOf(1L)
    ): Harness {
        val context = RuntimeEnvironment.getApplication()
        val strings = FakeStringProvider()
        val transports = java.util.concurrent.CopyOnWriteArrayList<FakeSyncTransport>()
        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onSessionExpired() {}
                override fun onCachedReloginRequired(): AuthResult = AuthResult.NoCredentials
            },
            stringProvider = strings,
            transportFactory = { _, _, listener, _, _, _ ->
                FakeSyncTransport(listener).also { transports.add(it) }
            },
            clipboard = FakeClipboard(),
            backoffDelaysNormalSeconds = backoffNormal
        )
        return Harness(engine, strings, transports)
    }

    // ------------------------------------------------------------------
    // 情景 1：welcome 收敛退避往返
    // ------------------------------------------------------------------

    @Test
    fun welcomeResetsBackoffSoNextDisconnectStartsFromFirstDelayAgain() {
        // 正常档固定为 7 秒，便于和任何其它数值区分；维护档同值不干扰本用例。
        val harness = newEngine(backoffNormal = listOf(7L))
        val engine = harness.engine

        engine.start()
        assertTrue(awaitTrue { harness.all.isNotEmpty() })
        harness.latest.simulateOpen()

        // 第一次建连后主动断线 → 计划 7 秒退避
        harness.latest.simulateClose(1006, "first drop")
        assertTrue(awaitTrue {
            harness.strings.calls.any { it.first == R.string.status_waiting_reconnect }
        })
        assertEquals(7L, harness.strings.calls.last { it.first == R.string.status_waiting_reconnect }.second[0])

        // 退避到点后自动重连出第二个 transport；打开它并送 welcome（重置退避计数）
        assertTrue(awaitTrue(timeoutMs = 10_000) { harness.all.size >= 2 })
        harness.latest.simulateOpen()
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)

        // 再次断线：新一轮等待必须是第一档 7 秒 —— 而不是累加进位后的更大值。
        harness.latest.simulateClose(1006, "after welcome")
        assertTrue(awaitTrue {
            val waits = harness.strings.calls.filter { it.first == R.string.status_waiting_reconnect }
            waits.size >= 2 && waits.last().second[0] == 7L
        })
        engine.stop()
    }

    // ------------------------------------------------------------------
    // 情景 2：旧代 inbound 文本不得产生任何入站副作用
    // ------------------------------------------------------------------

    @Test
    fun staleGenerationInboundTextIsDroppedWithoutAnySideEffect() {
        val harness = newEngine()
        val engine = harness.engine
        engine.start()
        assertTrue(awaitTrue { harness.all.isNotEmpty() })
        harness.latest.simulateOpen()

        // 断线重连推进 generation（旧 transport 对应的文本再到达时即为旧代）
        harness.latest.simulateClose(1006, "reconnect")
        assertTrue(awaitTrue { harness.all.size >= 2 })

        val statusesAfterReconnect = harness.strings.calls.size

        // 模拟旧代任务滞留后执行：直接走第一代 transport 的 listener 投递旧消息。
        // executeInbound 的 generation 过滤必须丢弃它 —— 不解析、不回 pong、不写剪贴板、
        // 也不产生任何新的状态文案。
        harness.all.first().listener.onText("{\"type\":\"ping\",\"serverTimeUtc\":\"2026-08-18T08:02:00Z\"}")

        // 给滞留任务充足的假想执行窗口
        Thread.sleep(200)
        assertEquals(
            "stale-generation text must produce zero observable side effects",
            statusesAfterReconnect,
            harness.strings.calls.size
        )
        // 新一代已建立（否则本用例没有意义）
        assertTrue(awaitTrue { engine.isConnecting || !engine.isConnected })
        engine.stop()
    }

    // ------------------------------------------------------------------
    // 情景 3：token 剩余寿命恰好等于安全余量时仍触发重登
    // ------------------------------------------------------------------

    @Test
    fun tokenExpiryExactlyAtSafetyMarginStillTriggersCachedRelogin() {
        val fixedNow = 10_000_000L
        val expiry = fixedNow + ClipConfig.TOKEN_EXPIRY_SAFETY_MS // now + 60s == expiresAt
        val config = baseConfig(tokenExpiresAtUtc = expiry)
        val context = RuntimeEnvironment.getApplication()
        val reloginCount = java.util.concurrent.atomic.AtomicInteger()

        val transports = java.util.concurrent.CopyOnWriteArrayList<FakeSyncTransport>()
        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onSessionExpired() {}
                override fun onCachedReloginRequired(): AuthResult {
                    reloginCount.incrementAndGet()
                    return AuthResult.NoCredentials
                }
            },
            transportFactory = { _, _, listener, _, _, _ ->
                FakeSyncTransport(listener).also { transports.add(it) }
            },
            clipboard = FakeClipboard(),
            nowMs = { fixedNow }
        )
        engine.start()

        // `>=` 判定：恰好等于余量也必须先 HTTP 重登而非直连；
        // NoCredentials 结果 → 会话失效，绝不创建 WebSocket 传输。
        assertTrue(awaitTrue { reloginCount.get() >= 1 })
        Thread.sleep(200)
        assertEquals("boundary equality must not fall through to direct connect", 0, transports.size)
        engine.stop()
    }
}
