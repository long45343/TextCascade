/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.os.Looper
import com.textcascad.v2.engine.ClipboardAccess
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Q1 pending 暂存与补发（对齐桌面 v2.3.5 Q3/Q12）：
 * 未连接/发送失败 → 暂存（仅最新）+ forceReconnect；welcome 后未被远端更新取代则重发；
 * Suppressed/RateLimited/TooLarge 不暂存。
 */
@RunWith(RobolectricTestRunner::class)
class TextSyncEnginePendingTest {

    private class FakeClipboard(var text: String? = null) : ClipboardAccess {
        override fun readText(): String? = text
        override fun writeText(text: String) { this.text = text }
    }

    private class FakeStringProvider : StringProvider {
        val calls = CopyOnWriteArrayList<Pair<Int, Array<out Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args)
            return "S$id|${args.joinToString("|") { it.toString() }}"
        }
    }

    /** 模拟 OkHttpTransport 语义：未 open 时 sendText 抛 IOException（连接中/半开）。 */
    private class FakeSyncTransport(private val listener: SyncTransport.Listener) : SyncTransport {
        val sent = CopyOnWriteArrayList<String>()
        val connectCount = AtomicInteger()
        @Volatile var opened = false
        @Volatile var closedCode: Int? = null
        @Volatile var sendTextException: Exception? = null

        override fun connect() { connectCount.incrementAndGet() }

        override fun sendText(text: String) {
            sendTextException?.let { throw it }
            if (!opened) throw IOException("WebSocket is not connected")
            sent.add(text)
        }

        override fun close(code: Int, reason: String) {
            closedCode = code
            opened = false
        }

        fun simulateOpen() { opened = true; listener.onOpen() }
        fun simulateText(text: String) = listener.onText(text)
        fun simulateClosed(code: Int, reason: String) {
            opened = false
            listener.onClosed(code, reason)
        }
    }

    private class RecordingCallbacks : TextSyncEngine.Callbacks {
        val statuses = CopyOnWriteArrayList<String>()
        val appliedTexts = CopyOnWriteArrayList<String>()
        override fun onStatus(message: String) { statuses.add(message) }
        override fun onRemoteTextApplied(text: String) { appliedTexts.add(text) }
        override fun onSessionExpired() {}
        override fun onCachedReloginRequired(): AuthResult = AuthResult.NoCredentials
        override fun onServerVersionAdvanced(version: Long) {}
    }

    private class Harness(
        val engine: TextSyncEngine,
        val callbacks: RecordingCallbacks,
        val strings: FakeStringProvider,
        val transports: CopyOnWriteArrayList<FakeSyncTransport>,
        val clipboard: FakeClipboard
    ) {
        val latest get() = transports.last()
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun newHarness(
        backoffNormalSeconds: List<Long> = listOf(60L),
        config: ClipConfig = baseConfig(),
        nowMs: () -> Long = System::currentTimeMillis
    ): Harness {
        val context = RuntimeEnvironment.getApplication()
        val transports = CopyOnWriteArrayList<FakeSyncTransport>()
        val clipboard = FakeClipboard()
        val strings = FakeStringProvider()
        val callbacks = RecordingCallbacks()
        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = callbacks,
            stringProvider = strings,
            transportFactory = { _, _, listener, _, _, _ ->
                FakeSyncTransport(listener).also { transports.add(it) }
            },
            nowMs = nowMs,
            clipboard = clipboard,
            backoffDelaysNormalSeconds = backoffNormalSeconds
        )
        return Harness(engine, callbacks, strings, transports, clipboard)
    }

    private fun disconnectedHarness(
        backoffNormalSeconds: List<Long> = listOf(60L),
        nowMs: () -> Long = System::currentTimeMillis
    ): Harness {
        val harness = newHarness(backoffNormalSeconds = backoffNormalSeconds, nowMs = nowMs)
        harness.engine.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        harness.latest.simulateClosed(1006, "eof")
        return harness
    }

    private fun baseConfig(): ClipConfig = ClipConfig(
        session = ServerSession(
            serverUrl = "https://srv.example",
            username = "user",
            token = "tok-1",
            tokenExpiresAtUtc = System.currentTimeMillis() + 3_600_000L,
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

    // ---------------- 暂存 ----------------

    @Test
    fun sendWhileDisconnectedStashesPendingAndForcesReconnect() {
        val harness = disconnectedHarness()
        val generationBefore = harness.engine.connectionGenerationForTest()
        // 半开连接上的发送失败（未 open 抛 IOException）→ 暂存 + 强制重连
        harness.engine.sendLocalText("stashed text", "test")
        assertTrue(awaitTrue { harness.engine.connectionGenerationForTest() > generationBefore })
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        // 失败的发送未在任何传输上产生 clip
        assertTrue(harness.transports.none { it.sent.any { t -> t.contains("\"type\":\"clip\"") } })
        harness.engine.stop()
    }

    // ---------------- welcome 后补发 ----------------

    @Test
    fun welcomeWithoutRemoteLatestResendsPending() {
        val harness = disconnectedHarness()
        harness.engine.sendLocalText("stashed text", "test")
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        // welcome 无 latest → pending 自动重发
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertTrue(awaitTrue {
            harness.latest.sent.any { it.contains("\"type\":\"clip\"") && it.contains("stashed text") }
        })
        // setLastSentHashHex 已记录：同 hash 的远端回显（更高版本）不落盘
        val hash = HashUtil.fnv1a64Hex("stashed text")
        harness.latest.simulateText(
            """{"type":"clip","version":30,"payload":"stashed text","encrypted":false,"hash":"$hash"}"""
        )
        assertTrue(awaitTrue { harness.callbacks.statuses.isNotEmpty() })
        assertFalse("echo of own resend must not write clipboard", harness.clipboard.text == "stashed text")
        harness.engine.stop()
    }

    @Test
    fun welcomeApplyingStaleRemoteKeepsPendingForResend() {
        val harness = disconnectedHarness()
        harness.engine.sendLocalText("my local edit", "test")
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        // welcome 携带远端 latest（服务端 latest 落后于本地暂存，v2.3.5 实机缺陷场景）：
        // 远端旧内容照常应用，但 pending 不被取代——welcome 自身的应用不参与时间序比较
        harness.latest.simulateText(ContractSamples.WELCOME_LATEST)
        assertTrue(awaitTrue { harness.clipboard.text == ContractSamples.PAYLOAD_TEXT })
        assertTrue(awaitTrue {
            harness.latest.sent.any { it.contains("\"type\":\"clip\"") && it.contains("my local edit") }
        })
        harness.engine.stop()
    }

    @Test
    fun remoteClipAppliedAfterStashSupersedesPending() {
        val clock = longArrayOf(1_000_000L)
        val harness = disconnectedHarness(nowMs = { clock[0] })
        // 半开连接上发送失败：pending 暂存于 T0
        harness.engine.sendLocalText("stale pending", "test")
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        // 新连接上先到一条「暂存之后」落地的远端 clip（T0+1s）
        clock[0] = 1_001_000L
        harness.latest.simulateText(ContractSamples.SERVER_CLIP)
        assertTrue(awaitTrue { harness.clipboard.text == ContractSamples.PAYLOAD_TEXT })
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        // welcome 结算：远端应用晚于暂存 → pending 被取代，不重发
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertFalse(harness.latest.sent.any { it.contains("\"type\":\"clip\"") })
        harness.engine.stop()
    }

    @Test
    fun resendFailureRestoresPendingAndReconnectsAgain() {
        val harness = disconnectedHarness()
        harness.engine.sendLocalText("retry me", "test")
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        // 补发再失败（写超时/半开）：pending 恢复 + 再次 forceReconnect
        harness.latest.sendTextException = IOException("write timeout")
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertTrue(awaitTrue { harness.transports.size >= 3 })
        assertTrue(harness.transports.none { it.sent.any { t -> t.contains("\"type\":\"clip\"") } })
        // 下一轮 welcome 再试，天然收敛
        harness.latest.sendTextException = null
        harness.latest.simulateOpen()
        assertTrue(awaitTrue { harness.latest.sent.any { it.contains("\"type\":\"hello\"") } })
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertTrue(awaitTrue {
            harness.latest.sent.any { it.contains("\"type\":\"clip\"") && it.contains("retry me") }
        })
        harness.engine.stop()
    }

    // ---------------- 不暂存的分支 ----------------

    @Test
    fun suppressedRemoteEchoIsNotStashed() {
        val harness = disconnectedHarness()
        // 远端刚落盘（hash 入池）：本地发送是自写回显，按 hash 抑制且不暂存
        harness.engine.state.markRemoteApplied(HashUtil.fnv1a64Hex("echoed"))
        harness.engine.sendLocalText("echoed", "test")
        harness.latest.simulateClosed(1000, "close")
        harness.engine.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertFalse(harness.latest.sent.any { it.contains("\"type\":\"clip\"") })
        harness.engine.stop()
    }

    @Test
    fun rateLimitedSendIsNotStashed() {
        val harness = disconnectedHarness()
        harness.engine.state.sendPausedUntilMs = System.currentTimeMillis() + 60_000L
        harness.engine.sendLocalText("paused text", "test")
        assertTrue(awaitTrue { harness.callbacks.statuses.any {
            it.startsWith("S${R.string.status_send_rate_limited}")
        } })
        harness.latest.simulateClosed(1000, "close")
        harness.engine.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertFalse(harness.latest.sent.any { it.contains("\"type\":\"clip\"") })
        harness.engine.stop()
    }

    @Test
    fun oversizedSendIsNotStashed() {
        val config = baseConfig().let { it.copy(userPrefs = it.userPrefs.copy(maxTextBytes = 10L)) }
        val harness = newHarness(config = config)
        harness.engine.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        harness.latest.simulateOpen()
        harness.latest.simulateClosed(1006, "eof")
        harness.engine.sendLocalText("this text is much longer than ten bytes", "test")
        assertTrue(awaitTrue { harness.callbacks.statuses.any {
            it.startsWith("S${R.string.status_clipboard_too_large}|")
        } })
        harness.latest.simulateClosed(1000, "close")
        harness.engine.onDeviceAwake()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        harness.latest.simulateOpen()
        harness.latest.simulateText(ContractSamples.WELCOME_NULL)
        assertFalse(harness.latest.sent.any { it.contains("\"type\":\"clip\"") })
        harness.engine.stop()
    }
}
