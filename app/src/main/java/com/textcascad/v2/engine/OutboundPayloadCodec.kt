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

import com.textcascad.v2.ClipConfig
import com.textcascad.v2.HashUtil
import com.textcascad.v2.Protocol
import com.textcascad.v2.R
import com.textcascad.v2.StringProvider
import java.util.UUID

/** 出站消息构造结果；失败分支的状态文案已在 codec 内按原顺序发出。 */
sealed class OutboundMessageResult {
    data class Ready(val body: ByteArray, val hashHex: String) : OutboundMessageResult() {
        override fun equals(other: Any?): Boolean =
            other is Ready && other.hashHex == hashHex && other.body.contentEquals(body)

        override fun hashCode(): Int = hashHex.hashCode() * 31 + body.contentHashCode()
    }
    object Suppressed : OutboundMessageResult()
    object RateLimited : OutboundMessageResult()
    object NotConnected : OutboundMessageResult()
    object TooLarge : OutboundMessageResult()
    object EncryptionFailed : OutboundMessageResult()
    object ServerLimitExceeded : OutboundMessageResult()
}

/**
 * 出站载荷编码器：从 TextSyncEngine 迁出 hello/clip 的判定序列与构造逻辑，
 * 不持有 socket；传输判空与发送仍由引擎负责。
 */
class OutboundPayloadCodec(
    private val config: ClipConfig,
    private val nowMs: () -> Long,
    private val clipboard: ClipboardAccess,
    private val state: SyncStateStore,
    private val stringProvider: StringProvider,
    private val isConnected: () -> Boolean,
    private val encrypt: (String) -> String?,
    private val status: (String) -> Unit
) {
    fun buildHelloMessageBytes(): ByteArray {
        var snapshot: Protocol.SnapshotPayload? = null
        runCatching {
            val text = clipboard.readText()
            if (!text.isNullOrBlank()) {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                if (isWithinLimits(textBytes)) {
                    val hashHex = HashUtil.fnv1a64Hex(textBytes)
                    val payload = encrypt(text)
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
        return Protocol.helloMessageBytes(
            clientId = config.clientId,
            clientName = config.clientName,
            lastServerVersion = state.serverVersion,
            snapshot = snapshot
        )
    }

    fun buildClipMessage(text: String, source: String): OutboundMessageResult {
        if (state.consumeSuppressNextLocal()) {
            return OutboundMessageResult.Suppressed
        }
        val now = nowMs()
        if (now < state.sendPausedUntilMs) {
            status(stringProvider.get(R.string.status_send_rate_limited))
            return OutboundMessageResult.RateLimited
        }
        if (!isConnected()) {
            status(stringProvider.get(R.string.status_ignored_not_connected, source))
            return OutboundMessageResult.NotConnected
        }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (!isWithinLimits(textBytes)) {
            return OutboundMessageResult.TooLarge
        }
        val hashHex = HashUtil.fnv1a64Hex(textBytes)
        if (state.isEchoOfLastRemote(hashHex)) {
            return OutboundMessageResult.Suppressed
        }

        val payload = encrypt(text)
        if (payload == null) {
            status(stringProvider.get(R.string.status_encryption_error))
            return OutboundMessageResult.EncryptionFailed
        }
        // 加密扩散后超出服务端 payload 限额：本地丢弃，避免无效传输与 text_too_large 回环
        if (!payloadWithinServerLimit(payload)) {
            status(stringProvider.get(R.string.status_clipboard_too_large, textBytes.size.toLong()))
            return OutboundMessageResult.ServerLimitExceeded
        }
        val body = Protocol.clipMessageBytes(
            id = UUID.randomUUID().toString(),
            payload = payload,
            encrypted = config.cipherEnabled,
            hashHex = hashHex
        )
        if (body.size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
            status(stringProvider.get(R.string.status_encoded_too_large))
            return OutboundMessageResult.TooLarge
        }
        return OutboundMessageResult.Ready(body, hashHex)
    }

    fun isWithinLimits(textBytes: ByteArray): Boolean {
        val businessLimit = minOf(config.maxTextBytes, config.localMaxClipboardBytes)
            .coerceIn(ClipConfig.MIN_CLIPBOARD_BYTES, ClipConfig.MAX_CLIPBOARD_BYTES)
        val bytes = textBytes.size.toLong()
        val ok = bytes in 1..businessLimit
        if (!ok) status(stringProvider.get(R.string.status_clipboard_too_large, bytes))
        return ok
    }

    /**
     * 服务端 ValidatePayloadSize 校验的是 payload 字段本身（加密后 JSON，约为明文 4/3 倍），
     * 不是明文。加密模式下明文 >~ 3/4 maxTextBytes 时会被服务端拒绝：
     * clip 路径触发 text_too_large，hello snapshot 路径触发 invalid_hello（1008 断连循环）。
     */
    private fun payloadWithinServerLimit(payload: String): Boolean {
        return payload.toByteArray(Charsets.UTF_8).size.toLong() <= config.maxTextBytes
    }
}
