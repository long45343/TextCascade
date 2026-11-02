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
import java.util.UUID

/** 纯出站结果：`Ready` 之外仅携带判定原因，不产生状态回调。 */
sealed class OutboundMessageResult {
    data class Ready(
        val body: ByteArray,
        val hashHex: String,
        val resourceId: Int = com.textcascad.v2.R.string.status_connected_broadcasting,
        val resourceArgs: List<Any> = emptyList()
    ) : OutboundMessageResult() {
        override fun equals(other: Any?): Boolean =
            other is Ready && other.hashHex == hashHex && other.body.contentEquals(body)

        override fun hashCode(): Int = hashHex.hashCode() * 31 + body.contentHashCode()
    }
    object Suppressed : OutboundMessageResult()
    object RateLimited : OutboundMessageResult()
    object NotConnected : OutboundMessageResult()
    object TooLargePlain : OutboundMessageResult()
    object EncryptionFailed : OutboundMessageResult()
    object TooLargeEncrypted : OutboundMessageResult()
}

/**
 * 出站载荷编码器：hello/clip 的判定序列与构造逻辑。
 *
 * S3：只依赖配置、同步状态、剪贴板、时钟与加密函数；返回纯结果，连接查询和
 * 最终状态文案由引擎处理。允许读写确定性的回显抑制/限流/最近哈希状态。
 */
class OutboundPayloadCodec(
    private val config: ClipConfig,
    private val nowMs: () -> Long,
    private val clipboard: ClipboardAccess,
    private val state: SyncStateStore,
    private val encrypt: (String) -> String?
) {
    fun buildHelloMessageBytes(): ByteArray {
        var snapshot: Protocol.SnapshotPayload? = null
        runCatching {
            val text = clipboard.readText()
            if (!text.isNullOrBlank()) {
                // 只量长度/算哈希，不为此生成整段 UTF-8 字节数组
                if (!isWithinLimits(text)) return@runCatching
                val hashHex = HashUtil.fnv1a64HexOfUtf8(text)
                val payload = encrypt(text)
                // 服务端对 snapshot.payload 本身限额（加密后含 base64 扩散）；
                // 超限时携带会被判 invalid_hello 并以 1008 断连，因此放弃快照。
                if (payload != null && payloadWithinServerLimit(payload)) {
                    snapshot = Protocol.SnapshotPayload(
                        payload = payload,
                        encrypted = config.cryptoMaterial.cipherEnabled,
                        hashHex = hashHex,
                        localModifiedAtUtc = Protocol.utcNowString(nowMs())
                    )
                }
            }
        }
        return Protocol.helloMessageBytes(
            clientId = config.session.clientId,
            clientName = config.session.clientName,
            lastServerVersion = state.serverVersion,
            snapshot = snapshot
        )
    }

    /**
     * 规范 9.2 判定顺序：
     * rate limit → connection（引擎层）→ local limit → echo → encryption
     * → server payload limit → transport limit。只产生 [OutboundMessageResult]。
     * 回显抑制走 recentRemoteHashes 池（hash 写前登记，命中即已作为 String 存在的 hash，
     * 不会为指纹单独分配明文副本）。
     */
    fun buildClipMessage(text: String, source: String): OutboundMessageResult {
        if (nowMs() < state.sendPausedUntilMs) {
            return OutboundMessageResult.RateLimited
        }

        if (!isWithinLimits(text)) {
            return OutboundMessageResult.TooLargePlain
        }
        val hashHex = HashUtil.fnv1a64HexOfUtf8(text)
        if (state.isEchoOfRecentRemote(hashHex)) {
            return OutboundMessageResult.Suppressed
        }

        val payload = encrypt(text)
            ?: return OutboundMessageResult.EncryptionFailed
        if (!payloadWithinServerLimit(payload)) {
            return OutboundMessageResult.TooLargeEncrypted
        }

        val body = Protocol.clipMessageBytes(
            id = UUID.randomUUID().toString(),
            payload = payload,
            encrypted = config.cryptoMaterial.cipherEnabled,
            hashHex = hashHex
        )
        if (body.size.toLong() > ClipConfig.MAX_TRANSPORT_BYTES) {
            return OutboundMessageResult.TooLargeEncrypted
        }
        return OutboundMessageResult.Ready(body, hashHex)
    }

    /** 明文长度判定；按 UTF-8 字节计，不生成字节数组。 */
    fun isWithinLimits(text: CharSequence): Boolean {
        val businessLimit = businessLimitBytes()
        val bytes = HashUtil.utf8Length(text).toLong()
        return bytes in 1..businessLimit
    }

    private fun businessLimitBytes(): Long =
        minOf(config.userPrefs.maxTextBytes, config.userPrefs.localMaxClipboardBytes)
            .coerceIn(ClipConfig.MIN_CLIPBOARD_BYTES, ClipConfig.MAX_CLIPBOARD_BYTES)

    /**
     * 服务端 ValidatePayloadSize 校验的是 payload 字段本身（加密后 JSON，约为明文 4/3 倍），
     * 不是明文。payload 为 Base64/转义后的 ASCII 或近似 ASCII 文本，只量长度不编码。
     */
    private fun payloadWithinServerLimit(payload: String): Boolean {
        return HashUtil.utf8Length(payload).toLong() <= config.userPrefs.maxTextBytes
    }
}
