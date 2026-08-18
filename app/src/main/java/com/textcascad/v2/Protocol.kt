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

import org.json.JSONObject
import java.time.Instant

/**
 * TextCascade v1 协议消息模型（契约来源：specs/spec.md）。
 *
 * 上行：hello / clip / pong（紧凑 JSON 序列化，仅契约字段）。
 * 下行：welcome / clip / clip_ack / ping / bye / error（容错解析）。
 * 所有时间均为 UTC RFC3339，以 Z 结尾。
 */
object Protocol {
    /** 客户端支持的协议版本；服务端 protocolVersion 高于该值时提示升级。 */
    const val SUPPORTED_PROTOCOL_VERSION = 1

    /** WebSocket 子协议。 */
    const val SUBPROTOCOL = "textcascade.v1"

    /** 同步 WebSocket 路径。 */
    const val SYNC_PATH = "/api/v1/sync"

    /** 登录路径。 */
    const val LOGIN_PATH = "/api/v1/login"

    // ------------------------------------------------------------------
    // 时间工具：RFC3339 UTC（Z 结尾）
    // ------------------------------------------------------------------

    /**
     * 客户端上行时间戳格式：秒级 yyyy-MM-ddTHH:mm:ssZ。
     * 服务端 TryGetUtcDateTime 仅接受 "O"（7 位小数）或秒级 Z 结尾两种格式；
     * Java Instant.toString() 的可变小数位（.1Z/.12Z/.123Z）会被判 invalid_message，
     * 导致 pong 被拒、心跳超时断连。截断到秒后 toString 恰好输出无小数位形式。
     */
    fun utcNowString(nowMs: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(nowMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** 容错解析服务端时间：ISO-8601（含偏移形式）/ epoch 毫秒数字符串；失败返回 null。 */
    fun parseUtcToEpochMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                value.toLongOrNull()
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON 字符串转义（手工序列化保证字节级确定性，紧凑无空格）
    // ------------------------------------------------------------------

    fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        val hex = ch.code.toString(16)
                        repeat(4 - hex.length) { sb.append('0') }
                        sb.append(hex)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 上行消息
    // ------------------------------------------------------------------

    data class SnapshotPayload(
        val payload: String,
        val encrypted: Boolean,
        val hashHex: String,
        val localModifiedAtUtc: String
    )

    fun helloMessage(
        clientId: String,
        clientName: String,
        lastServerVersion: Long,
        snapshot: SnapshotPayload?
    ): String {
        val sb = StringBuilder(128)
        sb.append("{\"type\":\"hello\",\"clientId\":\"").append(jsonEscape(clientId))
            .append("\",\"clientName\":\"").append(jsonEscape(clientName))
            .append("\",\"lastServerVersion\":").append(lastServerVersion)
        if (snapshot != null) {
            sb.append(",\"snapshot\":{\"payload\":\"").append(jsonEscape(snapshot.payload))
                .append("\",\"encrypted\":").append(snapshot.encrypted)
                .append(",\"hash\":\"").append(jsonEscape(snapshot.hashHex))
                .append("\",\"localModifiedAtUtc\":\"").append(jsonEscape(snapshot.localModifiedAtUtc))
                .append("\"}")
        }
        sb.append("}")
        return sb.toString()
    }

    fun clipMessage(id: String, payload: String, encrypted: Boolean, hashHex: String): String {
        return buildString {
            append("{\"type\":\"clip\",\"id\":\"").append(jsonEscape(id))
                .append("\",\"payload\":\"").append(jsonEscape(payload))
                .append("\",\"encrypted\":").append(encrypted)
                .append(",\"hash\":\"").append(jsonEscape(hashHex))
                .append("\"}")
        }
    }

    fun pongMessage(clientTimeUtc: String): String {
        return "{\"type\":\"pong\",\"clientTimeUtc\":\"${jsonEscape(clientTimeUtc)}\"}"
    }

    fun loginMessage(username: String, password: String): String {
        return "{\"username\":\"${jsonEscape(username)}\",\"password\":\"${jsonEscape(password)}\"}"
    }

    // ------------------------------------------------------------------
    // 下行消息（容错解析：未知 type 返回 Unknown，字段缺失给默认值）
    // ------------------------------------------------------------------

    sealed class ServerMessage {
        data class Welcome(val protocolVersion: Int = SUPPORTED_PROTOCOL_VERSION, val latest: LatestClip?) : ServerMessage()
        data class Clip(
            val id: String? = null,
            val version: Long,
            val payload: String,
            val encrypted: Boolean,
            val hashHex: String,
            val fromClientId: String? = null
        ) : ServerMessage()

        data class ClipAck(val id: String?, val version: Long, val updatedAtUtc: String? = null) : ServerMessage()
        data class Ping(val serverTimeUtc: String?) : ServerMessage()
        data class Bye(val reason: String?) : ServerMessage()
        data class Error(val code: String, val message: String?, val referenceId: String? = null) : ServerMessage()
        object Unknown : ServerMessage()
    }

    data class LatestClip(
        val version: Long,
        val payload: String,
        val encrypted: Boolean,
        val hashHex: String
    )

    fun parseServerMessage(json: String): ServerMessage {
        val obj = JSONObject(json)
        return when (val type = obj.optString("type")) {
            "welcome" -> ServerMessage.Welcome(
                protocolVersion = obj.optInt("protocolVersion", SUPPORTED_PROTOCOL_VERSION),
                latest = if (!obj.has("latest") || obj.isNull("latest")) {
                    null
                } else {
                    val latest = obj.getJSONObject("latest")
                    LatestClip(
                        version = latest.optLong("version", 0L),
                        payload = latest.optString("payload", ""),
                        encrypted = latest.optBoolean("encrypted", false),
                        hashHex = latest.optString("hash", "")
                    )
                }
            )
            "clip" -> ServerMessage.Clip(
                id = if (obj.has("id") && !obj.isNull("id")) obj.optString("id") else null,
                version = obj.optLong("version", 0L),
                payload = obj.optString("payload", ""),
                encrypted = obj.optBoolean("encrypted", false),
                hashHex = obj.optString("hash", ""),
                fromClientId = if (obj.has("fromClientId") && !obj.isNull("fromClientId")) {
                    obj.optString("fromClientId")
                } else {
                    null
                }
            )
            "clip_ack" -> ServerMessage.ClipAck(
                id = if (obj.has("id") && !obj.isNull("id")) obj.optString("id") else null,
                version = obj.optLong("version", 0L),
                updatedAtUtc = if (obj.has("updatedAtUtc") && !obj.isNull("updatedAtUtc")) {
                    obj.optString("updatedAtUtc")
                } else {
                    null
                }
            )
            "ping" -> ServerMessage.Ping(
                serverTimeUtc = if (obj.has("serverTimeUtc") && !obj.isNull("serverTimeUtc")) {
                    obj.optString("serverTimeUtc")
                } else {
                    null
                }
            )
            "bye" -> ServerMessage.Bye(
                reason = if (obj.has("reason") && !obj.isNull("reason")) obj.optString("reason") else null
            )
            "error" -> ServerMessage.Error(
                code = obj.optString("code", ""),
                message = if (obj.has("message") && !obj.isNull("message")) obj.optString("message") else null,
                referenceId = if (obj.has("referenceId") && !obj.isNull("referenceId")) {
                    obj.optString("referenceId")
                } else {
                    null
                }
            )
            else -> ServerMessage.Unknown
        }
    }
}
