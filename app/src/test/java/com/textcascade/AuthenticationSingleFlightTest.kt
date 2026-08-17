/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthenticationSingleFlightTest {
    @Before
    fun setup() {
        AuthenticationCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        AuthenticationCoordinator.resetForTests()
    }
    @Test
    fun nonReplacingRequestsCannotRunConcurrently() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        val first = AuthenticationCoordinator.submit(replaceActive = true) { requestGeneration ->
            active.incrementAndGet().also { value -> maximumActive.updateAndGet { maxOf(it, value) } }
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            assertTrue(AuthenticationCoordinator.isCurrent(requestGeneration))
            active.decrementAndGet()
        }
        assertNotNull(first)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertNull(AuthenticationCoordinator.submit(replaceActive = false) { error("must not run") })
        release.countDown()
        waitUntil { active.get() == 0 }
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun replacingRequestInvalidatesOlderGenerationBeforeItCanCommit() {
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldCommitted = AtomicInteger()

        AuthenticationCoordinator.submit(replaceActive = true) { oldGeneration ->
            oldStarted.countDown()
            oldRelease.await(5, TimeUnit.SECONDS)
            if (AuthenticationCoordinator.isCurrent(oldGeneration)) oldCommitted.incrementAndGet()
        }
        assertTrue(oldStarted.await(5, TimeUnit.SECONDS))

        val replacement = AuthenticationCoordinator.submit(replaceActive = true) { newGeneration ->
            assertTrue(AuthenticationCoordinator.isCurrent(newGeneration))
        }
        assertNotNull(replacement)
        oldRelease.countDown()
        waitUntil { !AuthenticationCoordinator.isCurrent(replacement!!) || oldCommitted.get() == 0 }
        assertEquals(0, oldCommitted.get())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !condition()) Thread.yield()
        assertTrue(condition())
    }
}
