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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写超时看门狗。每一条用例对应一个真实链路场景：
 * 判定错误会导致「剪贴板内容静默丢失」或「健康连接被误杀」。
 */
class WriteWatchdogTest {

    private var now = 0L
    private var stallCount = 0

    private fun watchdog(timeoutMs: Long = 2_000L) =
        WriteWatchdog(timeoutMs = timeoutMs, nowMs = { now }, onStall = { stallCount++ })

    @Test
    fun idleWatchdogNeverStalls() {
        // 场景：连接空闲，没有任何写操作。看门狗不得因为「很久没写」而关闭连接。
        val watchdog = watchdog()
        now = 10 * 60_000L
        assertFalse(watchdog.check())
        assertEquals(0, stallCount)
    }

    @Test
    fun slowButProgressingWriteIsNotKilled() {
        // 场景：大段文本分块发送，每块耗时但都有进展（慢速蜂窝网络）。
        // 只要进度在推进，就不能判超时。
        val watchdog = watchdog()
        watchdog.begin()
        repeat(10) {
            now += 1_500L
            watchdog.progress()
            assertFalse("进度在推进时不应判超时", watchdog.check())
        }
        assertEquals(0, stallCount)
    }

    @Test
    fun stalledWriteTriggersOnce() {
        // 场景：半开连接，write 阻塞不动。超时后必须触发一次并让调用方关闭 socket。
        val watchdog = watchdog()
        watchdog.begin()
        now += 1_999L
        assertFalse(watchdog.check())
        now += 2L
        assertTrue(watchdog.check())
        assertEquals(1, stallCount)
        assertTrue(watchdog.hasStalled())
    }

    @Test
    fun stallIsNotReportedRepeatedly() {
        // 场景：socket 关闭后定时任务还在跑。不能反复触发关闭与状态上报。
        val watchdog = watchdog()
        watchdog.begin()
        now += 5_000L
        assertTrue(watchdog.check())
        assertFalse(watchdog.check())
        assertFalse(watchdog.check())
        assertEquals(1, stallCount)
    }

    @Test
    fun writeCompletingWithinBudgetNeverStalls() {
        // 场景：正常发送，几百毫秒内完成。结束后看门狗回到空闲态，
        // 长时间无写也不应误判。
        val watchdog = watchdog()
        watchdog.begin()
        now += 300L
        watchdog.progress()
        watchdog.end()
        now += 60_000L
        assertFalse(watchdog.check())
        assertEquals(0, stallCount)
    }

    @Test
    fun subsequentWriteResetsStallFlag() {
        // 场景：一次超时后重连成功，新连接上的写必须重新开始计时，
        // 不能带着上一次的 stalled 状态。
        val watchdog = watchdog()
        watchdog.begin()
        now += 3_000L
        assertTrue(watchdog.check())
        assertTrue(watchdog.hasStalled())

        watchdog.begin()
        assertFalse("新一次写开始后应清空停滞标记", watchdog.hasStalled())
        now += 500L
        assertFalse(watchdog.check())
    }

    @Test
    fun progressOnIdleWatchdogIsIgnored() {
        // 场景：写已结束但迟到的 progress 回调不能重新激活计时。
        val watchdog = watchdog()
        watchdog.begin()
        watchdog.end()
        now += 10_000L
        watchdog.progress()
        assertFalse(watchdog.check())
    }

    @Test
    fun resetClearsActiveWrite() {
        // 场景：连接在写进行中被上层主动关闭，reset 后不得再判超时。
        val watchdog = watchdog()
        watchdog.begin()
        watchdog.reset()
        now += 5_000L
        assertFalse(watchdog.check())
        assertEquals(0, stallCount)
    }

    @Test
    fun defaultTimeoutMatchesDesignValue() {
        // 与 v2 的 OkHttp writeTimeout(2s) 对齐：半开连接上的复制应在 ~2s 内暴露。
        assertEquals(2_000L, RawWebSocketClient.WRITE_TIMEOUT_MS)
    }
}
