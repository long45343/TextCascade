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
    // R13: 测试接缝 - 允许注入自定义 StompClient 工厂
    private val stompClientFactory: (String, String, StompClient.Listener, Boolean) -> StompClient =
        { url, cookie, listener, trustAll -> StompClient(url, cookie, listener, trustAll) }
) : StompClient.Listener {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRemoteTextApplied(text: String)
        // R2: 会话失效回调
        fun onSessionExpired() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var stompClient: StompClient? = null
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
    // R9: 状态字段同步锁
    private val stateLock = Any()
    private var previousHash: Long? = null
    private var suppressNextLocal = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        connecting = false
        // R3: 复位 single-flight 标志
        reconnectInFlight = false
        reconnectTask?.cancel(false)
        reconnectTask = null
        stompClient?.close()
        stompClient = null
        executor.shutdownNow()
    }

    fun sendLocalText(text: String, source: String) {
        executor.execute {
            sendLocalTextInternal(text, source)
        }
    }

    override fun onConnected() {
        connected = true
        connecting = false
        firstDisconnectTime = 0L
        reconnectTask?.cancel(false)
        reconnectTask = null
        status(context.getString(R.string.status_connected))
        stompClient?.subscribe("/user/queue/cliptext")
    }

    override fun onMessage(body: String) {
        executor.execute {
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
                // R9: 线程安全读取 previousHash
                synchronized(stateLock) {
                    if (previousHash == hash) {
                        return@runCatching
                    }
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
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TextCascade", text))
                    callbacks.onRemoteTextApplied(text)
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
        reconnectTask?.cancel(false)
        reconnectTask = null
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

    // R3: single-flight 重连
    private fun scheduleReconnect() {
        if (stopped) {
            return
        }
        if (reconnectInFlight) {
            return
        }
        reconnectInFlight = true
        val delay = reconnectDelaySeconds()
        reconnectTask?.cancel(false)
        status(context.getString(R.string.status_connecting))
        reconnectTask = executor.schedule({
            reconnectInFlight = false
            connect()
        }, delay, TimeUnit.SECONDS)
    }

    private fun reconnectDelaySeconds(): Long {
        if (firstDisconnectTime == 0L) return 10L
        val elapsed = (System.currentTimeMillis() - firstDisconnectTime) / 1000
        return when {
            elapsed < 600 -> 10L
            elapsed < 1800 -> 60L
            elapsed < 3600 -> 180L
            else -> 300L
        }
    }

    fun forceReconnect() {
        firstDisconnectTime = 0L
        stopped = false
        // R3: 复位 single-flight 标志
        reconnectInFlight = false
        reconnectTask?.cancel(false)
        reconnectTask = null
        executor.execute { connect(force = true) }
    }

    fun reconnectAfterUserPresent() {
        executor.execute {
            if (stopped || connected || firstDisconnectTime == 0L) {
                return@execute
            }
            reconnectTask?.cancel(false)
            reconnectTask = executor.schedule({
                firstDisconnectTime = 0L
                connect(force = true)
            }, USER_PRESENT_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
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

        var payload = text
        if (config.cipherEnabled) {
            payload = JsonUtil.encryptedPayload(CryptoManager.encrypt(text, config.hashedPasswordBase64))
        }
        // R10: hash 在发送成功后才提交
        try {
            stompClient?.send(
                destination = "/app/cliptext",
                body = JsonUtil.clipMessage(payload, "text")
            ) ?: return
        } catch (e: Exception) {
            status(context.getString(R.string.status_websocket_error, e.message))
            // R10: 发送失败不更新 previousHash，下次相同内容可重试
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
        private const val USER_PRESENT_RECONNECT_DELAY_SECONDS = 3L
    }
}
