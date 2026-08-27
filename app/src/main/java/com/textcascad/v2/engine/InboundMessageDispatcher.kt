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

import com.textcascad.v2.Protocol
import com.textcascad.v2.R

/**
 * S2：入站 Dispatcher 是纯判定器。
 *
 * `dispatch()` 只读取共享同步状态并产出 [InboundCommands]；不发送网络帧、
 * 不切主线程、不格式化最终字符串、不直接调用执行回调。允许读取去重状态，
 * 但加密/限额上下文由构造参数显式注入，副作用由 `TextSyncEngine` 执行。
 */
sealed class InboundCommand {
    data class Pong(val body: String) : InboundCommand()
    object ResetBackoff : InboundCommand()
    object EnableMaintenanceBackoff : InboundCommand()

    /** 版本已推进；引擎据此触发一次持久化回调。 */
    data class AdvanceVersion(val version: Long) : InboundCommand()
    data class ApplyClipboard(
        val payload: String,
        val encrypted: Boolean,
        val hashHex: String,
        val parsedPayload: Any? = null
    ) : InboundCommand()
    data class Status(val resourceId: Int, val args: List<Any> = emptyList()) : InboundCommand()
}

data class InboundCommands(val commands: List<InboundCommand>) {
    companion object {
        val NONE = InboundCommands(emptyList())
    }
}

class InboundMessageDispatcher(
    val state: SyncStateStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 生产可注入解密器；null 时引擎负责解密与错误上报。 */
    private val decrypt: ((String) -> Result<String>)? = null,
    private val payloadLimitBytes: Long = Long.MAX_VALUE
) {
    fun dispatch(message: Protocol.ServerMessage): InboundCommands = when (message) {
        is Protocol.ServerMessage.Welcome -> handleWelcome(message)
        is Protocol.ServerMessage.Clip -> handleServerClip(message)
        is Protocol.ServerMessage.ClipAck -> advanceServerVersion(message.version)
        is Protocol.ServerMessage.Ping -> InboundCommands(
            listOf(InboundCommand.Pong(Protocol.pongMessage(Protocol.utcNowString(nowMs()))))
        )
        is Protocol.ServerMessage.Bye -> handleServerBye(message)
        is Protocol.ServerMessage.Error -> handleServerError(message)
        Protocol.ServerMessage.Unknown -> InboundCommands.NONE
    }

    fun handleWelcome(message: Protocol.ServerMessage.Welcome): InboundCommands {
        val commands = mutableListOf<InboundCommand>(InboundCommand.ResetBackoff)
        val latest = message.latest ?: return InboundCommands(commands)
        commands.addAll(clipCommands(latest.payload, latest.encrypted, latest.hashHex, latest.version))
        return InboundCommands(commands)
    }

    fun handleServerClip(message: Protocol.ServerMessage.Clip): InboundCommands =
        InboundCommands(clipCommands(message.payload, message.encrypted, message.hashHex, message.version))

    fun advanceServerVersion(version: Long): InboundCommands =
        if (state.advanceServerVersion(version)) {
            InboundCommands(listOf(InboundCommand.AdvanceVersion(version)))
        } else {
            InboundCommands.NONE
        }

    fun handleServerBye(message: Protocol.ServerMessage.Bye): InboundCommands = InboundCommands(
        listOf(
            InboundCommand.EnableMaintenanceBackoff,
            InboundCommand.Status(R.string.status_server_bye, listOf(message.reason ?: "unknown"))
        )
    )

    fun handleServerError(message: Protocol.ServerMessage.Error): InboundCommands = when (message.code) {
        "text_too_large" -> statusOnly(R.string.status_text_too_large_discarded)
        "rate_limited" -> {
            state.sendPausedUntilMs = nowMs() + 1000L
            statusOnly(R.string.status_send_rate_limited)
        }
        else -> statusOnly(R.string.status_server_error_code, listOf(message.code))
    }

    /**
     * 兼容聚焦测试的纯入口：给定已解密的明文，判断能否产生应用命令。
     * 引擎在写入失败时负责回滚 [SyncStateStore] 的 remote applied hash。
     */
    fun prepareRemoteApply(text: String, hashHex: String): InboundCommands =
        if (text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size.toLong() <= payloadLimitBytes) {
            state.markRemoteApplied(hashHex)
            InboundCommands(listOf(InboundCommand.ApplyClipboard(text, encrypted = false, hashHex = hashHex)))
        } else {
            InboundCommands.NONE
        }

    private fun clipCommands(payload: String, encrypted: Boolean, hashHex: String, version: Long): List<InboundCommand> {
        val shouldApply = state.shouldApplyRemote(version, hashHex) &&
            !state.isEchoOfRecentRemote(hashHex)
        val advanced = state.advanceServerVersion(version)
        val commands = mutableListOf<InboundCommand>()
        if (advanced) commands.add(InboundCommand.AdvanceVersion(version))
        if (!shouldApply) return commands

        if (!encrypted) {
            val textBytes = payload.toByteArray(Charsets.UTF_8)
            if (textBytes.isEmpty() || textBytes.size.toLong() > payloadLimitBytes) return commands
            state.markRemoteApplied(hashHex)
            commands.add(InboundCommand.ApplyClipboard(payload, false, hashHex))
            return commands
        }

        val parser = decrypt
        if (parser == null) {
            commands.add(InboundCommand.Status(R.string.status_inbound_error, listOf("decryptor unavailable")))
            return commands
        }
        val decrypted = parser(payload).getOrNull()
        if (decrypted == null) {
            commands.add(
                InboundCommand.Status(R.string.status_inbound_error, listOf("invalid encrypted payload"))
            )
            return commands
        }
        val textBytes = decrypted.toByteArray(Charsets.UTF_8)
        if (textBytes.isEmpty() || textBytes.size.toLong() > payloadLimitBytes) return commands
        state.markRemoteApplied(hashHex)
        commands.add(InboundCommand.ApplyClipboard(decrypted, false, hashHex))
        return commands
    }

    private fun statusOnly(resourceId: Int, args: List<Any> = emptyList()) =
        InboundCommands(listOf(InboundCommand.Status(resourceId, args)))
}






