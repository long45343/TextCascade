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

import android.content.Context

import android.os.Handler
import android.os.Looper

/**
 * 进程内运行时状态单一来源。
 *
 * 按 three-area-refactor-spec R1/R2：本对象不再使用 SharedPreferences，
 * `statusMessage`、`connectionStatusMessage`、`backgroundStatus`、`hasSession`、
 * `serviceRunning` 只保存在进程内存中；Application 创建时用低频的
 * [AppPreferences.sessionActive] 初始化 [hasSession]。
 */
class RuntimeStateStore {
    data class RuntimeSnapshot(
        val statusMessage: String = "",
        val connectionStatusMessage: String = "",
        val backgroundStatus: String = "",
        val hasSession: Boolean = false,
        val serviceRunning: Boolean = false,
        val passwordDecryptionFailed: Boolean = false,
        val securityDegraded: Boolean = false
    )

    interface Listener {
        fun onChanged()
    }

    private val lock = Any()
    private var snapshotInternal = RuntimeSnapshot()
    private val listeners = mutableListOf<Listener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val current: RuntimeSnapshot get() = synchronized(lock) { snapshotInternal }

    var statusMessage: String
        get() = current.statusMessage
        set(value) { update { it.copy(statusMessage = value) } }

    var connectionStatusMessage: String
        get() = current.connectionStatusMessage
        set(value) { update { it.copy(connectionStatusMessage = value) } }

    var backgroundStatus: String
        get() = current.backgroundStatus
        set(value) { update { it.copy(backgroundStatus = value) } }

    var hasSession: Boolean
        get() = current.hasSession
        set(value) { update { it.copy(hasSession = value) } }

    var serviceRunning: Boolean
        get() = current.serviceRunning
        set(value) { update { it.copy(serviceRunning = value) } }

    var passwordDecryptionFailed: Boolean
        get() = current.passwordDecryptionFailed
        set(value) { update { it.copy(passwordDecryptionFailed = value) } }

    var securityDegraded: Boolean
        get() = current.securityDegraded
        set(value) { update { it.copy(securityDegraded = value) } }

    fun initialize(sessionActive: Boolean, securityDegraded: Boolean = false) {
        update {
            it.copy(
                hasSession = sessionActive,
                securityDegraded = securityDegraded
            )
        }
    }

    fun consumePasswordDecryptionFailure(): Boolean {
        synchronized(lock) {
            if (!snapshotInternal.passwordDecryptionFailed) return false
            snapshotInternal = snapshotInternal.copy(passwordDecryptionFailed = false)
        }
        notifyListenersOnUiThread()
        return true
    }

    fun clearRuntimeState(): Boolean {
        update {
            RuntimeSnapshot(
                securityDegraded = it.securityDegraded
            )
        }
        return true
    }

    /** 失效标记为纯内存操作；对应的持久 `session_active` 由调用方在同一凭据事务内维护。 */
    fun markSessionInvalid(): Boolean {
        update { it.copy(hasSession = false, serviceRunning = false) }
        return true
    }

    fun registerListener(listener: Listener) {
        synchronized(lock) {
            if (listeners.none { it === listener }) {
                listeners.add(listener)
            }
        }
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) {
            listeners.removeAll { it === listener }
        }
    }
    private fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot): Boolean {
        var changed: Boolean
        synchronized(lock) {
            val next = transform(snapshotInternal)
            changed = next != snapshotInternal
            snapshotInternal = next
        }
        if (changed) {
            notifyListenersOnUiThread()
        }
        return changed
    }

    private fun notifyListenersOnUiThread() {
        val runnable = Runnable {
            val targets = synchronized(lock) { listeners.toList() }
            for (listener in targets) {
                runCatching { listener.onChanged() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            runCatching { mainHandler.post(runnable) }.onFailure { runnable.run() }
        }
    }
}

/**
 * Application 进程级 [RuntimeStateStore] 共享点。
 *
 * 这里使用进程单例而不是 Android `Application` 引用：单元测试可以显式 reset，
 * Service/Activity 也无需相互传递依赖。生产中的首个调用通常来自
 * [TextCascadeApplication.onCreate]。
 */
object RuntimeStateStoreHolder {
    @Volatile
    private var store: RuntimeStateStore? = null

    // @Synchronized 保证懒加载线程安全（原实现无锁存在双构造竞态）；
    // 不用 by lazy：resetForTest 需要可重置。
    val current: RuntimeStateStore
        @Synchronized get() = store ?: RuntimeStateStore().also { store = it }

    fun forContext(@Suppress("UNUSED_PARAMETER") context: Context): RuntimeStateStore = current

    fun initialize(sessionActive: Boolean, securityDegraded: Boolean = false) {
        current.initialize(sessionActive, securityDegraded)
    }

    fun resetForTest() {
        store = RuntimeStateStore()
        store = null
    }
}





