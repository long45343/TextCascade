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

import java.util.concurrent.atomic.AtomicLong

/**
 * 同步状态仓：收拢共享状态（lastSentHashHex / recentRemoteHashes / serverVersion /
 * remoteApplyGeneration / sendPausedUntilMs），单锁保证复合读写的原子性。
 *
 * 自写回显抑制依赖 recentRemoteHashes 池（hash 写前登记，对齐桌面端）：
 * 远端内容落剪贴板前 hash 已入池，随后系统剪贴板变更事件触发的本地采集会命中
 * 同 hash 而被抑制。不使用一次性旗子——旗子在采集链路有轮询延迟时会把用户的
 * 下一次真实复制吞掉（v2.3.5 实机复测定位的首复制延迟残余根因）。
 */
class SyncStateStore(initialServerVersion: Long) {

    private val lock = Any()

    private var lastSentHashHex: String? = null
    private var lastRemoteHashHex: String? = null
    private val recentRemoteHashes = LinkedHashSet<String>(REMOTE_HASH_CAPACITY)

    @Volatile
    var serverVersion: Long = initialServerVersion
        get() = synchronized(lock) { field }
        set(value) {
            synchronized(lock) { field = value }
        }

    @Volatile
    var sendPausedUntilMs: Long = 0L

    private val remoteApplyGeneration = AtomicLong(0L)

    /** 原子读取：hash 是否等于最近一次远端落盘 hash（保留兼容）。 */
    fun isEchoOfLastRemote(hashHex: String): Boolean = synchronized(lock) {
        lastRemoteHashHex == hashHex
    }

    /** 原子读取：hash 是否命中最近 16 条远端落盘 hash。空白 hash 不命中。 */
    fun isEchoOfRecentRemote(hashHex: String): Boolean = synchronized(lock) {
        if (hashHex.isBlank()) return@synchronized false
        recentRemoteHashes.contains(hashHex)
    }

    /** 远端已落盘：原子记录 hash（放入容量为 16 的池中，供自写回显按 hash 抑制）。空白 hash 不入池。 */
    fun markRemoteApplied(hashHex: String) {
        synchronized(lock) {
            lastRemoteHashHex = hashHex
            if (hashHex.isNotBlank()) {
                if (recentRemoteHashes.contains(hashHex)) {
                    recentRemoteHashes.remove(hashHex)
                } else if (recentRemoteHashes.size >= REMOTE_HASH_CAPACITY) {
                    val oldest = recentRemoteHashes.iterator().next()
                    recentRemoteHashes.remove(oldest)
                }
                recentRemoteHashes.add(hashHex)
            }
        }
    }

    /** 远端落盘失败回滚：仅当 hash 仍是当前最新记录时清除并移出池。 */
    fun rollbackRemoteAppliedIfCurrent(hashHex: String) {
        synchronized(lock) {
            if (lastRemoteHashHex == hashHex) {
                lastRemoteHashHex = null
            }
            if (hashHex.isNotBlank()) {
                recentRemoteHashes.remove(hashHex)
            }
        }
    }

    /** 发送成功后记录 hash（供 welcome/clip 回显去重）。 */
    fun setLastSentHashHex(hashHex: String) {
        synchronized(lock) { lastSentHashHex = hashHex }
    }

    /** 原子判定：版本更新且非自身回显时才应用远端载荷。 */
    fun shouldApplyRemote(version: Long, hashHex: String): Boolean = synchronized(lock) {
        version > serverVersion && hashHex != lastSentHashHex
    }

    /** 原子推进服务端版本；返回是否真的前进。 */
    fun advanceServerVersion(version: Long): Boolean = synchronized(lock) {
        if (version > serverVersion) {
            serverVersion = version
            true
        } else {
            false
        }
    }

    fun remoteApplyGeneration(): Long = remoteApplyGeneration.get()

    fun incrementRemoteApplyGeneration(): Long = remoteApplyGeneration.incrementAndGet()

    companion object {
        const val REMOTE_HASH_CAPACITY = 16
    }
}