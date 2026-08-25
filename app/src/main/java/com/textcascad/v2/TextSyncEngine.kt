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
import com.textcascad.v2.engine.ConnectionEvents
import com.textcascad.v2.engine.ConnectionManager
import com.textcascad.v2.engine.InboundMessageDispatcher
import com.textcascad.v2.engine.OutboundMessageResult
import com.textcascad.v2.engine.OutboundPayloadCodec
import com.textcascad.v2.engine.SyncStateStore
import com.textcascad.v2.engine.TransportFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * v2 同步引擎编排入口：
 * - 连接生命周期/重连调度委托 [ConnectionManager]，消息分发委托 [InboundMessageDispatcher]，
 *   出站编码委托 [OutboundPayloadCodec]，剪贴板经 [ClipboardAccess]。
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
    private val transportFactory: TransportFactory = { url, token, listener, trustAll, pinnedCertSha256, rxTimeoutMs ->
        RawWebSocketClient(url, token, listener, trustAll, pinnedCertSha256, rxTimeoutMs)
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
    private val rateLimitedReloginFloorSeconds: Long = 30L,
    internal val state: SyncStateStore = SyncStateStore(config.userPrefs.lastServerVersion),
    private val outbound: OutboundPayloadCodec? = null,
    private val inbound: InboundMessageDispatcher? = null,
    private val connectionManager: ConnectionManager? = null
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val outboundCodec: OutboundPayloadCodec =
        outbound ?: OutboundPayloadCodec(
            config = config,
            nowMs = nowMs,
            clipboard = clipboard,
            state = state,
            stringProvider = stringProvider,
            isConnected = { connection.isConnected },
            encrypt = { text -> encryptOutbound(text) },
            status = { message -> status(message) }
        )

    private val inboundCallbacks = object : InboundMessageDispatcher.InboundCallbacks {
        override fun onStatus(message: String) {
            callbacks.onStatus(message)
        }

        override fun onSendPong(body: String) {
            val generation = connection.currentGeneration()
            runCatching {
                connection.currentTransport()?.sendText(body)
            }.onFailure { connection.handleError(generation, it) }
        }

        override fun onWelcomeBackoffReset() {
            connection.resetBackoffState()
        }

        override fun onMaintenanceBackoffEnabled() {
            connection.enableMaintenanceBackoff()
        }

        override fun onServerVersionAdvanced(version: Long) {
            callbacks.onServerVersionAdvanced(version)
        }

        override fun onRemoteTextApplied(text: String) {
            callbacks.onRemoteTextApplied(text)
        }

        override fun derivedKeyBase64(): String = config.cryptoMaterial.derivedKeyBase64

        override fun isPayloadWithinLimits(textBytes: ByteArray): Boolean =
            outboundCodec.isWithinLimits(textBytes)
    }

    private val inboundDispatcher: InboundMessageDispatcher =
        inbound ?: InboundMessageDispatcher(
            callbacks = inboundCallbacks,
            state = state,
            clipboard = clipboard,
            stringProvider = stringProvider,
            mainHandler = mainHandler,
            nowMs = nowMs
        )

    private val connectionEvents = object : ConnectionEvents {
        override fun onStatus(message: String) {
            callbacks.onStatus(message)
        }

        override fun onDisconnectedStatus(message: String, subText: String) {
            disconnectedStatus(message, subText)
        }

        override fun onSessionExpired() {
            callbacks.onSessionExpired()
        }

        override fun onCachedReloginRequired(): CachedReloginResult = callbacks.onCachedReloginRequired()

        override fun onInboundText(generation: Long, body: String) {
            handleMessage(generation, body)
        }

        override fun onConnected(generation: Long, transport: SyncTransport?) {
            runCatching { transport?.sendBytes(outboundCodec.buildHelloMessageBytes()) }
                .onFailure { connection.handleError(generation, it) }
        }
    }

    private val connection: ConnectionManager =
        connectionManager ?: ConnectionManager(
            config = config,
            state = state,
            executorFactory = executorFactory,
            transportFactory = transportFactory,
            nowMs = nowMs,
            stringProvider = stringProvider,
            userPresentReconnectDelaySeconds = userPresentReconnectDelaySeconds,
            rateLimitedReloginFloorSeconds = rateLimitedReloginFloorSeconds,
            backoffDelaysNormalSeconds = backoffDelaysNormalSeconds,
            backoffDelaysMaintenanceSeconds = backoffDelaysMaintenanceSeconds,
            events = connectionEvents
        )

    val isConnected: Boolean get() = connection.isConnected
    val isConnecting: Boolean get() = connection.isConnecting
    val isStopped: Boolean get() = connection.isStopped

    internal fun executorForTest(): ScheduledExecutorService? = connection.executorForTest()

    internal fun connectionGenerationForTest(): Long = connection.connectionGenerationForTest()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start() = connection.start()

    fun stop() = connection.stop()

    fun sendLocalText(text: String, source: String) {
        connection.submit { sendLocalTextInternal(text, source) }
    }

    fun forceReconnect() = connection.forceReconnect()

    /** 解锁/回前台后提前重连（仅处于断线等待时生效）。 */
    fun reconnectAfterUserPresent() = connection.reconnectAfterUserPresent()

    // ------------------------------------------------------------------
    // 传输回调
    // ------------------------------------------------------------------

    override fun onOpen() {
        connection.handleOpen(connection.currentGeneration())
    }

    override fun onText(text: String) {
        handleMessage(connection.currentGeneration(), text)
    }

    override fun onClosed(code: Int, reason: String) {
        connection.handleClosed(connection.currentGeneration(), code, reason)
    }

    override fun onError(error: Throwable) {
        connection.handleError(connection.currentGeneration(), error)
    }

    override fun onSessionExpired(error: SessionExpiredException) {
        connection.handleSessionExpired(connection.currentGeneration())
    }

    // ------------------------------------------------------------------
    // 入站
    // ------------------------------------------------------------------

    private fun handleMessage(generation: Long, body: String) {
        connection.submit task@{
            if (!connection.isCurrentGeneration(generation)) return@task
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
            inboundDispatcher.dispatch(message)
        }
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    private fun encryptOutbound(text: String): String? {
        return try {
            if (config.cryptoMaterial.cipherEnabled) {
                CryptoManager.encryptedPayloadJson(
                    CryptoManager.encrypt(text, config.cryptoMaterial.derivedKeyBase64)
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
        when (val result = outboundCodec.buildClipMessage(text, source)) {
            is OutboundMessageResult.Ready -> {
                val currentTransport = connection.currentTransport()
                if (currentTransport == null) {
                    status(stringProvider.get(R.string.status_ignored_not_connected, source))
                    return
                }
                try {
                    currentTransport.sendBytes(result.body)
                } catch (error: Exception) {
                    status(stringProvider.get(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
                    return
                }
                state.setLastSentHashHex(result.hashHex)
                status(stringProvider.get(R.string.status_connected_broadcasting))
            }
            else -> Unit
        }
    }

    internal fun backoffDelaySeconds(attempt: Int, maintenance: Boolean): Long =
        connection.backoffDelaySeconds(attempt, maintenance)

    private fun status(message: String) {
        callbacks.onStatus(message)
    }

    companion object {
        private const val USER_PRESENT_RECONNECT_DELAY_SECONDS = 3L
    }
}
