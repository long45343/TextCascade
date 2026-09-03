/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ConnectionManager
import com.textcascad.v2.engine.SyncStateStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q3 恢复活动信号 → 无条件重连（亮屏/解锁/Doze 退出任一）：
 * CONNECTED 强制刷新；DISCONNECTED 取消退避立即重连；CONNECTING 新鲜忽略/陈旧强制刷新；
 * SCREEN_ON→USER_PRESENT 5s 防抖；STOPPED 无操作。
 */
class ConnectionManagerAwakeTest {

    private class FakeStringProvider : StringProvider {
        val calls = mutableListOf<Pair<Int, Array<out Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args)
            return "S$id|${args.joinToString("|") { it.toString() }}"
        }
    }

    /** connect() 不自动 open 的假传输：便于把连接留在 CONNECTING 态。 */
    private class FakeTransport(private val listener: SyncTransport.Listener) : SyncTransport {
        val connectCount = AtomicInteger()
        override fun connect() { connectCount.incrementAndGet() }
        override fun sendText(text: String) {}
        override fun close(code: Int, reason: String) {}
        fun simulateOpen() { listener.onOpen() }
        fun simulateClosed(code: Int, reason: String) = listener.onClosed(code, reason)
    }

    private fun managerConfig() = ClipConfig(
        session = ServerSession(
            serverUrl = "https://example.invalid",
            username = "user",
            token = "token-1",
            tokenExpiresAtUtc = 0L,
            clientId = "client",
            clientName = "test"
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

    private class Harness(
        val manager: ConnectionManager,
        val transports: CopyOnWriteArrayList<FakeTransport>,
        private val clock: LongArray
    ) {
        var now: Long
            get() = clock[0]
            set(value) { clock[0] = value }
    }

    private fun newHarness(normalDelays: List<Long> = listOf(60L), initialNow: Long = 1_000_000L): Harness {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val clock = longArrayOf(initialNow)
        val manager = ConnectionManager(
            config = managerConfig(),
            state = SyncStateStore(0L),
            executorFactory = {
                Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "test-awake").apply { isDaemon = true }
                }
            },
            transportFactory = { _, _, listener, _, _, _ ->
                FakeTransport(listener).also { transports.add(it) }
            },
            nowMs = { clock[0] },
            stringProvider = FakeStringProvider(),
            rateLimitedReloginFloorSeconds = 30L,
            backoffDelaysNormalSeconds = normalDelays,
            backoffDelaysMaintenanceSeconds = listOf(1L, 2L, 5L, 10L),
            onCachedReloginRequired = { AuthResult.NoCredentials },
            onStatus = {},
            onConnected = { _, _ -> },
            onInboundText = { _, _ -> },
            onClosed = { _, _ -> },
            onSessionExpired = {}
        )
        return Harness(manager, transports, clock)
    }

    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun awakeWhileConnectedForcesReconnect() {
        val harness = newHarness()
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.transports.first().simulateOpen()
        val generationBefore = harness.manager.connectionGenerationForTest()
        harness.manager.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        assertTrue(harness.manager.connectionGenerationForTest() > generationBefore)
        harness.manager.stop()
    }

    @Test
    fun awakeWhileBackoffCancelsTaskAndReconnectsImmediately() {
        val harness = newHarness(normalDelays = listOf(60L))
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.transports.first().simulateOpen()
        harness.transports.first().simulateClosed(1006, "eof")
        // 60s 退避挂起中：恢复信号必须立即重建连接，而不是等退避到点
        harness.manager.onDeviceAwake()
        assertTrue(awaitTrue(3_000) { harness.transports.size >= 2 })
        harness.manager.stop()
    }

    @Test
    fun freshConnectingWithinStaleGuardIsIgnored() {
        val harness = newHarness()
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        // 停留在 CONNECTING（不 simulateOpen）
        val t0 = harness.now
        harness.manager.onDeviceAwake() // 首次信号：CONNECTING 且 0s < 30s → 忽略
        harness.now = t0 + 1_000
        harness.manager.onDeviceAwake() // 5s 防抖窗口内 → 忽略
        harness.now = t0 + 11_000
        harness.manager.onDeviceAwake() // CONNECTING 且 11s < 30s → 忽略
        Thread.sleep(200)
        assertEquals(1, harness.transports.size)
        harness.manager.stop()
    }

    @Test
    fun staleConnectingBeyondGuardForcesReconnect() {
        val harness = newHarness()
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.now += 31_000
        harness.manager.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.manager.stop()
    }

    @Test
    fun consecutiveAwakeSignalsDebounceToSingleReconnect() {
        val harness = newHarness(normalDelays = listOf(60L))
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.transports.first().simulateOpen()
        harness.transports.first().simulateClosed(1006, "eof")
        // 模拟 SCREEN_ON → USER_PRESENT（相隔 1-2s）：仅一次重连生效
        harness.manager.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.transports[1].simulateOpen() // 新连接已建立
        harness.now += 1_000
        harness.manager.onDeviceAwake() // 防抖窗口内：不得再触发 forceReconnect
        Thread.sleep(200)
        assertEquals(2, harness.transports.size)
        harness.manager.stop()
    }

    @Test
    fun awakeWhileStoppedIsNoOp() {
        val harness = newHarness()
        harness.manager.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.manager.stop()
        val transportsAfterStop = harness.transports.size
        harness.manager.onDeviceAwake()
        Thread.sleep(200)
        assertEquals(transportsAfterStop, harness.transports.size)
        assertTrue(harness.manager.isStopped)
    }
}
