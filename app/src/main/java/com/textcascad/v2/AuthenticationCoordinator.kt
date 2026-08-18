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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    fun submit(replaceActive: Boolean, task: (generation: Long) -> Unit): Long? {
        val requestGeneration: Long
        synchronized(lock) {
            val current = activeFuture
            if (current != null && !current.isDone) {
                if (!replaceActive) return null
                current.cancel(true)
            }
            requestGeneration = ++generation
            activeGeneration = requestGeneration
            activeFuture = executor.submit {
                try {
                    task(requestGeneration)
                } finally {
                    synchronized(lock) {
                        if (activeGeneration == requestGeneration) {
                            activeFuture = null
                        }
                    }
                }
            }
        }
        return requestGeneration
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

    fun <T> submitBlocking(replaceActive: Boolean, task: (generation: Long) -> T): T? {
        val result = AtomicReference<Result<T>?>(null)
        val finished = CountDownLatch(1)
        val requestGeneration = submit(replaceActive) { generation ->
            try {
                result.set(runCatching { task(generation) })
            } finally {
                finished.countDown()
            }
        } ?: return null

        return try {
            finished.await()
            if (!isCurrent(requestGeneration) && result.get() == null) {
                null
            } else {
                result.get()?.getOrThrow()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
