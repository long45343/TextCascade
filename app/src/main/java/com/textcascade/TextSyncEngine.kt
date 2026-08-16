/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
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

package com.textcascade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TextSyncEngine(
    private val context: Context,
    private val config: ClipConfig,
    private val callbacks: Callbacks,
    private val disconnectedStatus: (message: String) -> Unit = callbacks::onStatus,
    // R13: 测试接缝 - 允许注入自定义 StompTransport 工厂
    private val stompClientFactory: (String, String, StompClient.Listener, Boolean) -> StompTransport =
        { url, cookie, listener, trustAll -> StompClient(url, cookie, listener, trustAll) },
    private val reconnectDelayPolicy: (firstDisconnectTime: Long) -> Long = { firstDiscTime ->
        if (firstDiscTime == 0L) 10L
        else {
            val elapsed = (System.currentTimeMillis() - firstDiscTime) / 1000
            when {
                elapsed < 600 -> 10L
                elapsed < 1800 -> 60L
                elapsed < 3600 -> 180L
                else -> 300L
            }
        }
    },
    private val userPresentReconnectDelaySeconds: Long = USER_PRESENT_RECONNECT_DELAY_SECONDS,
    private val clipboardWriter: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TextCascade", text))
    }
) : StompClient.Listener {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRemoteTextApplied(text: String)
        // R2: 会话失效回调
        fun onSessionExpired() {}
        // 两阶段断线恢复：缓存凭据重登回调
        fun onCachedReloginRequired(): CachedReloginResult = CachedReloginResult.NoCredentials
    }

    private enum class PendingReconnectAction {
        NONE,
        COOKIE,
        CACHED_RELOGIN
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var executor: ScheduledExecutorService? = null
    private var stompClient: StompTransport? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    @Volatile
    private var stopped = false
    @Volatile
    private var connected = false
    @Volatile
    private var connecting = false
    @Volatile
    private var firstDisconnectTime = 0L
    // R3: single-flight 重连标志
    @Volatile
    private var reconnectInFlight = false
    // 两阶段重连计数
    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var pendingReconnectAction = PendingReconnectAction.NONE

    private val reconnectTaskLock = Any()
    private var reconnectGeneration = 0L

    // R9: 状态字段同步锁
    private val stateLock = Any()
    private var previousHash: Long? = null
    private var suppressNextLocal = false

    val isConnected: Boolean
        get() = connected

    val isConnecting: Boolean
        get() = connecting

    val isStopped: Boolean
        get() = stopped

    fun start() {
        stopped = false
        ensureExecutor()
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        connecting = false
        reconnectInFlight = false
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectGeneration++
        }
        stompClient?.close()
        stompClient = null
        executor?.shutdownNow()
        executor = null
    }

    private fun ensureExecutor(): ScheduledExecutorService {
        val existing = executor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "textcascade-sync").apply { isDaemon = true }
        }.also { executor = it }
    }

    fun sendLocalText(text: String, source: String) {
        ensureExecutor().execute {
            sendLocalTextInternal(text, source)
        }
    }

    override fun onConnected() {
        connected = true
        connecting = false
        firstDisconnectTime = 0L
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectInFlight = false
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectGeneration++
        }
        status(context.getString(R.string.status_connected))
        stompClient?.subscribe("/user/queue/cliptext")
    }

    override fun onMessage(body: String) {
        ensureExecutor().execute {
            runCatching {
                val message = JsonUtil.parseClipMessage(body)
                if (message.type != "text") {
                    return@runCatching
                }
                var text = message.payload
                if (config.cipherEnabled) {
                    text = CryptoManager.decrypt(
                        JsonUtil.parseEncryptedPayload(text),
                        config.hashedPasswordBase64
                    )
                }
                val hash = HashUtil.fnv1a64(text)
                val previousHashBefore: Long?
                // R9: 线程安全读取 previousHash
                synchronized(stateLock) {
                    if (previousHash == hash) {
                        return@runCatching
                    }
                    previousHashBefore = previousHash
                }
                if (!isWithinLimits(text, context.getString(R.string.direction_inbound))) {
                    return@runCatching
                }
                // R9: 线程安全设置状态
                synchronized(stateLock) {
                    previousHash = hash
                    suppressNextLocal = true
                }
                mainHandler.post {
                    try {
                        clipboardWriter(text)
                        callbacks.onRemoteTextApplied(text)
                    } catch (e: Exception) {
                        synchronized(stateLock) {
                            if (previousHash == hash && suppressNextLocal) {
                                previousHash = previousHashBefore
                                suppressNextLocal = false
                            }
                        }
                        status(context.getString(R.string.status_inbound_error, e.message ?: e.javaClass.simpleName))
                    }
                }
            }.onFailure {
                status(context.getString(R.string.status_inbound_error, it.message))
            }
        }
    }
    override fun onClosed(reason: String) {
        connected = false
        connecting = false
        if (firstDisconnectTime == 0L) {
            firstDisconnectTime = System.currentTimeMillis()
        }
        disconnectedStatus(context.getString(R.string.status_disconnected, reason))
        scheduleReconnect()
    }

    override fun onError(error: Throwable) {
        connected = false
        connecting = false
        if (firstDisconnectTime == 0L) {
            firstDisconnectTime = System.currentTimeMillis()
        }
        status(context.getString(R.string.status_websocket_error, error.message))
        scheduleReconnect()
    }

    // R2: 会话失效 - 不调度重连
    override fun onSessionExpired(error: SessionExpiredException) {
        connected = false
        connecting = false
        reconnectInFlight = false
        synchronized(reconnectTaskLock) {
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectGeneration++
        }
        status(context.getString(R.string.status_session_expired))
        callbacks.onSessionExpired()
    }

    private fun connect(force: Boolean = false) {
        if (stopped || (!force && (connected || connecting))) {
            return
        }
        connected = false
        connecting = true
        status(context.getString(R.string.status_connecting))
        stompClient?.close()
        stompClient = stompClientFactory(config.websocketUrl, config.cookieHeader, this, config.trustAllCerts).also {
            it.connect()
        }
    }

    private fun performPendingReconnectAttempt(
        attempt: Int,
        action: PendingReconnectAction,
        taskGeneration: Long
    ) {
        synchronized(reconnectTaskLock) {
            if (stopped || taskGeneration != reconnectGeneration) return
            pendingReconnectAction = PendingReconnectAction.NONE
        }

        when (action) {
            PendingReconnectAction.COOKIE -> {
                reconnectInFlight = false
                connect()
            }
            PendingReconnectAction.CACHED_RELOGIN -> {
                val result = try {
                    callbacks.onCachedReloginRequired()
                } catch (e: Throwable) {
                    CachedReloginResult.TransientFailure(e)
                }

                synchronized(reconnectTaskLock) {
                    if (stopped || taskGeneration != reconnectGeneration) return
                }

                when (result) {
                    is CachedReloginResult.Success -> {
                        // 登录成功，Service 会重启并重建 engine；旧 engine 不再发起操作
                    }
                    CachedReloginResult.AuthFailure,
                    is CachedReloginResult.TransientFailure -> {
                        reconnectInFlight = false
                        scheduleReconnect()
                    }
                    CachedReloginResult.NoCredentials -> {
                        reconnectInFlight = false
                        onSessionExpired(SessionExpiredException(401))
                    }
                }
            }
            PendingReconnectAction.NONE -> Unit
        }
    }

    // 两阶段重连：1~2次走 cookie WebSocket，第3次起走 HTTP 缓存凭据重登
    private fun scheduleReconnect() {
        if (stopped || connected) {
            return
        }
        if (reconnectInFlight) {
            return
        }

        reconnectInFlight = true
        val attempt = ++reconnectAttempts
        val delay = reconnectDelayPolicy(firstDisconnectTime)
        val taskGen: Long
        val action = if (attempt <= COOKIE_RECONNECT_ATTEMPTS) PendingReconnectAction.COOKIE else PendingReconnectAction.CACHED_RELOGIN

        synchronized(reconnectTaskLock) {
            reconnectTask?.cancel(false)
            taskGen = ++reconnectGeneration
            pendingReconnectAction = action
        }

        if (action == PendingReconnectAction.COOKIE) {
            status(context.getString(R.string.status_waiting_reconnect, delay))
        } else {
            status(context.getString(R.string.status_relogin_with_cached))
        }

        val exec = ensureExecutor()
        val task = exec.schedule({
            performPendingReconnectAttempt(attempt, action, taskGen)
        }, delay, TimeUnit.SECONDS)

        synchronized(reconnectTaskLock) {
            if (taskGen == reconnectGeneration) {
                reconnectTask = task
            }
        }
    }

    fun forceReconnect() {
        firstDisconnectTime = 0L
        stopped = false
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectInFlight = false
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectGeneration++
        }
        ensureExecutor().execute { connect(force = true) }
    }

    fun reconnectAfterUserPresent() {
        val exec = ensureExecutor()
        exec.execute {
            if (stopped || connected || connecting || firstDisconnectTime == 0L) {
                return@execute
            }

            val action: PendingReconnectAction
            val taskGen: Long

            synchronized(reconnectTaskLock) {
                val currentAction = pendingReconnectAction
                if (currentAction == PendingReconnectAction.NONE) {
                    return@execute
                }

                reconnectTask?.cancel(false)
                reconnectGeneration++
                taskGen = reconnectGeneration
                action = currentAction
            }

            val task = exec.schedule({
                performPendingReconnectAttempt(
                    attempt = reconnectAttempts,
                    action = action,
                    taskGeneration = taskGen
                )
            }, userPresentReconnectDelaySeconds, TimeUnit.SECONDS)

            synchronized(reconnectTaskLock) {
                if (!stopped && taskGen == reconnectGeneration) {
                    reconnectTask = task
                } else {
                    task.cancel(false)
                }
            }
        }
    }

    private fun sendLocalTextInternal(text: String, source: String) {
        if (text.isBlank()) {
            return
        }
        // R9: 线程安全读取 suppressNextLocal
        synchronized(stateLock) {
            if (suppressNextLocal) {
                suppressNextLocal = false
                return
            }
        }
        if (!connected) {
            status(context.getString(R.string.status_ignored_not_connected, source))
            return
        }
        if (!isWithinLimits(text, context.getString(R.string.direction_outbound))) {
            return
        }

        val hash = HashUtil.fnv1a64(text)
        // R9: 线程安全读取 previousHash
        synchronized(stateLock) {
            if (previousHash == hash) {
                return
            }
        }

        val payload = try {
            if (config.cipherEnabled) {
                JsonUtil.encryptedPayload(
                    CryptoManager.encrypt(text, config.hashedPasswordBase64)
                )
            } else {
                text
            }
        } catch (error: Exception) {
            status(
                context.getString(
                    R.string.status_websocket_error,
                    error.message ?: error.javaClass.simpleName
                )
            )
            return
        }

        try {
            stompClient?.send(
                destination = "/app/cliptext",
                body = JsonUtil.clipMessage(payload, "text")
            ) ?: return
        } catch (error: Exception) {
            status(
                context.getString(
                    R.string.status_websocket_error,
                    error.message ?: error.javaClass.simpleName
                )
            )
            return
        }
        synchronized(stateLock) {
            previousHash = hash
        }
        status(context.getString(R.string.status_connected_broadcasting))
    }

    private fun isWithinLimits(text: String, direction: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        val localLimit = config.localMaxClipboardBytes.takeIf { it > 0 } ?: config.maxSizeBytes
        val ok = bytes <= config.maxSizeBytes && bytes <= localLimit
        if (!ok) {
            status(context.getString(R.string.status_clipboard_too_large, direction, bytes))
        }
        return ok
    }

    private fun status(message: String) {
        callbacks.onStatus(message)
    }

    companion object {
        private const val COOKIE_RECONNECT_ATTEMPTS = 2
        private const val USER_PRESENT_RECONNECT_DELAY_SECONDS = 3L
    }
}
