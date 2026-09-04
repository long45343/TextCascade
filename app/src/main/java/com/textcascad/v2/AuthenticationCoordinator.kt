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

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 进程级认证 single-flight。Service 销毁只会使自身任务失效，不关闭共享执行器。
 */
internal object AuthenticationCoordinator {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "textcascade-auth").apply { isDaemon = true }
    }
    private var generation = 0L
    private var activeGeneration = 0L
    private var activeFuture: Future<*>? = null

    fun submit(replaceActive: Boolean, task: (generation: Long) -> Unit): Long? =
        doSubmit(replaceActive, task)?.first

    /** 单飞登记 + 提交；返回 (generation, Future)。任务 finally 里清理 activeFuture 的语义保持不变。 */
    private fun <T> doSubmit(
        replaceActive: Boolean,
        task: (generation: Long) -> T
    ): Pair<Long, Future<T>>? {
        val requestGeneration: Long
        val future: Future<T>
        synchronized(lock) {
            val current = activeFuture
            if (current != null && !current.isDone) {
                if (!replaceActive) return null
                current.cancel(true)
            }
            requestGeneration = ++generation
            activeGeneration = requestGeneration
            future = executor.submit(Callable {
                try {
                    task(requestGeneration)
                } finally {
                    synchronized(lock) {
                        if (activeGeneration == requestGeneration) {
                            activeFuture = null
                        }
                    }
                }
            })
            activeFuture = future
        }
        return requestGeneration to future
    }

    fun isCurrent(requestGeneration: Long): Boolean = synchronized(lock) {
        requestGeneration == activeGeneration
    }

    internal fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        val future = synchronized(lock) { activeFuture }
        return try {
            future?.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: Throwable) {
            true
        }
    }

    internal fun resetForTests(timeoutMs: Long = 5_000L) {
        val future = synchronized(lock) {
            activeGeneration = ++generation
            activeFuture
        }
        future?.cancel(true)
        runCatching { future?.get(timeoutMs, TimeUnit.MILLISECONDS) }
        synchronized(lock) {
            if (activeFuture === future) activeFuture = null
        }
    }

    /**
     * 同步等待版本的 [submit]：任务异常原样抛出；未提交（忙且不可替换）、被替换/取消
     * 或被中断 → 返回 null。任务尚未开始即被取消时 [Future.get] 抛 CancellationException
     * （旧 latch 实现会在此永久挂起）。
     */
    fun <T> submitBlocking(replaceActive: Boolean, task: (generation: Long) -> T): T? {
        val (requestGeneration, future) = doSubmit(replaceActive) { generation ->
            runCatching { task(generation) }
        } ?: return null

        return try {
            val result = future.get()
            if (result.isFailure && !isCurrent(requestGeneration)) null else result.getOrThrow()
        } catch (e: CancellationException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
