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
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSyncEngineTwoPhaseReconnectTest {

    @Test
    fun cookiePhaseAllowsOnlyTwoScheduledReconnects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(
            websocketUrl = "ws://127.0.0.1:1",
            cookieHeader = "test-cookie"
        )

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("network error"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()

        // Wait for 3 websocket connects (1 initial + 2 cookie reconnects) and at least 1 relogin call
        awaitCondition {
            totalConnects.get() >= 3 && reloginCount.get() >= 1
        }

        // Must stop at 3 websocket connects
        assertEquals("Transport should connect 3 times (1 initial + 2 cookie reconnects)", 3, totalConnects.get())
        assertTrue("Cached relogin callback should have been called", reloginCount.get() >= 1)

        engine.stop()
    }

    @Test
    fun transientHttpFailureKeepsCachedReloginPhase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("HTTP 500"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()

        awaitCondition { reloginCount.get() >= 2 }

        assertEquals("WebSocket connects stay at 3", 3, totalConnects.get())
        assertTrue("Relogin retried in HTTP phase", reloginCount.get() >= 2)

        engine.stop()
    }

    @Test
    fun authFailureRetriesCachedRelogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.AuthFailure
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()

        awaitCondition { reloginCount.get() >= 2 }

        assertEquals("WebSocket connects stay at 3", 3, totalConnects.get())
        assertTrue("Relogin should retry on AuthFailure", reloginCount.get() >= 2)

        engine.stop()
    }

    @Test
    fun successfulCookieReconnectResetsAttemptCounter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        var listenerRef: StompClient.Listener? = null
        val totalConnects = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
            },
            stompClientFactory = { _, _, listener, _ ->
                listenerRef = listener
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()
        awaitCondition { totalConnects.get() == 1 }

        // Simulate successful connection
        listenerRef?.onConnected()
        assertTrue(engine.isConnected)

        // Now simulate disconnect
        listenerRef?.onError(ConnectException("lost"))

        // Wait for next reconnect
        awaitCondition { totalConnects.get() >= 2 }

        assertTrue(totalConnects.get() >= 2)

        engine.stop()
    }

    @Test
    fun forceReconnectRestartsFromCookiePhase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()
        awaitCondition { reloginCount.get() >= 1 }

        val connectsBeforeForce = totalConnects.get()
        assertEquals("Should be 3 connects before forceReconnect", 3, connectsBeforeForce)

        engine.forceReconnect()

        awaitCondition { totalConnects.get() > connectsBeforeForce }
        assertTrue("Force reconnect should trigger immediate connect", totalConnects.get() > connectsBeforeForce)

        engine.stop()
    }

    @Test
    fun noCredentialsFallsBackToSessionExpired() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val expiredCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onSessionExpired() {
                    expiredCount.incrementAndGet()
                }
                override fun onCachedReloginRequired(): CachedReloginResult {
                    return CachedReloginResult.NoCredentials
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()
        awaitCondition { expiredCount.get() >= 1 }

        assertEquals("onSessionExpired should be called once", 1, expiredCount.get())

        engine.stop()
    }

    @Test
    fun successfulCachedReloginStopsOldEngineScheduling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val fakeResult = LoginResult(
            normalizedServerUrl = "http://127.0.0.1:8080",
            websocketUrl = "ws://127.0.0.1:8080/ws",
            passwordSha3 = "sha3",
            hashedPasswordBase64 = "key",
            csrfToken = "csrf",
            cookieHeader = "cookie",
            maxSizeBytes = 1024
        )

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.Success(fakeResult)
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()
        awaitCondition { reloginCount.get() == 1 }

        Thread.sleep(100)
        assertEquals("Websocket connects stay at 3 after success", 3, totalConnects.get())
        assertEquals("Relogin called exactly once on success", 1, reloginCount.get())

        engine.stop()
    }

    @Test
    fun stopCancelsPendingCachedRelogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 100L } // Long delay
        )

        engine.start()
        engine.stop()

        assertTrue(engine.isStopped)
        assertEquals("Relogin should not be called after stop", 0, reloginCount.get())

        // Ensure restarting after stop works
        engine.start()
        assertFalse(engine.isStopped)
        engine.stop()
    }

    @Test
    fun userPresentReconnectPreservesCachedReloginPhase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(websocketUrl = "ws://127.0.0.1:1")

        val totalConnects = AtomicInteger(0)
        val reloginCount = AtomicInteger(0)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
                override fun onCachedReloginRequired(): CachedReloginResult {
                    reloginCount.incrementAndGet()
                    Thread.sleep(10)
                    return CachedReloginResult.TransientFailure(RuntimeException("fail"))
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        totalConnects.incrementAndGet()
                        listener.onError(ConnectException("refused"))
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {}
                    override fun close() {}
                }
            },
            reconnectDelayPolicy = { 0L }
        )

        engine.start()
        awaitCondition { reloginCount.get() >= 1 }

        val connectsBeforeUserPresent = totalConnects.get()
        assertEquals("3 connects before user present in HTTP phase", 3, connectsBeforeUserPresent)

        engine.reconnectAfterUserPresent()

        awaitCondition { reloginCount.get() >= 2 }
        assertEquals("In HTTP phase, user present should not add websocket connects", 3, totalConnects.get())

        engine.stop()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
    }
}
