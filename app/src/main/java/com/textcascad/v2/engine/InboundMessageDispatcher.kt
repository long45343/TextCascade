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

package com.textcascad.v2.engine

import android.os.Handler
import com.textcascad.v2.CryptoManager
import com.textcascad.v2.EncryptedPayload
import com.textcascad.v2.Protocol
import com.textcascad.v2.R
import com.textcascad.v2.StringProvider
import org.json.JSONObject

/**
 * 入站消息分发器：从 TextSyncEngine 迁出 welcome/clip/clip_ack/ping/bye/error 的
 * 逐分支处理。不含 socket 读取、不做 connectionGeneration 判断（该检查保留在引擎）。
 *
 * 与出站 codec 相同的边界：状态文案经 stringProvider 格式化后经 callbacks.onStatus
 * 发出；剪贴板写经 clipboard；去重与版本推进经 state；远端应用调度经 postToMain
 * （生产组装为 mainHandler::post）。
 */
class InboundMessageDispatcher(
    private val callbacks: InboundCallbacks,
    private val state: SyncStateStore,
    private val clipboard: ClipboardAccess,
    private val stringProvider: StringProvider,
    private val postToMain: (Runnable) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    /** 生产构造：远端应用经主线程 Handler 调度（与原引擎 mainHandler.post 等价）。 */
    constructor(
        callbacks: InboundCallbacks,
        state: SyncStateStore,
        clipboard: ClipboardAccess,
        stringProvider: StringProvider,
        mainHandler: Handler,
        nowMs: () -> Long
    ) : this(callbacks, state, clipboard, stringProvider, { r -> mainHandler.post(r) }, nowMs)

    /**
     * 引擎侧钩子集，各钩子对应迁移前的 TextSyncEngine 现状行为：
     * - [onStatus]：原 status(message)，转发 Callbacks.onStatus；
     * - [onSendPong]：原 Ping 分支的 runCatching { transport }?.sendText(pong)，
     *   发送失败触发 handleError(generation) 断线重连；
     * - [onWelcomeBackoffReset]：原 handleWelcome 开头的退避重置
     *   （reconnectAttempts=0、maintenanceBackoff=false）；
     * - [onMaintenanceBackoffEnabled]：原 Bye 分支首行 maintenanceBackoff=true；
     * - [onServerVersionAdvanced]：原 advanceServerVersion 前进分支的持久化回调；
     * - [onRemoteTextApplied]：原 applyRemotePayload 主线程成功分支回调；
     * - [derivedKeyBase64]：原 config.derivedKeyBase64（加密载荷解密密钥）；
     * - [isPayloadWithinLimits]：原 outboundCodec.isWithinLimits（本地限额判定）。
     */
    interface InboundCallbacks {
        fun onStatus(message: String)
        fun onSendPong(body: String)
        fun onWelcomeBackoffReset()
        fun onMaintenanceBackoffEnabled()
        fun onServerVersionAdvanced(version: Long)
        fun onRemoteTextApplied(text: String)
        fun derivedKeyBase64(): String
        fun isPayloadWithinLimits(textBytes: ByteArray): Boolean
    }

    /** 按 ServerMessage 类型分发；unknown 与现状一致静默忽略。 */
    fun dispatch(message: Protocol.ServerMessage) {
        when (message) {
            is Protocol.ServerMessage.Welcome -> handleWelcome(message)
            is Protocol.ServerMessage.Clip -> handleServerClip(message)
            is Protocol.ServerMessage.ClipAck -> advanceServerVersion(message.version)
            is Protocol.ServerMessage.Ping -> callbacks.onSendPong(
                Protocol.pongMessage(Protocol.utcNowString(nowMs()))
            )
            is Protocol.ServerMessage.Bye -> handleServerBye(message)
            is Protocol.ServerMessage.Error -> handleServerError(message)
            Protocol.ServerMessage.Unknown -> Unit
        }
    }

    fun handleWelcome(message: Protocol.ServerMessage.Welcome) {
        // welcome 后重置退避
        callbacks.onWelcomeBackoffReset()
        val latest = message.latest ?: return
        val shouldApply = state.shouldApplyRemote(latest.version, latest.hashHex)
        advanceServerVersion(latest.version)
        if (shouldApply) {
            applyRemotePayload(latest.payload, latest.encrypted, latest.hashHex)
        }
    }

    fun handleServerClip(message: Protocol.ServerMessage.Clip) {
        val shouldApply = state.shouldApplyRemote(message.version, message.hashHex)
        advanceServerVersion(message.version)
        if (shouldApply) {
            applyRemotePayload(message.payload, message.encrypted, message.hashHex)
        }
    }

    fun handleServerBye(message: Protocol.ServerMessage.Bye) {
        // 记录 reason，不影响重连决策；关闭后走温和退避
        callbacks.onMaintenanceBackoffEnabled()
        callbacks.onStatus(stringProvider.get(R.string.status_server_bye, message.reason ?: "unknown"))
    }

    fun handleServerError(message: Protocol.ServerMessage.Error) {
        when (message.code) {
            "invalid_message" -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "text_too_large" -> callbacks.onStatus(stringProvider.get(R.string.status_text_too_large_discarded))
            "empty_text" -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "rate_limited" -> {
                state.sendPausedUntilMs = nowMs() + 1000L
                callbacks.onStatus(stringProvider.get(R.string.status_send_rate_limited))
            }
            "hello_timeout" -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "server_busy" -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            "frame_too_large" -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
            else -> callbacks.onStatus(
                stringProvider.get(R.string.status_server_error_code, message.code)
            )
        }
    }

    fun advanceServerVersion(version: Long) {
        val advanced = state.advanceServerVersion(version)
        if (advanced) {
            runCatching { callbacks.onServerVersionAdvanced(version) }
        }
    }

    fun applyRemotePayload(payload: String, encrypted: Boolean, hashHex: String) {
        runCatching {
            var text = payload
            if (encrypted) {
                val parsed = parseEncryptedPayload(payload)
                val keyBase64 = callbacks.derivedKeyBase64()
                check(keyBase64.isNotBlank()) { "No derived key available for decryption" }
                text = CryptoManager.decrypt(parsed, keyBase64)
            }
            val textBytes = text.toByteArray(Charsets.UTF_8)
            if (!callbacks.isPayloadWithinLimits(textBytes)) return
            if (text.isEmpty()) return

            val applyGeneration = state.remoteApplyGeneration()
            state.markRemoteApplied(hashHex)
            postToMain {
                try {
                    clipboard.writeText(text)
                    callbacks.onRemoteTextApplied(text)
                } catch (error: Exception) {
                    state.rollbackRemoteAppliedIfCurrent(hashHex)
                    callbacks.onStatus(
                        stringProvider.get(
                            R.string.status_inbound_error,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }.onFailure {
            callbacks.onStatus(stringProvider.get(R.string.status_inbound_error, it.message ?: it.javaClass.simpleName))
        }
    }

    fun parseEncryptedPayload(payload: String): EncryptedPayload {
        val obj = JSONObject(payload)
        return EncryptedPayload(
            nonce = obj.getString("nonce"),
            ciphertext = obj.getString("ciphertext"),
            tag = obj.getString("tag")
        )
    }
}
