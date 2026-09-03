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

/**
 * 传输层抽象：连接、发送与关闭由具体实现（OkHttpTransport）承担。
 * 传输实例单次使用：一次 connect() 之后不可复用，复用与重建由 ConnectionManager 负责。
 */
interface SyncTransport {
    fun connect()
    fun sendText(text: String)
    fun close(code: Int, reason: String)

    /** 默认按 UTF-8 解码后走 sendText；实现可覆写以直发原始字节。 */
    fun sendBytes(bytes: ByteArray) {
        sendText(String(bytes, Charsets.UTF_8))
    }

    /** 传输事件回调；每个实例的每个回调至多触发一次（onOpen 之后必有唯一的终态回调）。 */
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onError(error: Throwable)
        fun onSessionExpired(error: SessionExpiredException)
    }
}
