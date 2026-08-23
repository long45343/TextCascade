/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Context
import android.os.Looper
import com.textcascad.v2.engine.ClipboardAccess
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 引擎状态机（假传输）：hello/welcome/clip/clip_ack/ping/bye/error、
 * hash+version 去重、回显抑制、退避序列、token 预判过期重登、401 会话失效。
 */
@RunWith(RobolectricTestRunner::class)
class TextSyncEngineTest {

    // ---------------- 测试基建 ----------------

    private class FakeSyncTransport(private val listener: RawWebSocketClient.Listener) : SyncTransport {
        val sent = CopyOnWriteArrayList<String>()
        val connectCount = java.util.concurrent.atomic.AtomicInteger()
        @Volatile
        var closedCode: Int? = null
        @Volatile
        var sendTextException: Exception? = null

        override fun connect() {
            connectCount.incrementAndGet()
        }

        override fun sendText(text: String) {
            sendTextException?.let { throw it }
            sent.add(text)
        }

        override fun close(code: Int, reason: String) {
            closedCode = code
        }

        fun simulateOpen() = listener.onOpen()
        fun simulateText(text: String) = listener.onText(text)
        fun simulateClosed(code: Int, reason: String) = listener.onClosed(code, reason)
        fun simulateError(error: Throwable) = listener.onError(error)
        fun simulateSessionExpired() = listener.onSessionExpired(SessionExpiredException(401))
    }

    private class RecordingCallbacks : TextSyncEngine.Callbacks {
        val statuses = CopyOnWriteArrayList<String>()
        val appliedTexts = CopyOnWriteArrayList<String>()
        val writtenTexts = CopyOnWriteArrayList<String>()
        val serverVersions = CopyOnWriteArrayList<Long>()
        val sessionExpiredCount = java.util.concurrent.atomic.AtomicInteger()
        val reloginCount = java.util.concurrent.atomic.AtomicInteger()
        @Volatile
        var reloginResult: CachedReloginResult = CachedReloginResult.NoCredentials

        override fun onStatus(message: String) {
            statuses.add(message)
        }

        override fun onRemoteTextApplied(text: String) {
            appliedTexts.add(text)
        }

        override fun onSessionExpired() {
            sessionExpiredCount.incrementAndGet()
        }

        override fun onCachedReloginRequired(): CachedReloginResult {
            reloginCount.incrementAndGet()
            return reloginResult
        }

        override fun onServerVersionAdvanced(version: Long) {
            serverVersions.add(version)
        }
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (condition()) return true
            Thread.sleep(10)
        }
        idleMain()
        return condition()
    }

    private fun baseConfig(
        cipherEnabled: Boolean = false,
        tokenExpiresAtUtc: Long = System.currentTimeMillis() + 3_600_000L,
        lastServerVersion: Long = 0L
    ): ClipConfig = ClipConfig(
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
            lastServerVersion = lastServerVersion,
            relaunchOnBoot = false,
            websocketStatusNotification = false,
            localMaxClipboardBytes = 512_000L
        ),
        cryptoMaterial = CryptoMaterial(
            derivedKeyBase64 = if (cipherEnabled) {
                android.util.Base64.encodeToString(
                    CryptoManager.derivePasswordKey("user", "pass", "salt", 1000),
                    android.util.Base64.NO_WRAP
                )
            } else "",
            hashRounds = 1000,
            salt = "salt",
            cipherEnabled = cipherEnabled,
            trustAllCerts = false
        )
    )

    private class EngineHarness(
        val engine: TextSyncEngine,
        val callbacks: RecordingCallbacks,
        val transports: List<FakeSyncTransport>,
        private val clipboardTexts: MutableList<String>,
        val stringProvider: FakeStringProvider
    ) {
        val latestTransport: FakeSyncTransport get() = transports.last()
        val written: List<String> get() = clipboardTexts
    }

    private class FakeStringProvider : StringProvider {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Array<out Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args)
            return "S$id|${args.joinToString("|") { it.toString() }}"
        }
    }

    private fun newEngine(
        config: ClipConfig = baseConfig(),
        callbacks: RecordingCallbacks = RecordingCallbacks(),
        clipboardContent: String? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        stringProvider: FakeStringProvider = FakeStringProvider()
    ): EngineHarness {
        val context = RuntimeEnvironment.getApplication()
        val transports = CopyOnWriteArrayList<FakeSyncTransport>()
        val clipboardTexts = CopyOnWriteArrayList<String>()
        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = callbacks,
            stringProvider = stringProvider,
            transportFactory = { _, _, listener, _, _ ->
                FakeSyncTransport(listener).also { transports.add(it) }
            },
            nowMs = nowMs,
            userPresentReconnectDelaySeconds = 0L,
            clipboard = object : ClipboardAccess {
                override fun readText(): String? = clipboardContent
                override fun writeText(text: String) {
                    clipboardTexts.add(text)
                }
            }
        )
        return EngineHarness(engine, callbacks, transports, clipboardTexts, stringProvider)
    }

    private fun startedEngine(
        config: ClipConfig = baseConfig(),
        callbacks: RecordingCallbacks = RecordingCallbacks(),
        clipboardContent: String? = null,
        nowMs: () -> Long = System::currentTimeMillis
    ): EngineHarness {
        val harness = newEngine(config, callbacks, clipboardContent, nowMs)
        harness.engine.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        return harness
    }

    // ---------------- hello ----------------

    @Test
    fun openSendsHelloWithoutSnapshotWhenClipboardEmpty() {
        val harness = startedEngine(config = baseConfig(lastServerVersion = 7L))
        harness.latestTransport.simulateOpen()
        assertTrue(awaitTrue { harness.latestTransport.sent.isNotEmpty() })
        val hello = harness.latestTransport.sent.first()
        assertEquals(ContractSamples.HELLO_NO_SNAPSHOT, hello)
    }

    @Test
    fun openSendsHelloWithSnapshotWhenClipboardNonEmpty() {
        val harness = startedEngine(clipboardContent = ContractSamples.PAYLOAD_TEXT)
        harness.latestTransport.simulateOpen()
        assertTrue(awaitTrue { harness.latestTransport.sent.isNotEmpty() })
        val hello = harness.latestTransport.sent.first()
        // 时间戳动态生成，逐字段校验而非逐字节
        val obj = org.json.JSONObject(hello)
        assertEquals("hello", obj.getString("type"))
        assertEquals(ContractSamples.CLIENT_ID, obj.getString("clientId"))
        assertEquals(ContractSamples.CLIENT_NAME, obj.getString("clientName"))
        assertEquals(0L, obj.getLong("lastServerVersion"))
        val snapshot = obj.getJSONObject("snapshot")
        assertEquals(ContractSamples.PAYLOAD_TEXT, snapshot.getString("payload"))
        assertEquals(false, snapshot.getBoolean("encrypted"))
        assertEquals(ContractSamples.HASH_FOOBAR, snapshot.getString("hash"))
        assertTrue(snapshot.getString("localModifiedAtUtc").endsWith("Z"))
    }

    @Test
    fun helloCarriesPersistedLastServerVersion() {
        val harness = startedEngine(config = baseConfig(lastServerVersion = 7L))
        harness.latestTransport.simulateOpen()
        assertTrue(awaitTrue { harness.latestTransport.sent.isNotEmpty() })
        assertEquals(7L, org.json.JSONObject(harness.latestTransport.sent.first()).getLong("lastServerVersion"))
    }

    // ---------------- welcome ----------------

    @Test
    fun welcomeWithNullLatestAppliesNothing() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.WELCOME_NULL)
        Thread.sleep(150)
        idleMain()
        assertTrue(harness.written.isEmpty())
        assertTrue(harness.callbacks.serverVersions.isEmpty())
    }

    @Test
    fun welcomeWithLatestWritesClipboardAndAdvancesVersion() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.WELCOME_LATEST)
        assertTrue(awaitTrue { harness.written.isNotEmpty() })
        assertEquals(ContractSamples.PAYLOAD_TEXT, harness.written.single())
        assertTrue(harness.callbacks.serverVersions.contains(9L))
        assertTrue(harness.callbacks.appliedTexts.contains(ContractSamples.PAYLOAD_TEXT))
    }

    @Test
    fun welcomeStaleVersionIsSkippedButHashEchoAlsoSkipped() {
        val harness = startedEngine(config = baseConfig(lastServerVersion = 20L))
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.WELCOME_LATEST) // version 9 < 20
        Thread.sleep(150)
        idleMain()
        assertTrue(harness.written.isEmpty())
        assertTrue(harness.callbacks.serverVersions.isEmpty())
    }

    @Test
    fun welcomeEchoOfOwnClipIsNotApplied() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        // 本地先发送 foobar
        harness.engine.sendLocalText(ContractSamples.PAYLOAD_TEXT, "test")
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.startsWith("{\"type\":\"clip\"") } })
        // 服务端把同 hash 的 latest 回显（version 9 > 0）
        harness.latestTransport.simulateText(ContractSamples.WELCOME_LATEST)
        assertTrue(awaitTrue { harness.callbacks.serverVersions.contains(9L) })
        idleMain()
        assertTrue(harness.written.isEmpty())
    }

    // ---------------- clip / clip_ack ----------------

    @Test
    fun serverClipWritesClipboardAndAdvancesVersion() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.SERVER_CLIP) // version 10
        assertTrue(awaitTrue { harness.written.isNotEmpty() })
        assertEquals(ContractSamples.PAYLOAD_TEXT, harness.written.single())
        assertTrue(harness.callbacks.serverVersions.contains(10L))
    }

    @Test
    fun serverClipWithStaleVersionIsIgnored() {
        val harness = startedEngine(config = baseConfig(lastServerVersion = 15L))
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.SERVER_CLIP) // version 10 <= 15
        Thread.sleep(150)
        idleMain()
        assertTrue(harness.written.isEmpty())
        assertTrue(harness.callbacks.serverVersions.isEmpty())
    }

    @Test
    fun clipAckAdvancesVersionWithoutApplying() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.CLIP_ACK) // version 11
        assertTrue(awaitTrue { harness.callbacks.serverVersions.contains(11L) })
        assertTrue(harness.written.isEmpty())
    }

    @Test
    fun localClipSentWithUuidIdAndHashDedupedAgainstRemote() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.engine.sendLocalText("local text", "test")
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.contains("\"type\":\"clip\"") } })
        val clip = harness.latestTransport.sent.first { it.contains("\"type\":\"clip\"") }
        val obj = org.json.JSONObject(clip)
        assertEquals("clip", obj.getString("type"))
        assertEquals("local text", obj.getString("payload"))
        assertEquals(false, obj.getBoolean("encrypted"))
        assertEquals(HashUtil.fnv1a64Hex("local text"), obj.getString("hash"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            .matches(obj.getString("id")))
        // 状态为广播中（假 StringProvider 输出 S<id>）
        assertTrue(harness.callbacks.statuses.any { it.startsWith("S${R.string.status_connected_broadcasting}|") })

        // 远端回送同文本（同 hash）不落盘
        harness.latestTransport.simulateText(
            """{"type":"clip","version":30,"payload":"local text","encrypted":false,"hash":"${HashUtil.fnv1a64Hex("local text")}"}"""
        )
        assertTrue(awaitTrue { harness.callbacks.serverVersions.contains(30L) })
        idleMain()
        assertTrue(harness.written.none { it == "local text" })
    }

    @Test
    fun emptyTextIsNeverSent() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.engine.sendLocalText("", "test")
        Thread.sleep(150)
        assertTrue(harness.latestTransport.sent.none { it.contains("\"type\":\"clip\"") })
    }

    @Test
    fun oversizedTextIsNotSent() {
        val config = baseConfig().let { it.copy(userPrefs = it.userPrefs.copy(maxTextBytes = 10L)) }
        val harness = startedEngine(config = config)
        harness.latestTransport.simulateOpen()
        harness.engine.sendLocalText("this text is much longer than ten bytes", "test")
        Thread.sleep(150)
        assertTrue(harness.latestTransport.sent.none { it.contains("\"type\":\"clip\"") })
    }

    /**
     * 回归（服务端 spec 对齐）：ValidatePayloadSize 校验 payload 字段本身（加密后含 base64 扩散），
     * 非明文。加密模式明文未超限但加密 payload 超限时必须本地拦截，
     * 否则 clip 触发 text_too_large、hello snapshot 触发 invalid_hello（1008 断连循环）。
     */
    @Test
    fun encryptedClipDroppedLocallyWhenPayloadExceedsServerLimit() {
        // maxTextBytes=1024：明文 900B 通过本地预检，加密 payload ~1.3KB 超限
        val config = baseConfig(cipherEnabled = true).let { it.copy(userPrefs = it.userPrefs.copy(maxTextBytes = 1024L)) }
        val harness = startedEngine(config = config)
        harness.latestTransport.simulateOpen()
        val bigText = "x".repeat(900)
        harness.engine.sendLocalText(bigText, "test")
        Thread.sleep(300)
        assertTrue(harness.latestTransport.sent.none { it.contains("\"type\":\"clip\"") })
        assertTrue(harness.callbacks.statuses.any { it.contains("900") })
    }

    @Test
    fun helloSnapshotDroppedWhenEncryptedPayloadExceedsServerLimit() {
        val config = baseConfig(cipherEnabled = true).let { it.copy(userPrefs = it.userPrefs.copy(maxTextBytes = 1024L)) }
        val harness = startedEngine(config = config, clipboardContent = "y".repeat(900))
        harness.latestTransport.simulateOpen()
        assertTrue(awaitTrue { harness.latestTransport.sent.isNotEmpty() })
        val hello = org.json.JSONObject(harness.latestTransport.sent.first())
        // hello 必须仍为合法无快照形态（服务端否则 1008 断连）
        assertEquals("hello", hello.getString("type"))
        assertTrue(!hello.has("snapshot"))
    }

    // ---------------- 回显抑制 ----------------

    @Test
    fun remoteApplySuppressesNextLocalClipboardEvent() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.SERVER_CLIP)
        assertTrue(awaitTrue { harness.written.isNotEmpty() })

        // 模拟系统回环：剪贴板监听触发 sendLocalText（同文本）→ 应被抑制
        harness.engine.sendLocalText(ContractSamples.PAYLOAD_TEXT, "clipboard")
        Thread.sleep(200)
        assertTrue(harness.latestTransport.sent.none { it.contains("\"type\":\"clip\"") })

        // 第二次（未被抑制的独立复制）正常发送
        harness.engine.sendLocalText("next text", "clipboard")
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.contains("next text") } })
    }

    // ---------------- ping/pong ----------------

    @Test
    fun pingIsAnsweredWithPongImmediately() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.PING)
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.contains("\"type\":\"pong\"") } })
        val pong = harness.latestTransport.sent.first { it.contains("\"type\":\"pong\"") }
        assertTrue(org.json.JSONObject(pong).getString("clientTimeUtc").endsWith("Z"))
    }

    /**
     * R4: ping 后 sendText 抛异常应立即触发断线重连，而非静默吞错。
     */
    @Test
    fun pingSendFailureTriggersReconnect() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        // hello 已发送；此后 pong 发送抛异常
        harness.latestTransport.sendTextException = java.io.IOException("pong write failed")
        harness.latestTransport.simulateText(ContractSamples.PING)
        // handleError → scheduleReconnect → 退避 1s 后新建传输
        assertTrue(awaitTrue(8_000) { harness.transports.size >= 2 })
        assertTrue(harness.callbacks.statuses.any {
            it.startsWith("S${R.string.status_websocket_error}|") ||
                it.startsWith("S${R.string.status_waiting_reconnect}|")
        })
    }

    // ---------------- bye / 错误码 ----------------

    @Test
    fun byeLogsReasonAndEnablesGentleBackoff() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.BYE)
        assertTrue(awaitTrue { harness.callbacks.statuses.any { it.contains("server_shutdown") } })
        // 温和退避序列：1/2/5/10 固定 10
        assertEquals(1L, harness.engine.backoffDelaySeconds(0, maintenance = true))
        assertEquals(2L, harness.engine.backoffDelaySeconds(1, maintenance = true))
        assertEquals(5L, harness.engine.backoffDelaySeconds(2, maintenance = true))
        assertEquals(10L, harness.engine.backoffDelaySeconds(3, maintenance = true))
        assertEquals(10L, harness.engine.backoffDelaySeconds(9, maintenance = true))
    }

    @Test
    fun normalBackoffSequence() {
        val harness = newEngine()
        assertEquals(1L, harness.engine.backoffDelaySeconds(0, maintenance = false))
        assertEquals(2L, harness.engine.backoffDelaySeconds(1, maintenance = false))
        assertEquals(5L, harness.engine.backoffDelaySeconds(2, maintenance = false))
        assertEquals(10L, harness.engine.backoffDelaySeconds(3, maintenance = false))
        assertEquals(30L, harness.engine.backoffDelaySeconds(4, maintenance = false))
        assertEquals(60L, harness.engine.backoffDelaySeconds(5, maintenance = false))
        assertEquals(60L, harness.engine.backoffDelaySeconds(12, maintenance = false))
    }

    @Test
    fun errorInvalidMessageKeepsConnection() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText("""{"type":"error","code":"invalid_message","message":"bad"}""")
        assertTrue(awaitTrue { harness.callbacks.statuses.any { it.contains("invalid_message") } })
        // 连接保持：后续 ping 正常处理
        harness.latestTransport.simulateText(ContractSamples.PING)
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.contains("\"type\":\"pong\"") } })
    }

    @Test
    fun errorTextTooLargeNotifiesUser() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText(ContractSamples.ERROR_TEXT_TOO_LARGE)
        assertTrue(awaitTrue { harness.callbacks.statuses.isNotEmpty() })
    }

    @Test
    fun errorRateLimitedPausesSending() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText("""{"type":"error","code":"rate_limited"}""")
        assertTrue(awaitTrue { harness.callbacks.statuses.any { it.startsWith("S${R.string.status_send_rate_limited}") } })
        // 暂停期内丢弃发送
        harness.engine.sendLocalText("paused text", "test")
        Thread.sleep(150)
        assertTrue(harness.latestTransport.sent.none { it.contains("paused text") })
    }

    @Test
    fun unknownMessageTypeIsIgnored() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateText("""{"type":"future_thing","data":1}""")
        Thread.sleep(150)
        // 无异常、无状态污染
        assertTrue(harness.callbacks.statuses.none { it.contains("error") })
    }

    // ---------------- 断线重连 ----------------

    @Test
    fun abnormalCloseSchedulesReconnect() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateClosed(1006, "unexpected EOF")
        assertTrue(awaitTrue { harness.callbacks.statuses.any { it.startsWith("S${R.string.status_waiting_reconnect}|") && it.contains("1") } })
        // 第一次退避 1s 后重连（等待真实调度）
        assertTrue(awaitTrue(8_000) { harness.transports.size >= 2 })
    }

    @Test
    fun close1001UsesGentleBackoffStatus() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateClosed(1001, "going_away")
        assertTrue(awaitTrue(8_000) { harness.transports.size >= 2 })
    }

    @Test
    fun userPresentTriggersEarlyReconnect() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateClosed(1006, "eof")
        harness.engine.reconnectAfterUserPresent()
        assertTrue(awaitTrue(8_000) { harness.transports.size >= 2 })
    }

    @Test
    fun stopClosesWith1000AndDoesNotReconnect() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.engine.stop()
        assertEquals(1000, harness.latestTransport.closedCode)
        val transportCount = harness.transports.size
        harness.latestTransport.simulateClosed(1000, "client_stop")
        Thread.sleep(1_500)
        assertEquals(transportCount, harness.transports.size)
        assertTrue(harness.engine.isStopped)
    }

    // ---------------- 会话失效 / token 预判 ----------------

    @Test
    fun sessionExpired401InvokesCallbackOnceWithoutReconnect() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        harness.latestTransport.simulateSessionExpired()
        assertTrue(awaitTrue { harness.callbacks.sessionExpiredCount.get() == 1 })
        Thread.sleep(1_000)
        assertEquals(1, harness.transports.size)
        assertEquals(1, harness.callbacks.sessionExpiredCount.get())
    }

    @Test
    fun tokenNearExpiryTriggersReloginBeforeConnect() {
        val callbacks = RecordingCallbacks()
        callbacks.reloginResult = CachedReloginResult.NoCredentials
        val config = baseConfig(tokenExpiresAtUtc = System.currentTimeMillis() + 30_000L)
        val harness = newEngine(config = config, callbacks = callbacks)
        harness.engine.start()
        // token 距过期不足 60s：先走 HTTP 重登；NoCredentials → 会话失效，不建 WebSocket
        assertTrue(awaitTrue { callbacks.reloginCount.get() >= 1 })
        assertTrue(awaitTrue { callbacks.sessionExpiredCount.get() >= 1 })
        Thread.sleep(300)
        assertTrue(harness.transports.isEmpty())
    }

    @Test
    fun tokenNearExpiryReloginSuccessWaitsForRestart() {
        val callbacks = RecordingCallbacks()
        callbacks.reloginResult = CachedReloginResult.Success(LoginResult(
            normalizedServerUrl = "https://srv.example",
            websocketUrl = "wss://srv.example/api/v1/sync",
            token = "tok-2",
            tokenExpiresAtUtc = System.currentTimeMillis() + 3_600_000L,
            protocolVersion = 1,
            maxTextBytes = 512_000L,
            helloTimeoutSeconds = 10,
            heartbeatIntervalSeconds = 20,
            heartbeatTimeoutSeconds = 60
        ))
        val config = baseConfig(tokenExpiresAtUtc = System.currentTimeMillis() + 10_000L)
        val harness = newEngine(config = config, callbacks = callbacks)
        harness.engine.start()
        assertTrue(awaitTrue { callbacks.reloginCount.get() == 1 })
        Thread.sleep(300)
        // 重登成功：等待上层以新配置重启，不使用旧 token 连接
        assertTrue(harness.transports.isEmpty())
    }

    @Test
    fun tokenNearExpiryRateLimitedWaitsAtLeast30s() {
        val callbacks = RecordingCallbacks()
        callbacks.reloginResult = CachedReloginResult.RateLimited(5L)
        val config = baseConfig(tokenExpiresAtUtc = System.currentTimeMillis() + 30_000L)
        val harness = newEngine(config = config, callbacks = callbacks)
        harness.engine.start()
        assertTrue(awaitTrue { callbacks.reloginCount.get() == 1 })
        assertTrue(awaitTrue {
            callbacks.statuses.any { it.contains("30") }
        })
        Thread.sleep(300)
        // 30s 内不重试重登、不连接
        assertEquals(1, callbacks.reloginCount.get())
        assertTrue(harness.transports.isEmpty())
    }

    @Test
    fun validTokenConnectsDirectlyWithoutRelogin() {
        val callbacks = RecordingCallbacks()
        val harness = newEngine(callbacks = callbacks)
        harness.engine.start()
        assertTrue(awaitTrue { harness.transports.isNotEmpty() })
        assertEquals(0, callbacks.reloginCount.get())
    }

    // ---------------- 加密互通 ----------------

    @Test
    fun encryptedLocalClipAndRemoteDecryptRoundtrip() {
        val config = baseConfig(cipherEnabled = true)
        val harness = startedEngine(config = config)
        harness.latestTransport.simulateOpen()

        // 本地发送 → payload 为加密 JSON
        harness.engine.sendLocalText("secret text", "test")
        assertTrue(awaitTrue { harness.latestTransport.sent.any { it.contains("\"type\":\"clip\"") } })
        val clip = org.json.JSONObject(
            harness.latestTransport.sent.first { it.contains("\"type\":\"clip\"") }
        )
        assertEquals(true, clip.getBoolean("encrypted"))
        assertEquals(HashUtil.fnv1a64Hex("secret text"), clip.getString("hash"))
        val payloadObj = org.json.JSONObject(clip.getString("payload"))
        assertEquals(3, payloadObj.length())

        // 远端加密 clip → 解密落盘（payload 由 JSONObject 正确转义）
        val encryptedPayload = CryptoManager.encrypt("remote secret", config.cryptoMaterial.derivedKeyBase64)
        val serverClip = org.json.JSONObject()
            .put("type", "clip")
            .put("version", 50L)
            .put("payload", CryptoManager.encryptedPayloadJson(encryptedPayload))
            .put("encrypted", true)
            .put("hash", HashUtil.fnv1a64Hex("remote secret"))
            .toString()
        harness.latestTransport.simulateText(serverClip)
        assertTrue(awaitTrue { harness.written.contains("remote secret") })
    }

    // ---------------- generation 重启 ----------------

    @Test
    fun stopThenStartCreatesFreshEngineSession() {
        val harness = startedEngine()
        harness.engine.stop()
        assertTrue(harness.engine.isStopped)
        // 同一实例重新启动（executor 重建路径）
        harness.engine.start()
        assertTrue(awaitTrue { harness.transports.size >= 2 })
        assertNotNull(harness.engine.executorForTest())
    }

    // ---------------- R8 StringProvider 解耦 ----------------

    /**
     * R8: 假 StringProvider 注入下，各状态对应文案仍被正确触发（按资源 id 校验）。
     */
    @Test
    fun stringProviderReceivesExpectedIdsForKeyStates() {
        val harness = startedEngine()
        harness.latestTransport.simulateOpen()
        assertTrue(awaitTrue { harness.stringProvider.calls.any { it.first == R.string.status_connected } })
        harness.engine.sendLocalText("hello", "test")
        assertTrue(awaitTrue { harness.stringProvider.calls.any { it.first == R.string.status_connected_broadcasting } })
        harness.latestTransport.simulateClosed(1006, "eof")
        assertTrue(awaitTrue { harness.stringProvider.calls.any { it.first == R.string.status_disconnected } })
    }
}

