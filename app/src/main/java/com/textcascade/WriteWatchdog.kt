/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
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

/**
 * 写进度看门狗。
 *
 * Java 的 `Socket.setSoTimeout` 只作用于读，没有任何写超时 API。对端消失但本端
 * 尚未收到 FIN 的半开连接上，`OutputStream.write` / `flush` 会一直阻塞，
 * 剪贴板内容既不送达也不报错，属于静默丢失。
 *
 * 本类跟踪写进度：调用方在每次写开始 [begin]、每写完一块 [progress]、写结束 [end]，
 * 定时任务周期调用 [check]；写进度停滞超过 [timeoutMs] 时触发 [onStall]，
 * 由调用方关闭底层 socket，使阻塞中的写抛 IOException 并把错误上报到重连链路。
 */
internal class WriteWatchdog(
    private val timeoutMs: Long,
    private val nowMs: () -> Long,
    private val onStall: () -> Unit
) {
    private var writing = false
    private var lastProgressMs = 0L
    private var stalled = false

    fun begin() {
        writing = true
        stalled = false
        lastProgressMs = nowMs()
    }

    fun progress() {
        if (writing) lastProgressMs = nowMs()
    }

    fun end() {
        writing = false
    }

    /** 由定时任务周期调用；返回 true 表示本次检测判定为超时并已触发回调。 */
    fun check(): Boolean {
        if (!writing) return false
        if (nowMs() - lastProgressMs <= timeoutMs) return false
        // 只触发一次：后续 check 不应重复关闭 socket
        writing = false
        stalled = true
        onStall()
        return true
    }

    /** 最近一次写是否因超时被中止（供测试观察）。 */
    fun hasStalled(): Boolean = stalled

    /** 停止巡检并清空状态（连接结束时调用）。 */
    fun reset() {
        writing = false
        stalled = false
    }
}
