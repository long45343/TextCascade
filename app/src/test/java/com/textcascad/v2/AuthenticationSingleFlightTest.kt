/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthenticationSingleFlightTest {

    @Before
    fun setUp() {
        AuthenticationCoordinator.resetForTests()
    }

    @Test
    fun replaceActiveCancelsRunningTask() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val firstGeneration = AuthenticationCoordinator.submit(replaceActive = true) {
            started.countDown()
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                interrupted.countDown()
                Thread.currentThread().interrupt()
            }
        }
        assertNotNull(firstGeneration)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val second = AuthenticationCoordinator.submit(replaceActive = true) { }
        assertNotNull(second)

        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        assertFalse(AuthenticationCoordinator.isCurrent(firstGeneration!!))
        assertTrue(AuthenticationCoordinator.isCurrent(second!!))
        AuthenticationCoordinator.awaitIdle()
    }

    @Test
    fun nonReplaceSubmissionRejectedWhileBusy() {
        val gate = CountDownLatch(1)
        AuthenticationCoordinator.submit(replaceActive = true) {
            gate.await(10, TimeUnit.SECONDS)
        }
        val rejected = AuthenticationCoordinator.submit(replaceActive = false) { }
        assertNull(rejected)
        gate.countDown()
        AuthenticationCoordinator.awaitIdle()
    }

    @Test
    fun submitBlockingReturnsValue() {
        val result = AuthenticationCoordinator.submitBlocking(replaceActive = true) {
            "value-42"
        }
        assertEquals("value-42", result)
    }

    @Test
    fun tasksAreSerializedOnSingleExecutorThread() {
        val threadNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        repeat(3) {
            val generation = AuthenticationCoordinator.submit(replaceActive = true) { threadName ->
                threadNames.add(Thread.currentThread().name)
            }
            assertNotNull(generation)
            assertTrue(AuthenticationCoordinator.awaitIdle(10_000))
        }
        assertEquals(1, threadNames.distinct().size)
    }
}
