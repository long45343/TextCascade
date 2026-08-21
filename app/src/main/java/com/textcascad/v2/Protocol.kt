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
import java.io.ByteArrayOutputStream
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

    /**
     * 把 value 作为带引号 JSON 字符串直接写入流：逐字符按 jsonEscape 相同规则
     * 转义并把 UTF-8 字节写入 output，不生成转义后的中间 String。
     * 输出与 "\"" + jsonEscape(value) + "\"" 的 UTF_8 编码逐字节一致；
     * 未配对代理项与 String.toByteArray(UTF_8) 行为一致，替换为单字节 '?'。
     */
    fun appendJsonString(output: ByteArrayOutputStream, value: String) {
        output.write('"'.code)
        var i = 0
        val n = value.length
        while (i < n) {
            val ch = value[i]
            when {
                ch == '"' -> {
                    output.write('\\'.code)
                    output.write('"'.code)
                }
                ch == '\\' -> {
                    output.write('\\'.code)
                    output.write('\\'.code)
                }
                ch == '\b' -> {
                    output.write('\\'.code)
                    output.write('b'.code)
                }
                ch == '\u000C' -> {
                    output.write('\\'.code)
                    output.write('f'.code)
                }
                ch == '\n' -> {
                    output.write('\\'.code)
                    output.write('n'.code)
                }
                ch == '\r' -> {
                    output.write('\\'.code)
                    output.write('r'.code)
                }
                ch == '\t' -> {
                    output.write('\\'.code)
                    output.write('t'.code)
                }
                ch < ' ' -> {
                    output.write('\\'.code)
                    output.write('u'.code)
                    val hex = ch.code.toString(16)
                    repeat(4 - hex.length) { output.write('0'.code) }
                    for (c in hex) output.write(c.code)
                }
                else -> {
                    var codePoint = ch.code
                    if (ch.isHighSurrogate() && i + 1 < n && value[i + 1].isLowSurrogate()) {
                        codePoint = Character.toCodePoint(ch, value[i + 1])
                        i++
                    } else if (ch.isHighSurrogate() || ch.isLowSurrogate()) {
                        codePoint = '?'.code
                    }
                    when {
                        codePoint < 0x80 -> output.write(codePoint)
                        codePoint < 0x800 -> {
                            output.write(0xC0 or (codePoint ushr 6))
                            output.write(0x80 or (codePoint and 0x3F))
                        }
                        codePoint < 0x10000 -> {
                            output.write(0xE0 or (codePoint ushr 12))
                            output.write(0x80 or ((codePoint ushr 6) and 0x3F))
                            output.write(0x80 or (codePoint and 0x3F))
                        }
                        else -> {
                            output.write(0xF0 or (codePoint ushr 18))
                            output.write(0x80 or ((codePoint ushr 12) and 0x3F))
                            output.write(0x80 or ((codePoint ushr 6) and 0x3F))
                            output.write(0x80 or (codePoint and 0x3F))
                        }
                    }
                }
            }
            i++
        }
        output.write('"'.code)
    }

    /** 带引号的 JSON 字符串字节数组（UTF-8 单次编码）。 */
    fun toUtf8JsonString(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length + 8)
        appendJsonString(out, value)
        return out.toByteArray()
    }

    /** 直写结构性 ASCII 字面量（仅限 ASCII，逐字符取低 8 位）。 */
    private fun ByteArrayOutputStream.writeAscii(text: String) {
        for (ch in text) write(ch.code)
    }

    // ------------------------------------------------------------------
    // 上行消息（Bytes 版本为唯一实现，String 版本委托之）
    // ------------------------------------------------------------------

    data class SnapshotPayload(
        val payload: String,
        val encrypted: Boolean,
        val hashHex: String,
        val localModifiedAtUtc: String
    )

    fun helloMessageBytes(
        clientId: String,
        clientName: String,
        lastServerVersion: Long,
        snapshot: SnapshotPayload?
    ): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.writeAscii("{\"type\":\"hello\",\"clientId\":")
        appendJsonString(out, clientId)
        out.writeAscii(",\"clientName\":")
        appendJsonString(out, clientName)
        out.writeAscii(",\"lastServerVersion\":")
        out.writeAscii(lastServerVersion.toString())
        if (snapshot != null) {
            out.writeAscii(",\"snapshot\":{\"payload\":")
            appendJsonString(out, snapshot.payload)
            out.writeAscii(",\"encrypted\":")
            out.writeAscii(snapshot.encrypted.toString())
            out.writeAscii(",\"hash\":")
            appendJsonString(out, snapshot.hashHex)
            out.writeAscii(",\"localModifiedAtUtc\":")
            appendJsonString(out, snapshot.localModifiedAtUtc)
            out.writeAscii("}")
        }
        out.writeAscii("}")
        return out.toByteArray()
    }

    fun helloMessage(
        clientId: String,
        clientName: String,
        lastServerVersion: Long,
        snapshot: SnapshotPayload?
    ): String =
        String(helloMessageBytes(clientId, clientName, lastServerVersion, snapshot), Charsets.UTF_8)

    fun clipMessageBytes(id: String, payload: String, encrypted: Boolean, hashHex: String): ByteArray {
        val out = ByteArrayOutputStream(64)
        out.writeAscii("{\"type\":\"clip\",\"id\":")
        appendJsonString(out, id)
        out.writeAscii(",\"payload\":")
        appendJsonString(out, payload)
        out.writeAscii(",\"encrypted\":")
        out.writeAscii(encrypted.toString())
        out.writeAscii(",\"hash\":")
        appendJsonString(out, hashHex)
        out.writeAscii("}")
        return out.toByteArray()
    }

    fun clipMessage(id: String, payload: String, encrypted: Boolean, hashHex: String): String =
        String(clipMessageBytes(id, payload, encrypted, hashHex), Charsets.UTF_8)

    fun pongMessageBytes(clientTimeUtc: String): ByteArray {
        val out = ByteArrayOutputStream(48)
        out.writeAscii("{\"type\":\"pong\",\"clientTimeUtc\":")
        appendJsonString(out, clientTimeUtc)
        out.writeAscii("}")
        return out.toByteArray()
    }

    fun pongMessage(clientTimeUtc: String): String =
        String(pongMessageBytes(clientTimeUtc), Charsets.UTF_8)

    fun loginMessageBytes(username: String, password: String): ByteArray {
        val out = ByteArrayOutputStream(48)
        out.writeAscii("{\"username\":")
        appendJsonString(out, username)
        out.writeAscii(",\"password\":")
        appendJsonString(out, password)
        out.writeAscii("}")
        return out.toByteArray()
    }

    fun loginMessage(username: String, password: String): String =
        String(loginMessageBytes(username, password), Charsets.UTF_8)

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
