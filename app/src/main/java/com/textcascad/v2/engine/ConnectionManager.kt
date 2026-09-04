package com.textcascad.v2.engine

import com.textcascad.v2.AuthResult
import com.textcascad.v2.ClipConfig
import com.textcascad.v2.OkHttpTransport
import com.textcascad.v2.Protocol
import com.textcascad.v2.R
import com.textcascad.v2.SessionExpiredException
import com.textcascad.v2.StringProvider
import com.textcascad.v2.SyncTransport
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 与 TextSyncEngine 原构造器默认 transport lambda 等价的 6 参工厂。
 */
typealias TransportFactory = (
    url: String,
    token: String,
    listener: SyncTransport.Listener,
    trustAllCerts: Boolean,
    pinnedCertSha256: String,
    rxTimeoutMs: Long
) -> SyncTransport

/**
 * 断连结果的结构化明细：状态文案由引擎层据此生成，连接层不回传字符串。
 */
sealed class ConnectionCloseInfo {
    data class Closed(val code: Int, val reason: String) : ConnectionCloseInfo()
    data class Error(val error: Throwable) : ConnectionCloseInfo()
}

class ConnectionManager(
    private val config: ClipConfig,
    private val state: SyncStateStore,
    private val executorFactory: () -> ScheduledExecutorService,
    private val transportFactory: TransportFactory,
    private val nowMs: () -> Long,
    private val stringProvider: StringProvider,
    private val rateLimitedReloginFloorSeconds: Long,
    private val backoffDelaysNormalSeconds: List<Long>,
    private val backoffDelaysMaintenanceSeconds: List<Long>,
    private val onCachedReloginRequired: () -> AuthResult,
    /** 连接生命周期内部的文案（connecting/waiting/relogin/session expired）。 */
    private val onStatus: (String) -> Unit,
    /** 少量显式连接入口，替代原宽接口 ConnectionEvents。 */
    private val onConnected: (generation: Long, transport: SyncTransport?) -> Unit,
    private val onInboundText: (generation: Long, body: String) -> Unit,
    private val onClosed: (generation: Long, closeInfo: ConnectionCloseInfo) -> Unit,
    private val onSessionExpired: () -> Unit
) {
    private enum class ConnectionLifecycle {
        STOPPED,
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

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

    private val reconnectTaskLock = Any()
    private var reconnectGeneration = 0L
    private var reconnectAttempts = 0
    private var reconnectInFlight = false

    // 恢复活动信号（Doze 退出 / 回到 App 的 RESUME_RECONNECT）触发重连的状态
    @Volatile
    private var lastAwakeReconnectAtMs = 0L
    private var connectingSinceMs = 0L

    val isConnected: Boolean get() = connected
    val isConnecting: Boolean get() = connecting
    val isStopped: Boolean get() = stopped

    internal fun executorForTest(): ScheduledExecutorService? = synchronized(connectionLock) { executor }

    internal fun connectionGenerationForTest(): Long = synchronized(connectionLock) { connectionGeneration }

    fun currentGeneration(): Long = synchronized(connectionLock) { connectionGeneration }

    fun currentTransport(): SyncTransport? = synchronized(connectionLock) { transport }

    fun isCurrentGeneration(generation: Long): Boolean = synchronized(connectionLock) {
        isCurrentGenerationLocked(generation)
    }

    private fun isCurrentGenerationLocked(generation: Long): Boolean =
        !stopped && lifecycle != ConnectionLifecycle.STOPPED && generation == connectionGeneration

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
            state.serverVersion = config.userPrefs.lastServerVersion
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
            state.incrementRemoteApplyGeneration()
            oldTransport = transport
            transport = null
            oldExecutor = executor
            executor = null
        }
        cancelReconnectTasks()
        runCatching { oldTransport?.close(1000, "client_stop") }
        oldExecutor?.shutdownNow()
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
            state.incrementRemoteApplyGeneration()
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

    /**
     * 恢复活动信号（Doze 退出 / RESUME_RECONNECT）：无条件重建连接。
     * - CONNECTED：半开/陈旧连接强制刷新（abort 旧连接再连）
     * - DISCONNECTED：取消退避任务立即重连（保留退避档位记忆）
     * - CONNECTING：仅当超过陈旧守卫时长才强制刷新（OkHttp readTimeout=0，握手读无超时）
     */
    fun onDeviceAwake() {
        val exec = synchronized(connectionLock) { currentExecutorLocked() } ?: return
        exec.execute {
            synchronized(connectionLock) {
                if (stopped) return@execute
                if (nowMs() - lastAwakeReconnectAtMs < AWAKE_DEBOUNCE_MS) return@execute
                lastAwakeReconnectAtMs = nowMs()
            }
            val lifecycleNow = synchronized(connectionLock) { lifecycle }
            when (lifecycleNow) {
                ConnectionLifecycle.CONNECTED -> forceReconnect()
                ConnectionLifecycle.DISCONNECTED -> {
                    cancelAwakeReconnectTask()
                    performReconnect(currentGeneration())
                }
                ConnectionLifecycle.CONNECTING ->
                    if (nowMs() - connectingSinceMs > STALE_CONNECTING_MS) forceReconnect()
                ConnectionLifecycle.STOPPED -> Unit
            }
        }
    }

    /** 仅取消退避任务并复位 in-flight 标记；保留退避档位记忆（区别于 cancelReconnectTasks）。 */
    private fun cancelAwakeReconnectTask() {
        synchronized(reconnectTaskLock) {
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectInFlight = false
            ++reconnectGeneration
        }
    }

    // ------------------------------------------------------------------
    // 传输回调
    // ------------------------------------------------------------------

    fun handleOpen(generation: Long) {
        val currentTransport: SyncTransport?
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.CONNECTED
            connected = true
            connecting = false
            currentTransport = transport
        }
        onStatus(stringProvider.get(R.string.status_connected))
        onConnected(generation, currentTransport)
    }

    fun handleClosed(generation: Long, code: Int, reason: String) {
        if (!markDisconnected(generation)) return
        maintenanceBackoff = maintenanceBackoff || code == 1001
        onClosed(generation, ConnectionCloseInfo.Closed(code, reason.take(80)))
        scheduleReconnect()
    }

    fun handleError(generation: Long, error: Throwable) {
        if (!markDisconnected(generation)) return
        onClosed(generation, ConnectionCloseInfo.Error(error))
        scheduleReconnect()
    }

    fun handleSessionExpired(generation: Long) {
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            ++connectionGeneration
            state.incrementRemoteApplyGeneration()
        }
        cancelReconnectTasks()
        onStatus(stringProvider.get(R.string.status_session_expired))
        onSessionExpired()
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
    // 连接与重连
    // ------------------------------------------------------------------

    private fun currentExecutorLocked(): ScheduledExecutorService? {
        if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return null
        val existing = executor
        if (existing != null && !existing.isShutdown) return existing
        return executorFactory().also { executor = it }
    }

    fun submit(task: () -> Unit): Boolean {
        val currentExecutor = synchronized(connectionLock) { currentExecutorLocked() } ?: return false
        return try {
            currentExecutor.execute(task)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun tokenNeedsRelogin(): Boolean {
        val expiresAt = config.session.tokenExpiresAtUtc
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
            connectingSinceMs = nowMs()
            generation = connectionGeneration
            oldTransport = transport
            transport = null
        }

        runCatching { oldTransport?.close(1000, "superseded") }

        // token 本地预判过期：先 HTTP 重登，避免必然失败的 401 往返
        if (tokenNeedsRelogin()) {
            status(stringProvider.get(R.string.status_relogin_with_cached))
            val result = try {
                onCachedReloginRequired()
            } catch (error: Throwable) {
                AuthResult.Failed(error)
            }
            if (!isCurrentGeneration(generation)) return
            when (result) {
                is AuthResult.Success -> {
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
                is AuthResult.RateLimited -> scheduleReconnectAfter(
                    maxOf(rateLimitedReloginFloorSeconds, result.retryAfterSeconds ?: 0L)
                )
                is AuthResult.AuthRejected -> handleSessionExpired(generation)
                is AuthResult.NoCredentials -> handleSessionExpired(generation)
                is AuthResult.ProtocolUnsupported -> {
                    status(
                        stringProvider.get(
                            R.string.status_protocol_unsupported,
                            result.serverVersion,
                            Protocol.SUPPORTED_PROTOCOL_VERSION
                        )
                    )
                    handleSessionExpired(generation)
                }
                else -> {
                    status(
                        stringProvider.get(
                            R.string.status_relogin_failed,
                            (result as? AuthResult.Failed)?.error?.message.orEmpty()
                        )
                    )
                    scheduleReconnect()
                }
            }
            // 重登结果分支已决定退避/终止/等待重启；不得使用旧 token 继续走下方建连。
            return
        }
        status(stringProvider.get(R.string.status_connecting))
        val rxTimeoutMs = OkHttpTransport.watchdogRxTimeoutMs(config.userPrefs.heartbeatIntervalSeconds)
        val newTransport = try {
            transportFactory(
                config.websocketUrl,
                config.session.token,
                GenerationListener(generation),
                config.cryptoMaterial.trustAllCerts,
                config.cryptoMaterial.pinnedCertSha256,
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

    private fun cancelReconnectTasks() {
        synchronized(reconnectTaskLock) {
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectInFlight = false
            reconnectAttempts = 0
            ++reconnectGeneration
        }
    }

    /** welcome 到达后重置退避（原引擎 inboundCallbacks.onWelcomeBackoffReset）。 */
    fun resetBackoffState() {
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            maintenanceBackoff = false
        }
    }

    /** bye/维护模式启用温和退避（原引擎 inboundCallbacks.onMaintenanceBackoffEnabled）。 */
    fun enableMaintenanceBackoff() {
        maintenanceBackoff = true
    }

    private fun status(message: String) {
        onStatus(message)
    }

    private inner class GenerationListener(private val generation: Long) : SyncTransport.Listener {
        override fun onOpen() = handleOpen(generation)
        override fun onText(text: String) = onInboundText(generation, text)
        override fun onClosed(code: Int, reason: String) = handleClosed(generation, code, reason)
        override fun onError(error: Throwable) = handleError(generation, error)
        override fun onSessionExpired(error: SessionExpiredException) = handleSessionExpired(generation)
    }

    companion object {
        /** Doze 退出与回 App（RESUME_RECONNECT）可能在短窗口内先后到达；防重复建连。 */
        private const val AWAKE_DEBOUNCE_MS = 5_000L

        /** OkHttp readTimeout=0 使握手读无超时；超过该时长的 CONNECTING 视为陈旧并强制刷新。 */
        private const val STALE_CONNECTING_MS = 30_000L
    }
}




