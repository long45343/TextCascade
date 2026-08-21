package com.textcascad.v2.engine

import com.textcascad.v2.CachedReloginResult
import com.textcascad.v2.ClipConfig
import com.textcascad.v2.R
import com.textcascad.v2.RawWebSocketClient
import com.textcascad.v2.SessionExpiredException
import com.textcascad.v2.StringProvider
import com.textcascad.v2.SyncTransport
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 与 TextSyncEngine 原构造器默认 transport lambda 等价的 5 参工厂。
 */
typealias TransportFactory = (
    url: String,
    token: String,
    listener: RawWebSocketClient.Listener,
    trustAllCerts: Boolean,
    rxTimeoutMs: Long
) -> SyncTransport

/**
 * 连接生命周期与重连调度（自 TextSyncEngine 迁出）：
 * 持有 generation、executor、transport、退避状态；会话/编码关注点经 [ConnectionEvents] 委托回引擎。
 * 锁对象与临界区范围与原引擎实现逐字一致。
 */
interface ConnectionEvents {
    fun onStatus(message: String)

    /** 断连明细（原引擎 disconnectedStatus lambda：message + 通知 subText）。 */
    fun onDisconnectedStatus(message: String, subText: String)

    /** 会话失效（401/token 过期重登无凭据）：停止重连，交上层处理。 */
    fun onSessionExpired()

    /** 引擎需要 HTTP 重登（token 预判过期时同步调用，阻塞在引擎线程）。 */
    fun onCachedReloginRequired(): CachedReloginResult

    /** 传输层文本帧到达（generation 已绑定），由引擎做入站分发。 */
    fun onInboundText(generation: Long, body: String)

    /** 连接建立完成（已发 status_connected），由引擎发送 hello。 */
    fun onConnected(generation: Long, transport: SyncTransport?)
}

class ConnectionManager(
    private val config: ClipConfig,
    private val state: SyncStateStore,
    private val executorFactory: () -> ScheduledExecutorService,
    private val transportFactory: TransportFactory,
    private val nowMs: () -> Long,
    private val stringProvider: StringProvider,
    private val userPresentReconnectDelaySeconds: Long,
    private val rateLimitedReloginFloorSeconds: Long,
    private val backoffDelaysNormalSeconds: List<Long>,
    private val backoffDelaysMaintenanceSeconds: List<Long>,
    private val events: ConnectionEvents
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

    fun handleOpen(generation: Long) {
        val currentTransport: SyncTransport?
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.CONNECTED
            connected = true
            connecting = false
            currentTransport = transport
        }
        status(stringProvider.get(R.string.status_connected))
        events.onConnected(generation, currentTransport)
    }

    fun handleClosed(generation: Long, code: Int, reason: String) {
        if (!markDisconnected(generation)) return
        maintenanceBackoff = maintenanceBackoff || code == 1001
        val detail = "close $code ${reason.take(80)}"
        events.onDisconnectedStatus(
            stringProvider.get(R.string.status_disconnected, detail),
            detail
        )
        scheduleReconnect()
    }

    fun handleError(generation: Long, error: Throwable) {
        if (!markDisconnected(generation)) return
        status(stringProvider.get(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
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
        status(stringProvider.get(R.string.status_session_expired))
        events.onSessionExpired()
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
            generation = connectionGeneration
            oldTransport = transport
            transport = null
        }

        runCatching { oldTransport?.close(1000, "superseded") }

        // token 本地预判过期：先 HTTP 重登，避免必然失败的 401 往返
        if (tokenNeedsRelogin()) {
            status(stringProvider.get(R.string.status_relogin_with_cached))
            val result = try {
                events.onCachedReloginRequired()
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
        val rxTimeoutMs = RawWebSocketClient.watchdogRxTimeoutMs(config.userPrefs.heartbeatTimeoutSeconds)
        val newTransport = try {
            transportFactory(
                config.websocketUrl,
                config.session.token,
                GenerationListener(generation),
                config.cryptoMaterial.trustAllCerts,
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
        events.onStatus(message)
    }

    private inner class GenerationListener(private val generation: Long) : RawWebSocketClient.Listener {
        override fun onOpen() = handleOpen(generation)
        override fun onText(text: String) = events.onInboundText(generation, text)
        override fun onClosed(code: Int, reason: String) = handleClosed(generation, code, reason)
        override fun onError(error: Throwable) = handleError(generation, error)
        override fun onSessionExpired(error: SessionExpiredException) = handleSessionExpired(generation)
    }
}
