/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSyncEngineLifecycleTest {

    @Test
    fun stopThenForceReconnectDoesNotCreateASecondTransport() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }
        engine.stop()
        engine.forceReconnect()
        Thread.sleep(50)
        assertEquals(1, fixture.transports.size)
        assertTrue(engine.isStopped)
    }

    @Test
    fun callbacksFromTransportReplacedByForceReconnectAreIgnored() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }
        val oldTransport = fixture.transports[0]

        engine.forceReconnect()
        waitUntil { fixture.transports.size == 2 && fixture.transports[1].connected && fixture.transports[1].subscribeCount == 1 }
        val currentTransport = fixture.transports[1]
        val subscriptionsBefore = currentTransport.subscribeCount

        // 5 types of callbacks on old transport
        oldTransport.listener.onConnected()
        oldTransport.listener.onMessage(JsonUtil.clipMessage("old", "stale"))
        oldTransport.listener.onClosed("stale closed")
        oldTransport.listener.onError(IllegalStateException("stale error"))
        oldTransport.listener.onSessionExpired(SessionExpiredException(401))

        ShadowLooper.idleMainLooper()
        Thread.sleep(50)

        assertEquals(subscriptionsBefore, currentTransport.subscribeCount)
        assertTrue(fixture.clipboardWrites.isEmpty())
        assertFalse(fixture.sessionExpiredCalled.get())
        engine.stop()
    }

    @Test
    fun concurrentStartAndStopLifecycleRemainsConsistentAndExecutorDoesNotLeak() {
        val fixture = Fixture()
        val engine = fixture.engine()

        val latch = CountDownLatch(10)
        for (i in 0 until 10) {
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    if (i % 2 == 0) {
                        engine.start()
                    } else {
                        engine.stop()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        engine.stop()

        assertTrue(engine.isStopped)
        engine.forceReconnect()
        Thread.sleep(50)
        assertTrue(engine.isStopped)
    }

    @Test
    fun concurrentStartForceReconnectStopLeavesAtMostOneActiveTransport() {
        val fixture = Fixture()
        val engine = fixture.engine()

        val latch = CountDownLatch(15)
        for (i in 0 until 15) {
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    when (i % 3) {
                        0 -> engine.start()
                        1 -> engine.forceReconnect()
                        2 -> engine.stop()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        engine.stop()

        assertTrue(engine.isStopped)
        // Verify all created transports are closed
        assertTrue(fixture.transports.all { !it.connected })
        assertTrue(fixture.maxActiveTransportCount.get() <= 1)
    }

    @Test
    fun repeatedStartStopShutsDownEveryCreatedExecutor() {
        val fixture = Fixture()
        val executors = CopyOnWriteArrayList<ScheduledExecutorService>()
        val engine = fixture.engine(
            executorFactory = {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "textcascade-sync-test").apply { isDaemon = true }
                }.also { executors += it }
            }
        )

        repeat(3) {
            engine.start()
            waitUntil { fixture.transports.size == it + 1 && fixture.transports[it].connected }
            engine.stop()
        }

        assertTrue(executors.isNotEmpty())
        assertTrue(executors.all { it.isShutdown && it.awaitTermination(5, TimeUnit.SECONDS) })
        engine.forceReconnect()
        assertTrue(engine.isStopped)
    }

    @Test
    fun startExecuteFailureRollsBackAndAllowsLaterStart() {
        val fixture = Fixture()
        val rejecting = object : ScheduledThreadPoolExecutor(1) {
            override fun execute(command: Runnable) {
                throw IllegalStateException("executor rejected start")
            }
        }
        var factoryCalls = 0
        val engine = fixture.engine(
            executorFactory = {
                factoryCalls++
                if (factoryCalls == 1) rejecting else Executors.newSingleThreadScheduledExecutor()
            }
        )
        val generationBefore = engine.connectionGenerationForTest()

        try {
            engine.start()
            throw AssertionError("Expected executor rejection")
        } catch (error: IllegalStateException) {
            assertEquals("executor rejected start", error.message)
        }

        assertTrue(engine.isStopped)
        assertTrue(rejecting.isShutdown)
        assertTrue(engine.executorForTest() == null)
        assertTrue(engine.connectionGenerationForTest() > generationBefore)

        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }
        engine.stop()
    }

    @Test
    fun stopPreventsPendingRemoteApplyFromWritingClipboard() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }

        val transport = fixture.transports[0]
        // Deliver message which posts to main looper
        transport.listener.onMessage(JsonUtil.clipMessage("delayed_text", "remote"))

        // Stop engine before main looper processes the runnable
        engine.stop()

        // Execute queued runnables on main looper
        ShadowLooper.idleMainLooper()

        // Clipboard should not have received the text
        assertTrue(fixture.clipboardWrites.isEmpty())
    }

    @Test
    fun encodedOversizeOutboundMessageIsNotSentAndAllowsSubsequentLegalMessage() {
        val fixture = Fixture()
        val engine = fixture.engine(
            ClipConfig.default(fixture.context).copy(
                cipherEnabled = false,
                maxSizeBytes = ClipConfig.MAX_CLIPBOARD_BYTES,
                localMaxClipboardBytes = ClipConfig.MAX_CLIPBOARD_BYTES
            )
        )
        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }

        // Send oversized text
        engine.sendLocalText("x".repeat(ClipConfig.MAX_TRANSPORT_BYTES.toInt()), "test")
        waitUntil { fixture.statuses.any { it.contains("encoded", ignoreCase = true) || it.contains("超过", ignoreCase = true) } }
        assertTrue(fixture.transports[0].sentBodies.isEmpty())

        // Send valid text with same content/pattern
        engine.sendLocalText("valid small text", "test")
        waitUntil { fixture.transports[0].sentBodies.isNotEmpty() }
        assertEquals(1, fixture.transports[0].sentBodies.size)
        assertTrue(fixture.transports[0].sentBodies[0].contains("valid small text"))

        engine.stop()
    }

    @Test
    fun inboundOversizeMessageIsNotAppliedToClipboard() {
        val fixture = Fixture()
        val engine = fixture.engine(
            ClipConfig.default(fixture.context).copy(
                cipherEnabled = false,
                maxSizeBytes = 100L,
                localMaxClipboardBytes = 100L
            )
        )
        engine.start()
        waitUntil { fixture.transports.size == 1 && fixture.transports[0].connected }

        val transport = fixture.transports[0]
        val oversizedInbound = "a".repeat(200)
        transport.listener.onMessage(JsonUtil.clipMessage(oversizedInbound, "text"))
        waitUntil { fixture.statuses.any { it.contains("clipboard", ignoreCase = true) || it.contains("剪贴板", ignoreCase = true) } }
        ShadowLooper.idleMainLooper()

        assertTrue(fixture.clipboardWrites.isEmpty())

        // Subsequent valid inbound is applied
        transport.listener.onMessage(JsonUtil.clipMessage("short text", "text"))
        waitUntil {
            ShadowLooper.idleMainLooper()
            fixture.clipboardWrites.isNotEmpty()
        }

        assertEquals(listOf("short text"), fixture.clipboardWrites)
        engine.stop()
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val statuses = CopyOnWriteArrayList<String>()
        val clipboardWrites = CopyOnWriteArrayList<String>()
        val sessionExpiredCalled = AtomicBoolean(false)

        val activeTransportCount = AtomicInteger()
        val maxActiveTransportCount = AtomicInteger()

        fun engine(
            config: ClipConfig = ClipConfig.default(context).copy(cipherEnabled = false),
            executorFactory: () -> ScheduledExecutorService = {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "textcascade-sync").apply { isDaemon = true }
                }
            }
        ): TextSyncEngine =
            TextSyncEngine(
                context = context,
                config = config,
                callbacks = object : TextSyncEngine.Callbacks {
                    override fun onStatus(message: String) { statuses += message }
                    override fun onRemoteTextApplied(text: String) {}
                    override fun onSessionExpired() { sessionExpiredCalled.set(true) }
                },
                stompClientFactory = { _, _, listener, _ ->
                    FakeTransport(listener, activeTransportCount, maxActiveTransportCount).also { transports += it }
                },
                reconnectDelayPolicy = { 60L },
                executorFactory = executorFactory,
                clipboardWriter = { clipboardWrites += it }
            )
    }

    private class FakeTransport(
        val listener: StompClient.Listener,
        private val activeTransportCount: AtomicInteger,
        private val maxActiveTransportCount: AtomicInteger
    ) : StompTransport {
        @Volatile
        var connected = false
        var subscribeCount = 0
        val sentBodies = CopyOnWriteArrayList<String>()

        override fun connect() {
            if (!connected) {
                connected = true
                val active = activeTransportCount.incrementAndGet()
                maxActiveTransportCount.accumulateAndGet(active, ::maxOf)
            }
            listener.onConnected()
        }

        override fun subscribe(destination: String) {
            subscribeCount++
        }

        override fun send(destination: String, body: String) {
            sentBodies += body
        }

        override fun close() {
            if (connected) {
                connected = false
                activeTransportCount.decrementAndGet()
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(10)
        assertTrue(condition())
    }
}
