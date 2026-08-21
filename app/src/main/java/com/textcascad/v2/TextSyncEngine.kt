/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascad.v2

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.textcascad.v2.engine.AndroidClipboardAccess
import com.textcascad.v2.engine.ClipboardAccess
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * v2 同步引擎状态机：
 * - hello（含 snapshot）→ welcome / clip / clip_ack / ping→pong / bye / error
 * - hash + version 双去重、回显抑制（写剪贴板后抑制下一次本地事件）
 * - 退避：常规 1/2/5/10/30/60（固定 60）；bye/1001 温和 1/2/5/10（固定 10）；welcome 重置
 * - token 本地预判过期（距 expiresAtUtc 不足 60s 先 HTTP 重登）
 * - 401 升级失败 → 会话失效回调（上层单飞重登一次）
 */
class TextSyncEngine(
    private val context: Context,
    private val config: ClipConfig,
    private val callbacks: Callbacks,
    private val stringProvider: StringProvider = object : StringProvider {
        override fun get(id: Int, vararg args: Any): String =
            if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    },
    private val disconnectedStatus: (message: String, subText: String) -> Unit = { m, _ -> callbacks.onStatus(m) },
    private val transportFactory: (
        url: String,
        token: String,
        listener: RawWebSocketClient.Listener,
        trustAll: Boolean,
        rxTimeoutMs: Long
    ) -> SyncTransport = { url, token, listener, trustAll, rxTimeoutMs ->
        RawWebSocketClient(url, token, listener, trustAll, rxTimeoutMs)
    },
    internal val executorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "textcascade-sync").apply { isDaemon = true }
        }
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val userPresentReconnectDelaySeconds: Long = USER_PRESENT_RECONNECT_DELAY_SECONDS,
    private val clipboard: ClipboardAccess = AndroidClipboardAccess(context),
    private val backoffDelaysNormalSeconds: List<Long> = listOf(1L, 2L, 5L, 10L, 30L, 60L),
    private val backoffDelaysMaintenanceSeconds: List<Long> = listOf(1L, 2L, 5L, 10L),
    private val rateLimitedReloginFloorSeconds: Long = 30L
) : RawWebSocketClient.Listener {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRemoteTextApplied(text: String)

        /** 会话失效（401/token 过期重登无凭据）：停止重连，交上层处理。 */
        fun onSessionExpired() {}

        /** 引擎需要 HTTP 重登（token 预判过期时同步调用，阻塞在引擎线程）。 */
        fun onCachedReloginRequired(): CachedReloginResult = CachedReloginResult.NoCredentials

        /** lastServerVersion 前进时持久化（单调递增）。 */
        fun onServerVersionAdvanced(version: Long) {}
    }

    private enum class ConnectionLifecycle {
        STOPPED,
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionLock = Any()
    private var lifecycle = ConnectionLifecycle.STOPPED
    private var connectionGeneration = 0L
    private var executor: ScheduledExecutorService? = null
    private var transport: SyncTransport? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    @Volatile
    private var stopped = true
    @Volatile
    private var connected = false
    @Volatile
    private var connecting = false
    @Volatile
    private var maintenanceBackoff = false
    @Volatile
    private var sendPausedUntilMs = 0L

    private val reconnectTaskLock = Any()
    private var reconnectGeneration = 0L
    private var reconnectAttempts = 0
    private var reconnectInFlight = false

    private val stateLock = Any()
    private var lastSentHashHex: String? = null
    private var lastRemoteHashHex: String? = null
    private var suppressNextLocal = false
    @Volatile
    private var serverVersion: Long = config.lastServerVersion
    private val remoteApplyGeneration = AtomicLong(0L)

    val isConnected: Boolean get() = connected
    val isConnecting: Boolean get() = connecting
    val isStopped: Boolean get() = stopped

    internal fun executorForTest(): ScheduledExecutorService? = synchronized(connectionLock) { executor }

    internal fun connectionGenerationForTest(): Long = synchronized(connectionLock) { connectionGeneration }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start() {
        val generation: Long
        val currentExecutor: ScheduledExecutorService
        synchronized(connectionLock) {
            if (lifecycle != ConnectionLifecycle.STOPPED) return
            stopped = false
            connected = false
            connecting = false
            maintenanceBackoff = false
            serverVersion = config.lastServerVersion
            lifecycle = ConnectionLifecycle.DISCONNECTED
            generation = ++connectionGeneration
            currentExecutor = try {
                currentExecutorLocked() ?: error("Unable to create sync executor")
            } catch (error: RuntimeException) {
                stopped = true
                lifecycle = ConnectionLifecycle.STOPPED
                ++connectionGeneration
                throw error
            }
        }
        try {
            currentExecutor.execute { connect(expectedGeneration = generation) }
        } catch (error: RuntimeException) {
            synchronized(connectionLock) {
                if (connectionGeneration == generation && executor === currentExecutor) {
                    executor = null
                    stopped = true
                    lifecycle = ConnectionLifecycle.STOPPED
                    ++connectionGeneration
                }
            }
            currentExecutor.shutdownNow()
            throw error
        }
    }

    fun stop() {
        val oldTransport: SyncTransport?
        val oldExecutor: ScheduledExecutorService?
        synchronized(connectionLock) {
            stopped = true
            connected = false
            connecting = false
            lifecycle = ConnectionLifecycle.STOPPED
            ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
            oldTransport = transport
            transport = null
            oldExecutor = executor
            executor = null
        }
        cancelReconnectTasks()
        runCatching { oldTransport?.close(1000, "client_stop") }
        oldExecutor?.shutdownNow()
    }

    fun sendLocalText(text: String, source: String) {
        submitToCurrentExecutor { sendLocalTextInternal(text, source) }
    }

    fun forceReconnect() {
        val oldTransport: SyncTransport?
        val generation: Long
        val currentExecutor: ScheduledExecutorService
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            generation = ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
            oldTransport = transport
            transport = null
            currentExecutor = currentExecutorLocked() ?: return
        }
        cancelReconnectTasks()
        runCatching { oldTransport?.close(1000, "reconnect") }
        try {
            currentExecutor.execute { connect(force = true, expectedGeneration = generation) }
        } catch (_: RuntimeException) {
            synchronized(connectionLock) {
                if (generation == connectionGeneration && lifecycle != ConnectionLifecycle.STOPPED) {
                    lifecycle = ConnectionLifecycle.DISCONNECTED
                    connecting = false
                }
            }
        }
    }

    /** 解锁/回前台后提前重连（仅处于断线等待时生效）。 */
    fun reconnectAfterUserPresent() {
        val exec = synchronized(connectionLock) { currentExecutorLocked() } ?: return
        exec.execute {
            val taskGen: Long
            val connectionGen: Long
            synchronized(connectionLock) {
                if (stopped || connected || connecting) return@execute
                connectionGen = connectionGeneration
            }
            synchronized(reconnectTaskLock) {
                if (reconnectTask == null) return@execute
                reconnectTask?.cancel(false)
                reconnectTask = null
                reconnectInFlight = false
                taskGen = ++reconnectGeneration
            }
            val task = exec.schedule({
                if (taskGen == reconnectGenerationSafe()) {
                    performReconnect(connectionGen)
                }
            }, userPresentReconnectDelaySeconds, TimeUnit.SECONDS)
            synchronized(reconnectTaskLock) {
                if (!stopped && taskGen == reconnectGeneration) reconnectTask = task else task.cancel(false)
            }
        }
    }

    private fun reconnectGenerationSafe(): Long = synchronized(reconnectTaskLock) { reconnectGeneration }

    // ------------------------------------------------------------------
    // 传输回调
    // ------------------------------------------------------------------

    override fun onOpen() {
        handleOpen(synchronized(connectionLock) { connectionGeneration })
    }

    override fun onText(text: String) {
        val generation = synchronized(connectionLock) { connectionGeneration }
        handleMessage(generation, text)
    }

    override fun onClosed(code: Int, reason: String) {
        handleClosed(synchronized(connectionLock) { connectionGeneration }, code, reason)
    }

    override fun onError(error: Throwable) {
        handleError(synchronized(connectionLock) { connectionGeneration }, error)
    }

    override fun onSessionExpired(error: SessionExpiredException) {
        handleSessionExpired(synchronized(connectionLock) { connectionGeneration })
    }

    // ------------------------------------------------------------------
    // 连接与重连
    // ------------------------------------------------------------------

    private fun currentExecutorLocked(): ScheduledExecutorService? {
        if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return null
        val existing = executor
        if (existing != null && !existing.isShutdown) return existing
        return executorFactory().also { executor = it }
    }

    private fun submitToCurrentExecutor(task: () -> Unit): Boolean {
        val currentExecutor = synchronized(connectionLock) { currentExecutorLocked() } ?: return false
        return try {
            currentExecutor.execute(task)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun tokenNeedsRelogin(): Boolean {
        val expiresAt = config.tokenExpiresAtUtc
        return expiresAt > 0L && nowMs() + ClipConfig.TOKEN_EXPIRY_SAFETY_MS >= expiresAt
    }

    private fun connect(force: Boolean = false, expectedGeneration: Long? = null) {
        val oldTransport: SyncTransport?
        val generation: Long
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return
            if (expectedGeneration != null && expectedGeneration != connectionGeneration) return
            if (!force && lifecycle != ConnectionLifecycle.DISCONNECTED) return
            lifecycle = ConnectionLifecycle.CONNECTING
            connected = false
            connecting = true
            generation = connectionGeneration
            oldTransport = transport
            transport = null
        }

        runCatching { oldTransport?.close(1000, "superseded") }

        // token 本地预判过期：先 HTTP 重登，避免必然失败的 401 往返
        if (tokenNeedsRelogin()) {
            status(stringProvider.get(R.string.status_relogin_with_cached))
            val result = try {
                callbacks.onCachedReloginRequired()
            } catch (error: Throwable) {
                CachedReloginResult.TransientFailure(error)
            }
            if (!isCurrentGeneration(generation)) return
            when (result) {
                is CachedReloginResult.Success -> {
                    // 重登成功：会话已更新，等待上层以新配置重启引擎
                    status(stringProvider.get(R.string.status_relogin_succeeded_restart))
                    synchronized(connectionLock) {
                        if (isCurrentGenerationLocked(generation)) {
                            lifecycle = ConnectionLifecycle.DISCONNECTED
                            connecting = false
                        }
                    }
                    return
                }
                is CachedReloginResult.RateLimited -> {
                    scheduleReconnectAfter(maxOf(rateLimitedReloginFloorSeconds, result.retryAfterSeconds ?: 0L))
                    return
                }
                CachedReloginResult.AuthFailure -> {
                    handleSessionExpired(generation)
                    return
                }
                CachedReloginResult.NoCredentials -> {
                    handleSessionExpired(generation)
                    return
                }
                is CachedReloginResult.TransientFailure -> {
                    status(stringProvider.get(R.string.status_relogin_failed, result.error.message.orEmpty()))
                    scheduleReconnect()
                    return
                }
            }
        }

        status(stringProvider.get(R.string.status_connecting))
        val rxTimeoutMs = RawWebSocketClient.watchdogRxTimeoutMs(config.heartbeatTimeoutSeconds)
        val newTransport = try {
            transportFactory(
                config.websocketUrl,
                config.token,
                GenerationListener(generation),
                config.trustAllCerts,
                rxTimeoutMs
            )
        } catch (error: Throwable) {
            handleError(generation, error)
            return
        }

        val accepted = synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED || generation != connectionGeneration) {
                false
            } else {
                transport = newTransport
                true
            }
        }
        if (!accepted) {
            runCatching { newTransport.close(1000, "stale") }
            return
        }
        try {
            newTransport.connect()
        } catch (error: Throwable) {
            handleError(generation, error)
        }
    }

    private fun handleOpen(generation: Long) {
        val currentTransport: SyncTransport?
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.CONNECTED
            connected = true
            connecting = false
            currentTransport = transport
        }
        status(stringProvider.get(R.string.status_connected))
        runCatching { currentTransport?.sendText(buildHelloMessage()) }
            .onFailure { handleError(generation, it) }
    }

    private fun buildHelloMessage(): String {
        var snapshot: Protocol.SnapshotPayload? = null
        runCatching {
            val text = clipboard.readText()
            if (!text.isNullOrBlank()) {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                if (isWithinLimits(textBytes)) {
                    val hashHex = HashUtil.fnv1a64Hex(textBytes)
                    val payload = encryptOutbound(text)
                    // 服务端对 snapshot.payload 本身限额（加密后含 base64 扩散）；
                    // 超限时携带会被判 invalid_hello 并以 1008 断连，因此放弃快照。
                    if (payload != null && payloadWithinServerLimit(payload)) {
                        snapshot = Protocol.SnapshotPayload(
                            payload = payload,
                            encrypted = config.cipherEnabled,
                            hashHex = hashHex,
                            localModifiedAtUtc = Protocol.utcNowString(nowMs())
                        )
                    }
                }
            }
        }
        return Protocol.helloMessage(
            clientId = config.clientId,
            clientName = config.clientName,
            lastServerVersion = synchronized(stateLock) { serverVersion },
            snapshot = snapshot
        )
    }

    /**
     * 服务端 ValidatePayloadSize 校验的是 payload 字段本身（加密后 JSON，约为明文 4/3 倍），
     * 不是明文。加密模式下明文 >~ 3/4 maxTextBytes 时会被服务端拒绝：
     * clip 路径触发 text_too_large，hello snapshot 路径触发 invalid_hello（1008 断连循环）。
     */
    private fun payloadWithinServerLimit(payload: String): Boolean {
        return payload.toByteArray(Charsets.UTF_8).size.toLong() <= config.maxTextBytes
    }

    private fun handleMessage(generation: Long, body: String) {
        submitToCurrentExecutor task@{
            if (!isCurrentGeneration(generation)) return@task
            if (body.toByteArray(Charsets.UTF_8).size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
                status(stringProvider.get(R.string.status_encoded_too_large))
                return@task
            }
            val message = try {
                Protocol.parseServerMessage(body)
            } catch (_: Exception) {
                status(stringProvider.get(R.string.status_inbound_error, "malformed json"))
                return@task
            }
            when (message) {
                is Protocol.ServerMessage.Welcome -> handleWelcome(message)
                is Protocol.ServerMessage.Clip -> handleServerClip(message)
                is Protocol.ServerMessage.ClipAck -> advanceServerVersion(message.version)
                is Protocol.ServerMessage.Ping -> runCatching {
                    synchronized(connectionLock) { transport }?.sendText(
                        Protocol.pongMessage(Protocol.utcNowString(nowMs()))
                    )
                }.onFailure { handleError(generation, it) }
                is Protocol.ServerMessage.Bye -> {
                    // 记录 reason，不影响重连决策；关闭后走温和退避
                    maintenanceBackoff = true
                    status(stringProvider.get(R.string.status_server_bye, message.reason ?: "unknown"))
                }
                is Protocol.ServerMessage.Error -> handleServerError(message)
                Protocol.ServerMessage.Unknown -> Unit
            }
        }
    }

    private fun handleWelcome(message: Protocol.ServerMessage.Welcome) {
        // welcome 后重置退避
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            maintenanceBackoff = false
        }
        val latest = message.latest ?: return
        val shouldApply = synchronized(stateLock) {
            latest.version > serverVersion && latest.hashHex != lastSentHashHex
        }
        advanceServerVersion(latest.version)
        if (shouldApply) {
            applyRemotePayload(latest.payload, latest.encrypted, latest.hashHex)
        }
    }

    private fun handleServerClip(message: Protocol.ServerMessage.Clip) {
        val shouldApply = synchronized(stateLock) {
            message.version > serverVersion && message.hashHex != lastSentHashHex
        }
        advanceServerVersion(message.version)
        if (shouldApply) {
            applyRemotePayload(message.payload, message.encrypted, message.hashHex)
        }
    }

    private fun advanceServerVersion(version: Long) {
        val advanced = synchronized(stateLock) {
            if (version > serverVersion) {
                serverVersion = version
                true
            } else {
                false
            }
        }
        if (advanced) {
            runCatching { callbacks.onServerVersionAdvanced(version) }
        }
    }

    private fun applyRemotePayload(payload: String, encrypted: Boolean, hashHex: String) {
        runCatching {
            var text = payload
            if (encrypted) {
                val parsed = parseEncryptedPayload(payload)
                val keyBase64 = config.derivedKeyBase64
                check(keyBase64.isNotBlank()) { "No derived key available for decryption" }
                text = CryptoManager.decrypt(parsed, keyBase64)
            }
            val textBytes = text.toByteArray(Charsets.UTF_8)
            if (!isWithinLimits(textBytes)) return
            if (text.isEmpty()) return

            val applyGeneration = remoteApplyGeneration.get()
            synchronized(stateLock) {
                lastRemoteHashHex = hashHex
                suppressNextLocal = true
            }
            mainHandler.post {
                try {
                    clipboard.writeText(text)
                    callbacks.onRemoteTextApplied(text)
                } catch (error: Exception) {
                    synchronized(stateLock) {
                        if (lastRemoteHashHex == hashHex) {
                            lastRemoteHashHex = null
                            suppressNextLocal = false
                        }
                    }
                    status(
                        stringProvider.get(
                            R.string.status_inbound_error,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }.onFailure {
            status(stringProvider.get(R.string.status_inbound_error, it.message ?: it.javaClass.simpleName))
        }
    }

    private fun parseEncryptedPayload(payload: String): EncryptedPayload {
        val obj = JSONObject(payload)
        return EncryptedPayload(
            nonce = obj.getString("nonce"),
            ciphertext = obj.getString("ciphertext"),
            tag = obj.getString("tag")
        )
    }

    private fun handleServerError(message: Protocol.ServerMessage.Error) {
        when (message.code) {
            "invalid_message" -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "text_too_large" -> status(stringProvider.get(R.string.status_text_too_large_discarded))
            "empty_text" -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "rate_limited" -> {
                sendPausedUntilMs = nowMs() + 1000L
                status(stringProvider.get(R.string.status_send_rate_limited))
            }
            "hello_timeout" -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "server_busy" -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "frame_too_large" -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            else -> status(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
        }
    }

    private fun handleClosed(generation: Long, code: Int, reason: String) {
        if (!markDisconnected(generation)) return
        maintenanceBackoff = maintenanceBackoff || code == 1001
        val detail = "close $code ${reason.take(80)}"
        disconnectedStatus(
            stringProvider.get(R.string.status_disconnected, detail),
            detail
        )
        scheduleReconnect()
    }

    private fun handleError(generation: Long, error: Throwable) {
        if (!markDisconnected(generation)) return
        status(stringProvider.get(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
        scheduleReconnect()
    }

    private fun handleSessionExpired(generation: Long) {
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
        }
        cancelReconnectTasks()
        status(stringProvider.get(R.string.status_session_expired))
        callbacks.onSessionExpired()
    }

    private fun markDisconnected(generation: Long): Boolean {
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return false
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            return true
        }
    }

    // ------------------------------------------------------------------
    // 退避调度
    // ------------------------------------------------------------------

    internal fun backoffDelaySeconds(attempt: Int, maintenance: Boolean): Long {
        val delays = if (maintenance) backoffDelaysMaintenanceSeconds else backoffDelaysNormalSeconds
        if (delays.isEmpty()) return 1L
        return delays[attempt.coerceAtMost(delays.size - 1)]
    }

    private fun scheduleReconnect() {
        val connectionGen: Long
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED || connected) return
            connectionGen = connectionGeneration
        }
        val attempt: Int
        val taskGen: Long
        synchronized(reconnectTaskLock) {
            if (stopped || reconnectTask != null || reconnectInFlight) return
            reconnectInFlight = true
            attempt = reconnectAttempts++
            taskGen = ++reconnectGeneration
        }
        val delay = backoffDelaySeconds(attempt, maintenanceBackoff)
        status(stringProvider.get(R.string.status_waiting_reconnect, delay))
        scheduleReconnectTask(connectionGen, taskGen, delay)
    }

    private fun scheduleReconnectAfter(minDelaySeconds: Long) {
        val connectionGen: Long
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED || connected) return
            connectionGen = connectionGeneration
        }
        val taskGen: Long
        synchronized(reconnectTaskLock) {
            if (stopped || reconnectTask != null || reconnectInFlight) return
            reconnectInFlight = true
            taskGen = ++reconnectGeneration
        }
        val normalDelay = backoffDelaySeconds(reconnectAttempts, maintenanceBackoff)
        val delay = maxOf(minDelaySeconds, normalDelay)
        status(stringProvider.get(R.string.status_waiting_reconnect, delay))
        scheduleReconnectTask(connectionGen, taskGen, delay)
    }

    private fun scheduleReconnectTask(connectionGen: Long, taskGen: Long, delaySeconds: Long) {
        val exec = synchronized(connectionLock) { currentExecutorLocked() }
        if (exec == null) {
            synchronized(reconnectTaskLock) {
                if (taskGen == reconnectGeneration) reconnectInFlight = false
            }
            return
        }
        val task = try {
            exec.schedule({ performReconnect(connectionGen) }, delaySeconds, TimeUnit.SECONDS)
        } catch (_: RuntimeException) {
            synchronized(reconnectTaskLock) {
                if (taskGen == reconnectGeneration) reconnectInFlight = false
            }
            return
        }
        synchronized(reconnectTaskLock) {
            if (taskGen == reconnectGeneration && !stopped) {
                reconnectTask = task
            } else {
                task.cancel(false)
            }
        }
    }

    private fun performReconnect(connectionGen: Long) {
        synchronized(reconnectTaskLock) {
            reconnectTask = null
            reconnectInFlight = false
        }
        if (!isCurrentGeneration(connectionGen)) return
        connect(expectedGeneration = connectionGen)
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    private fun encryptOutbound(text: String): String? {
        return try {
            if (config.cipherEnabled) {
                CryptoManager.encryptedPayloadJson(
                    CryptoManager.encrypt(text, config.derivedKeyBase64)
                )
            } else {
                text
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendLocalTextInternal(text: String, source: String) {
        if (text.isEmpty()) return
        synchronized(stateLock) {
            if (suppressNextLocal) {
                suppressNextLocal = false
                return
            }
        }
        val now = nowMs()
        if (now < sendPausedUntilMs) {
            status(stringProvider.get(R.string.status_send_rate_limited))
            return
        }
        if (!connected) {
            status(stringProvider.get(R.string.status_ignored_not_connected, source))
            return
        }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (!isWithinLimits(textBytes)) return
        val hashHex = HashUtil.fnv1a64Hex(textBytes)
        synchronized(stateLock) {
            if (lastRemoteHashHex == hashHex) return
        }

        val payload = encryptOutbound(text)
        if (payload == null) {
            status(stringProvider.get(R.string.status_encryption_error))
            return
        }
        // 加密扩散后超出服务端 payload 限额：本地丢弃，避免无效传输与 text_too_large 回环
        if (!payloadWithinServerLimit(payload)) {
            status(stringProvider.get(R.string.status_clipboard_too_large, textBytes.size.toLong()))
            return
        }
        val body = Protocol.clipMessage(
            id = UUID.randomUUID().toString(),
            payload = payload,
            encrypted = config.cipherEnabled,
            hashHex = hashHex
        )
        if (body.toByteArray(Charsets.UTF_8).size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
            status(stringProvider.get(R.string.status_encoded_too_large))
            return
        }
        val currentTransport = synchronized(connectionLock) { transport }
        if (currentTransport == null) {
            status(stringProvider.get(R.string.status_ignored_not_connected, source))
            return
        }
        try {
            currentTransport.sendText(body)
        } catch (error: Exception) {
            status(stringProvider.get(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
            return
        }
        synchronized(stateLock) {
            lastSentHashHex = hashHex
        }
        status(stringProvider.get(R.string.status_connected_broadcasting))
    }

    private fun isWithinLimits(textBytes: ByteArray): Boolean {
        val businessLimit = minOf(config.maxTextBytes, config.localMaxClipboardBytes)
            .coerceIn(ClipConfig.MIN_CLIPBOARD_BYTES, ClipConfig.MAX_CLIPBOARD_BYTES)
        val bytes = textBytes.size.toLong()
        val ok = bytes in 1..businessLimit
        if (!ok) status(stringProvider.get(R.string.status_clipboard_too_large, bytes))
        return ok
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(connectionLock) {
        isCurrentGenerationLocked(generation)
    }

    private fun isCurrentGenerationLocked(generation: Long): Boolean =
        !stopped && lifecycle != ConnectionLifecycle.STOPPED && generation == connectionGeneration

    private fun cancelReconnectTasks() {
        synchronized(reconnectTaskLock) {
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectInFlight = false
            reconnectAttempts = 0
            ++reconnectGeneration
        }
    }

    private fun status(message: String) {
        callbacks.onStatus(message)
    }

    private inner class GenerationListener(private val generation: Long) : RawWebSocketClient.Listener {
        override fun onOpen() = handleOpen(generation)
        override fun onText(text: String) = handleMessage(generation, text)
        override fun onClosed(code: Int, reason: String) = handleClosed(generation, code, reason)
        override fun onError(error: Throwable) = handleError(generation, error)
        override fun onSessionExpired(error: SessionExpiredException) = handleSessionExpired(generation)
    }

    companion object {
        private const val USER_PRESENT_RECONNECT_DELAY_SECONDS = 3L
    }
}
