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
import com.textcascad.v2.engine.ConnectionCloseInfo
import com.textcascad.v2.engine.ConnectionManager
import com.textcascad.v2.engine.InboundCommand
import com.textcascad.v2.engine.InboundCommands
import com.textcascad.v2.engine.InboundMessageDispatcher
import com.textcascad.v2.engine.OutboundMessageResult
import com.textcascad.v2.engine.OutboundPayloadCodec
import com.textcascad.v2.engine.SyncStateStore
import com.textcascad.v2.engine.TransportFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * v2 同步引擎编排入口：连接、入站副作用和出站发送统一在这里执行。
 *
 * S1/S2/S3/S4：ConnectionManager 只回调少量显式事件；Dispatcher 只产出命令；
 * Codec 只产出纯结果。本类负责 generation 过滤、网络帧发送、主线程剪贴板写入、
 * 回滚与最终状态通知。
 */
class TextSyncEngine(
    private val context: Context,
    private val config: ClipConfig,
    private val callbacks: Callbacks,
    private val stringProvider: StringProvider = object : StringProvider {
        override fun get(id: Int, vararg args: Any): String =
            if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    },
    private val disconnectedStatus: (message: String, subText: String) -> Unit =
        { message, _ -> callbacks.onStatus(message) },
    private val transportFactory: TransportFactory = { url, token, listener, trustAll, pinnedCertSha256, rxTimeoutMs ->
        OkHttpTransport(url, token, listener, trustAll, pinnedCertSha256, rxTimeoutMs)
    },
    internal val executorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "textcascade-sync").apply { isDaemon = true }
        }
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val clipboard: ClipboardAccess = AndroidClipboardAccess(context),
    private val backoffDelaysNormalSeconds: List<Long> = listOf(1L, 2L, 5L, 10L, 30L, 60L),
    private val backoffDelaysMaintenanceSeconds: List<Long> = listOf(1L, 2L, 5L, 10L),
    private val rateLimitedReloginFloorSeconds: Long = 30L,
    internal val state: SyncStateStore = SyncStateStore(config.userPrefs.lastServerVersion),
    private val outbound: OutboundPayloadCodec? = null,
    private val inbound: InboundMessageDispatcher? = null,
    private val connectionManager: ConnectionManager? = null
) : SyncTransport.Listener {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRemoteTextApplied(text: String)

        /** 会话失效（401/token 过期重登无凭据）：停止重连，交上层处理。 */
        fun onSessionExpired() {}

        /** 引擎在 token 预判过期时同步调用；阻塞于引擎线程。 */
        fun onCachedReloginRequired(): AuthResult = AuthResult.NoCredentials

        /** lastServerVersion 前进时持久化（单调递增）。 */
        fun onServerVersionAdvanced(version: Long) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 未连接/发送失败时暂存的本地文本（仅最新一条）。仅在单线程 textcascade-sync
     * executor 上读写（sendLocalText/handleMessage/重发均经 connection.submit），无需加锁。
     */
    private var pendingLocalText: String? = null

    private val outboundCodec: OutboundPayloadCodec =
        outbound ?: OutboundPayloadCodec(
            config = config,
            nowMs = nowMs,
            clipboard = clipboard,
            state = state,
            encrypt = ::encryptOutbound
        )

    private val inboundDispatcher: InboundMessageDispatcher =
        inbound ?: InboundMessageDispatcher(
            state = state,
            nowMs = nowMs,
            decrypt = { payload ->
                runCatching {
                    val encrypted = parseEncryptedPayload(payload)
                    val keyBase64 = config.cryptoMaterial.derivedKeyBase64
                    check(keyBase64.isNotBlank()) { "No derived key available for decryption" }
                    CryptoManager.decrypt(encrypted, keyBase64)
                }
            },
            payloadLimitBytes = minOf(
                config.userPrefs.maxTextBytes,
                config.userPrefs.localMaxClipboardBytes
            ).coerceIn(ClipConfig.MIN_CLIPBOARD_BYTES, ClipConfig.MAX_CLIPBOARD_BYTES)
        )

    /** cached relogin 不是普通连接事件；显式函数保持认证依赖单向进入连接层。 */
    private fun cachedRelogin(): AuthResult = callbacks.onCachedReloginRequired()

    /**
     * S1：断连明细的结构化文案映射。普通状态与断连通知统一由引擎生成后
     * 交给上层回调，ConnectionManager 不再回传字符串。
     */
    private fun onConnectionClosed(generation: Long, closeInfo: ConnectionCloseInfo) {
        when (closeInfo) {
            is ConnectionCloseInfo.Closed -> {
                val detail = "close ${closeInfo.code} ${closeInfo.reason}"
                disconnectedStatus(
                    stringProvider.get(R.string.status_disconnected, detail),
                    detail
                )
            }
            is ConnectionCloseInfo.Error ->
                status(
                    stringProvider.get(
                        R.string.status_websocket_error,
                        closeInfo.error.message ?: closeInfo.error.javaClass.simpleName
                    )
                )
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
            rateLimitedReloginFloorSeconds = rateLimitedReloginFloorSeconds,
            backoffDelaysNormalSeconds = backoffDelaysNormalSeconds,
            backoffDelaysMaintenanceSeconds = backoffDelaysMaintenanceSeconds,
            onCachedReloginRequired = ::cachedRelogin,
            onStatus = ::status,
            onConnected = { generation, transport ->
                runCatching { transport?.sendBytes(outboundCodec.buildHelloMessageBytes()) }
                    .onFailure { connection.handleError(generation, it) }
            },
            onInboundText = ::handleMessage,
            onClosed = ::onConnectionClosed,
            onSessionExpired = { callbacks.onSessionExpired() }
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

    /** 恢复活动信号（亮屏/解锁/Doze 退出任一）：无条件重建连接。 */
    fun onDeviceAwake() = connection.onDeviceAwake()

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
    // 入站命令执行
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
            val commands = inboundDispatcher.dispatch(message)
            executeInbound(generation, commands)
            // welcome 到达（重连成功）后结算 pending：远端已有更新则放弃，否则补发
            if (message is Protocol.ServerMessage.Welcome) {
                val appliedRemote = commands.commands.any { it is InboundCommand.ApplyClipboard }
                val pending = pendingLocalText
                when {
                    appliedRemote -> pendingLocalText = null
                    pending != null -> {
                        pendingLocalText = null
                        sendLocalTextInternal(pending, PENDING_RESEND_SOURCE)
                    }
                }
            }
        }
    }

    private fun executeInbound(generation: Long, commands: InboundCommands) {
        for (command in commands.commands) {
            when (command) {
                is InboundCommand.Pong -> sendPong(generation, command.body)
                InboundCommand.ResetBackoff -> connection.resetBackoffState()
                InboundCommand.EnableMaintenanceBackoff -> connection.enableMaintenanceBackoff()
                is InboundCommand.AdvanceVersion ->
                    runCatching { callbacks.onServerVersionAdvanced(command.version) }
                is InboundCommand.ApplyClipboard -> applyClipboardOnMain(command)
                is InboundCommand.Status -> status(stringProvider.get(command.resourceId, *command.args.toTypedArray()))
            }
        }
    }

    private fun sendPong(generation: Long, body: String) {
        runCatching {
            connection.currentTransport()?.sendText(body)
        }.onFailure { connection.handleError(generation, it) }
    }

    /**
     * 先记录 hash 再切主线程写剪贴板；主线程写失败时在当前线程回滚并报告入站错误。
     */
    private fun applyClipboardOnMain(command: InboundCommand.ApplyClipboard) {
        mainHandler.post {
            try {
                clipboard.writeText(command.payload)
                callbacks.onRemoteTextApplied(command.payload)
            } catch (error: Exception) {
                state.rollbackRemoteAppliedIfCurrent(command.hashHex)
                status(
                    stringProvider.get(
                        R.string.status_inbound_error,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
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

    private fun parseEncryptedPayload(payload: String): EncryptedPayload {
        val obj = org.json.JSONObject(payload)
        return EncryptedPayload(
            nonce = obj.getString("nonce"),
            ciphertext = obj.getString("ciphertext"),
            tag = obj.getString("tag")
        )
    }

    private fun sendLocalTextInternal(text: String, source: String) {
        if (text.isEmpty()) return
        when (val result = outboundCodec.buildClipMessage(text, source)) {
            is OutboundMessageResult.Ready -> {
                val currentTransport = connection.currentTransport()
                if (currentTransport == null) {
                    status(stringProvider.get(R.string.status_ignored_not_connected, source))
                    stashPendingAndReconnect(text)
                    return
                }
                try {
                    currentTransport.sendBytes(result.body)
                } catch (error: Exception) {
                    status(stringProvider.get(R.string.status_websocket_error, error.message ?: error.javaClass.simpleName))
                    stashPendingAndReconnect(text)
                    return
                }
                state.setLastSentHashHex(result.hashHex)
                if (pendingLocalText == text) pendingLocalText = null
                status(stringProvider.get(R.string.status_connected_broadcasting))
            }
            OutboundMessageResult.RateLimited ->
                status(stringProvider.get(R.string.status_send_rate_limited))
            OutboundMessageResult.NotConnected -> {
                status(stringProvider.get(R.string.status_ignored_not_connected, source))
                stashPendingAndReconnect(text)
            }
            OutboundMessageResult.TooLargePlain,
            OutboundMessageResult.TooLargeEncrypted ->
                status(
                    stringProvider.get(
                        R.string.status_clipboard_too_large,
                        text.toByteArray(Charsets.UTF_8).size.toLong()
                    )
                )
            OutboundMessageResult.EncryptionFailed ->
                status(stringProvider.get(R.string.status_encryption_error))
            OutboundMessageResult.Suppressed -> Unit
        }
    }

    /**
     * 未连接或发送失败时暂存文本（仅最新一条，新复制覆盖）并强制重连；
     * 重连成功的 welcome 后由 [handleMessage] 补发。超限/抑制/限流内容不暂存。
     */
    private fun stashPendingAndReconnect(text: String) {
        pendingLocalText = text
        connection.forceReconnect()
    }

    internal fun backoffDelaySeconds(attempt: Int, maintenance: Boolean): Long =
        connection.backoffDelaySeconds(attempt, maintenance)

    private fun status(message: String) {
        callbacks.onStatus(message)
    }

    companion object {
        private const val PENDING_RESEND_SOURCE = "pending_resend"
    }
}





