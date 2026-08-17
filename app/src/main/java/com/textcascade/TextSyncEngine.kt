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
import java.util.concurrent.atomic.AtomicLong

class TextSyncEngine(
    private val context: Context,
    private val config: ClipConfig,
    private val callbacks: Callbacks,
    private val disconnectedStatus: (message: String) -> Unit = callbacks::onStatus,
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
    internal val executorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "textcascade-sync").apply { isDaemon = true }
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
        fun onSessionExpired() {}
        fun onCachedReloginRequired(): CachedReloginResult = CachedReloginResult.NoCredentials
    }

    private enum class PendingReconnectAction {
        NONE,
        COOKIE,
        CACHED_RELOGIN
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
    private var stompClient: StompTransport? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    @Volatile
    private var stopped = true
    @Volatile
    private var connected = false
    @Volatile
    private var connecting = false
    @Volatile
    private var firstDisconnectTime = 0L
    @Volatile
    private var reconnectInFlight = false
    @Volatile
    private var reconnectAttempts = 0
    @Volatile
    private var pendingReconnectAction = PendingReconnectAction.NONE

    private val reconnectTaskLock = Any()
    private var reconnectGeneration = 0L

    private val stateLock = Any()
    private var previousHash: Long? = null
    private var suppressNextLocal = false
    private val remoteApplyGeneration = AtomicLong(0L)

    val isConnected: Boolean get() = connected
    val isConnecting: Boolean get() = connecting
    val isStopped: Boolean get() = stopped

    internal fun executorForTest(): ScheduledExecutorService? = synchronized(connectionLock) { executor }

    internal fun connectionGenerationForTest(): Long = synchronized(connectionLock) { connectionGeneration }

    fun start() {
        val generation: Long
        val currentExecutor: ScheduledExecutorService
        synchronized(connectionLock) {
            if (lifecycle != ConnectionLifecycle.STOPPED) return
            stopped = false
            connected = false
            connecting = false
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
        val oldTransport: StompTransport?
        val oldExecutor: ScheduledExecutorService?
        synchronized(connectionLock) {
            stopped = true
            connected = false
            connecting = false
            lifecycle = ConnectionLifecycle.STOPPED
            ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
            oldTransport = stompClient
            stompClient = null
            oldExecutor = executor
            executor = null
        }
        cancelReconnectTasks()
        oldTransport?.close()
        oldExecutor?.shutdownNow()
    }

    fun sendLocalText(text: String, source: String) {
        submitToCurrentExecutor { sendLocalTextInternal(text, source) }
    }

    override fun onConnected() {
        handleConnected(synchronized(connectionLock) { connectionGeneration })
    }

    override fun onMessage(body: String) {
        val generation = synchronized(connectionLock) { connectionGeneration }
        handleMessage(generation, body)
    }

    override fun onClosed(reason: String) {
        handleClosed(synchronized(connectionLock) { connectionGeneration }, reason)
    }

    override fun onError(error: Throwable) {
        handleError(synchronized(connectionLock) { connectionGeneration }, error)
    }

    override fun onSessionExpired(error: SessionExpiredException) {
        handleSessionExpired(synchronized(connectionLock) { connectionGeneration }, error)
    }

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

    private fun connect(force: Boolean = false, expectedGeneration: Long? = null) {
        val oldTransport: StompTransport?
        val generation: Long
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return
            if (expectedGeneration != null && expectedGeneration != connectionGeneration) return
            if (!force && lifecycle != ConnectionLifecycle.DISCONNECTED) return
            lifecycle = ConnectionLifecycle.CONNECTING
            connected = false
            connecting = true
            generation = connectionGeneration
            oldTransport = stompClient
            stompClient = null
        }

        oldTransport?.close()
        status(context.getString(R.string.status_connecting))
        val transport = try {
            stompClientFactory(
                config.websocketUrl,
                config.cookieHeader,
                GenerationListener(generation),
                config.trustAllCerts
            )
        } catch (error: Throwable) {
            handleError(generation, error)
            return
        }

        val accepted = synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED || generation != connectionGeneration) {
                false
            } else {
                stompClient = transport
                true
            }
        }
        if (!accepted) {
            transport.close()
            return
        }
        try {
            transport.connect()
        } catch (error: Throwable) {
            handleError(generation, error)
        }
    }

    private fun handleConnected(generation: Long) {
        val currentTransport: StompTransport?
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.CONNECTED
            connected = true
            connecting = false
            firstDisconnectTime = 0L
            currentTransport = stompClient
        }
        synchronized(reconnectTaskLock) {
            reconnectAttempts = 0
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectInFlight = false
            reconnectTask?.cancel(false)
            reconnectTask = null
            ++reconnectGeneration
        }
        status(context.getString(R.string.status_connected))
        runCatching { currentTransport?.subscribe("/user/queue/cliptext") }
            .onFailure { handleError(generation, it) }
    }

    private fun handleMessage(generation: Long, body: String) {
        submitToCurrentExecutor task@{
            if (!isCurrentGeneration(generation)) return@task
            if (body.toByteArray(Charsets.UTF_8).size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
                status(context.getString(R.string.status_encoded_too_large))
                return@task
            }
            runCatching {
                val message = JsonUtil.parseClipMessage(body)
                if (message.type != "text") return@runCatching
                if (message.payload.toByteArray(Charsets.UTF_8).size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
                    status(context.getString(R.string.status_encoded_too_large))
                    return@runCatching
                }
                var text = message.payload
                if (config.cipherEnabled) {
                    text = CryptoManager.decrypt(
                        JsonUtil.parseEncryptedPayload(text),
                        config.hashedPasswordBase64
                    )
                }
                if (!isCurrentGeneration(generation)) return@runCatching
                val hash = HashUtil.fnv1a64(text)
                val previousHashBefore: Long?
                synchronized(stateLock) {
                    if (previousHash == hash) return@runCatching
                    previousHashBefore = previousHash
                }
                if (!isWithinLimits(text, context.getString(R.string.direction_inbound))) return@runCatching
                val applyGeneration = remoteApplyGeneration.get()
                synchronized(stateLock) {
                    if (!isCurrentGeneration(generation) || remoteApplyGeneration.get() != applyGeneration) {
                        return@runCatching
                    }
                    previousHash = hash
                    suppressNextLocal = true
                }
                mainHandler.post {
                    if (!isCurrentGeneration(generation) || remoteApplyGeneration.get() != applyGeneration) {
                        return@post
                    }
                    try {
                        clipboardWriter(text)
                        callbacks.onRemoteTextApplied(text)
                    } catch (error: Exception) {
                        synchronized(stateLock) {
                            if (isCurrentGeneration(generation) &&
                                remoteApplyGeneration.get() == applyGeneration &&
                                previousHash == hash && suppressNextLocal
                            ) {
                                previousHash = previousHashBefore
                                suppressNextLocal = false
                            }
                        }
                        if (isCurrentGeneration(generation) && remoteApplyGeneration.get() == applyGeneration) {
                            status(context.getString(R.string.status_inbound_error, error.message ?: error.javaClass.simpleName))
                        }
                    }
                }
            }.onFailure {
                if (isCurrentGeneration(generation)) {
                    status(context.getString(R.string.status_inbound_error, it.message ?: it.javaClass.simpleName))
                }
            }
        }
    }

    private fun handleClosed(generation: Long, reason: String) {
        if (!markDisconnected(generation)) return
        disconnectedStatus(context.getString(R.string.status_disconnected, reason))
        scheduleReconnect()
    }

    private fun handleError(generation: Long, error: Throwable) {
        if (!markDisconnected(generation)) return
        status(context.getString(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
        scheduleReconnect()
    }

    private fun handleSessionExpired(generation: Long, error: SessionExpiredException) {
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
        }
        cancelReconnectTasks()
        status(context.getString(R.string.status_session_expired))
        callbacks.onSessionExpired()
    }

    private fun markDisconnected(generation: Long): Boolean {
        synchronized(connectionLock) {
            if (!isCurrentGenerationLocked(generation)) return false
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            if (firstDisconnectTime == 0L) firstDisconnectTime = System.currentTimeMillis()
            return true
        }
    }

    private fun performPendingReconnectAttempt(
        action: PendingReconnectAction,
        taskGeneration: Long,
        connectionGen: Long
    ) {
        synchronized(reconnectTaskLock) {
            if (stopped || taskGeneration != reconnectGeneration) return
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectTask = null
        }
        if (!isCurrentGeneration(connectionGen)) return
        when (action) {
            PendingReconnectAction.COOKIE -> {
                reconnectInFlight = false
                connect(expectedGeneration = connectionGen)
            }
            PendingReconnectAction.CACHED_RELOGIN -> {
                val result = try {
                    callbacks.onCachedReloginRequired()
                } catch (error: Throwable) {
                    CachedReloginResult.TransientFailure(error)
                }
                if (!isCurrentGeneration(connectionGen)) return
                when (result) {
                    is CachedReloginResult.Success -> Unit
                    CachedReloginResult.AuthFailure,
                    is CachedReloginResult.TransientFailure -> {
                        reconnectInFlight = false
                        scheduleReconnect()
                    }
                    CachedReloginResult.NoCredentials -> {
                        reconnectInFlight = false
                        handleSessionExpired(connectionGen, SessionExpiredException(401))
                    }
                }
            }
            PendingReconnectAction.NONE -> Unit
        }
    }

    private fun scheduleReconnect() {
        val connectionGen: Long
        val action: PendingReconnectAction
        val attempt: Int
        val taskGen: Long
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED || connected) return
            connectionGen = connectionGeneration
        }
        synchronized(reconnectTaskLock) {
            if (stopped || reconnectTask != null || reconnectInFlight) return
            reconnectInFlight = true
            attempt = ++reconnectAttempts
            action = if (attempt <= COOKIE_RECONNECT_ATTEMPTS) {
                PendingReconnectAction.COOKIE
            } else {
                PendingReconnectAction.CACHED_RELOGIN
            }
            taskGen = ++reconnectGeneration
            pendingReconnectAction = action
        }

        val delay = reconnectDelayPolicy(firstDisconnectTime)
        if (action == PendingReconnectAction.COOKIE) {
            status(context.getString(R.string.status_waiting_reconnect, delay))
        } else {
            status(context.getString(R.string.status_relogin_with_cached))
        }
        val exec = synchronized(connectionLock) { currentExecutorLocked() }
        if (exec == null) {
            synchronized(reconnectTaskLock) {
                if (taskGen == reconnectGeneration) {
                    reconnectInFlight = false
                    pendingReconnectAction = PendingReconnectAction.NONE
                }
            }
            return
        }
        val task = try {
            exec.schedule({
                performPendingReconnectAttempt(action, taskGen, connectionGen)
            }, delay, TimeUnit.SECONDS)
        } catch (_: RuntimeException) {
            synchronized(reconnectTaskLock) {
                if (taskGen == reconnectGeneration) {
                    reconnectInFlight = false
                    pendingReconnectAction = PendingReconnectAction.NONE
                }
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

    fun forceReconnect() {
        val oldTransport: StompTransport?
        val generation: Long
        val currentExecutor: ScheduledExecutorService
        synchronized(connectionLock) {
            if (stopped || lifecycle == ConnectionLifecycle.STOPPED) return
            lifecycle = ConnectionLifecycle.DISCONNECTED
            connected = false
            connecting = false
            generation = ++connectionGeneration
            remoteApplyGeneration.incrementAndGet()
            oldTransport = stompClient
            stompClient = null
            currentExecutor = currentExecutorLocked() ?: return
        }
        cancelReconnectTasks()
        oldTransport?.close()
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

    fun reconnectAfterUserPresent() {
        val exec = synchronized(connectionLock) { currentExecutorLocked() } ?: return
        exec.execute {
            val action: PendingReconnectAction
            val taskGen: Long
            val connectionGen: Long
            synchronized(connectionLock) {
                if (stopped || connected || connecting || firstDisconnectTime == 0L) return@execute
                connectionGen = connectionGeneration
            }
            synchronized(reconnectTaskLock) {
                action = pendingReconnectAction
                if (action == PendingReconnectAction.NONE) return@execute
                reconnectTask?.cancel(false)
                taskGen = ++reconnectGeneration
            }
            val task = exec.schedule({
                performPendingReconnectAttempt(action, taskGen, connectionGen)
            }, userPresentReconnectDelaySeconds, TimeUnit.SECONDS)
            synchronized(reconnectTaskLock) {
                if (!stopped && taskGen == reconnectGeneration) reconnectTask = task else task.cancel(false)
            }
        }
    }

    private fun sendLocalTextInternal(text: String, source: String) {
        if (text.isBlank()) return
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
        if (!isWithinLimits(text, context.getString(R.string.direction_outbound))) return
        val generation = synchronized(connectionLock) { connectionGeneration }
        val hash = HashUtil.fnv1a64(text)
        synchronized(stateLock) {
            if (previousHash == hash) return
        }

        val payload = try {
            if (config.cipherEnabled) {
                JsonUtil.encryptedPayload(CryptoManager.encrypt(text, config.hashedPasswordBase64))
            } else text
        } catch (error: Exception) {
            status(context.getString(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
            return
        }
        val body = JsonUtil.clipMessage(payload, "text")
        if (body.toByteArray(Charsets.UTF_8).size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
            status(context.getString(R.string.status_encoded_too_large))
            return
        }
        try {
            stompClient?.send("/app/cliptext", body) ?: return
        } catch (error: Exception) {
            status(context.getString(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
            return
        }
        synchronized(stateLock) {
            if (isCurrentGeneration(generation)) previousHash = hash
        }
        status(context.getString(R.string.status_connected_broadcasting))
    }

    private fun isWithinLimits(text: String, direction: String): Boolean {
        val businessLimit = minOf(config.maxSizeBytes, config.localMaxClipboardBytes)
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        val ok = businessLimit >= ClipConfig.MIN_CLIPBOARD_BYTES &&
            businessLimit <= ClipConfig.MAX_CLIPBOARD_BYTES && bytes <= businessLimit
        if (!ok) status(context.getString(R.string.status_clipboard_too_large, direction, bytes))
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
            pendingReconnectAction = PendingReconnectAction.NONE
            reconnectInFlight = false
            reconnectAttempts = 0
            ++reconnectGeneration
        }
    }

    private fun status(message: String) {
        callbacks.onStatus(message)
    }

    private inner class GenerationListener(private val generation: Long) : StompClient.Listener {
        override fun onConnected() = handleConnected(generation)
        override fun onMessage(body: String) = handleMessage(generation, body)
        override fun onClosed(reason: String) = handleClosed(generation, reason)
        override fun onError(error: Throwable) = handleError(generation, error)
        override fun onSessionExpired(error: SessionExpiredException) = handleSessionExpired(generation, error)
    }

    companion object {
        private const val COOKIE_RECONNECT_ATTEMPTS = 2
        private const val USER_PRESENT_RECONNECT_DELAY_SECONDS = 3L
    }
}
