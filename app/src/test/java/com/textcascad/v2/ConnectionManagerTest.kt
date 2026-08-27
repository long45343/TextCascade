/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ConnectionCloseInfo
import com.textcascad.v2.engine.ConnectionManager
import com.textcascad.v2.engine.SyncStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class ConnectionManagerTest {

    private class FakeStringProvider : StringProvider {
        val calls = mutableListOf<Pair<Int, Array<out Any>>>()
        override fun get(id: Int, vararg args: Any): String {
            calls.add(id to args)
            return "S$id|${args.joinToString("|") { it.toString() }}"
        }
    }

    private class FakeTransport : SyncTransport {
        val sentTexts = mutableListOf<String>()
        val sentBytes = mutableListOf<ByteArray>()
        val closes = mutableListOf<Pair<Int, String>>()
        var connectCount = 0
        var connected = false
        var pinnedCertSha256 = ""
        lateinit var listener: RawWebSocketClient.Listener

        fun bind(listener: RawWebSocketClient.Listener) {
            this.listener = listener
        }

        override fun connect() {
            connectCount++
            connected = true
            listener.onOpen()
        }

        override fun sendText(text: String) {
            sentTexts.add(text)
        }

        override fun sendBytes(body: ByteArray) {
            sentBytes.add(body)
        }

        override fun close(code: Int, reason: String) {
            closes.add(code to reason)
            connected = false
        }
    }

    /** S1：捕获少量显式连接事件，替代原宽接口 RecordingEvents。 */
    private class RecordingEvents {
        val statuses = mutableListOf<String>()
        val closed = mutableListOf<ConnectionCloseInfo>()
        var sessionExpiredCount = 0
        val inboundTexts = mutableListOf<Pair<Long, String>>()
        val connectedCalls = mutableListOf<Pair<Long, SyncTransport?>>()

        fun onStatus(message: String) {
            statuses.add(message)
        }

        fun onConnected(generation: Long, transport: SyncTransport?) {
            connectedCalls.add(generation to transport)
        }

        fun onInboundText(generation: Long, body: String) {
            inboundTexts.add(generation to body)
        }

        fun onClosed(generation: Long, closeInfo: ConnectionCloseInfo) {
            closed.add(closeInfo)
        }

        fun onSessionExpired() {
            sessionExpiredCount++
        }
    }

    private fun managerConfig(
        tokenExpiresAtUtc: Long = 0L,
        heartbeatTimeoutSeconds: Int = 60,
        trustAllCerts: Boolean = false,
        pinnedCertSha256: String = ""
    ) = ClipConfig(
        session = ServerSession(
            serverUrl = "https://example.invalid",
            username = "user",
            token = "token-1",
            tokenExpiresAtUtc = tokenExpiresAtUtc,
            clientId = "client",
            clientName = "test"
        ),
        userPrefs = UserPrefs(
            maxTextBytes = 512_000L,
            helloTimeoutSeconds = 10,
            heartbeatIntervalSeconds = 20,
            heartbeatTimeoutSeconds = heartbeatTimeoutSeconds,
            lastServerVersion = 0L,
            relaunchOnBoot = false,
            websocketStatusNotification = false,
            localMaxClipboardBytes = 512_000L
        ),
        cryptoMaterial = CryptoMaterial(
            derivedKeyBase64 = "",
            hashRounds = 1000,
            salt = "salt",
            cipherEnabled = false,
            trustAllCerts = trustAllCerts,
            pinnedCertSha256 = pinnedCertSha256
        )
    )

    private fun newManager(
        config: ClipConfig = managerConfig(),
        events: RecordingEvents = RecordingEvents(),
        stringProvider: FakeStringProvider = FakeStringProvider(),
        normalDelays: List<Long> = listOf(1L, 2L, 5L, 10L, 30L, 60L),
        maintenanceDelays: List<Long> = listOf(1L, 2L, 5L, 10L),
        transports: CopyOnWriteArrayList<FakeTransport> = CopyOnWriteArrayList(),
        reloginResult: AuthResult = AuthResult.NoCredentials
    ): ConnectionManager =
        ConnectionManager(
            config = config,
            state = SyncStateStore(config.userPrefs.lastServerVersion),
            executorFactory = {
                Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "test-conn").apply { isDaemon = true }
                }
            },
            transportFactory = { _, _, listener, _, pinned, _ ->
                FakeTransport().also { it.pinnedCertSha256 = pinned; it.bind(listener); transports.add(it) }
            },
            nowMs = System::currentTimeMillis,
            stringProvider = stringProvider,
            userPresentReconnectDelaySeconds = 0L,
            rateLimitedReloginFloorSeconds = 30L,
            backoffDelaysNormalSeconds = normalDelays,
            backoffDelaysMaintenanceSeconds = maintenanceDelays,
            onCachedReloginRequired = { reloginResult },
            onStatus = events::onStatus,
            onConnected = events::onConnected,
            onInboundText = events::onInboundText,
            onClosed = events::onClosed,
            onSessionExpired = events::onSessionExpired
        )

    private fun awaitTrue(timeoutMs: Long = 5000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun backoffNormalSequenceFollowsConfiguredDelays() {
        val manager = newManager()
        assertEquals(1L, manager.backoffDelaySeconds(0, maintenance = false))
        assertEquals(2L, manager.backoffDelaySeconds(1, maintenance = false))
        assertEquals(5L, manager.backoffDelaySeconds(2, maintenance = false))
        assertEquals(10L, manager.backoffDelaySeconds(3, maintenance = false))
        assertEquals(30L, manager.backoffDelaySeconds(4, maintenance = false))
        assertEquals(60L, manager.backoffDelaySeconds(5, maintenance = false))
        assertEquals(60L, manager.backoffDelaySeconds(12, maintenance = false))
    }

    @Test
    fun backoffMaintenanceSequenceFollowsConfiguredDelays() {
        val manager = newManager()
        assertEquals(1L, manager.backoffDelaySeconds(0, maintenance = true))
        assertEquals(2L, manager.backoffDelaySeconds(1, maintenance = true))
        assertEquals(5L, manager.backoffDelaySeconds(2, maintenance = true))
        assertEquals(10L, manager.backoffDelaySeconds(3, maintenance = true))
        assertEquals(10L, manager.backoffDelaySeconds(9, maintenance = true))
    }

    @Test
    fun backoffEmptyDelaysFallBackToOneSecond() {
        val manager = newManager(normalDelays = emptyList(), maintenanceDelays = emptyList())
        assertEquals(1L, manager.backoffDelaySeconds(0, maintenance = false))
        assertEquals(1L, manager.backoffDelaySeconds(7, maintenance = true))
    }

    @Test
    fun startCreatesTransportWithConfiguredParameters() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val manager = newManager(
            config = managerConfig(trustAllCerts = true, pinnedCertSha256 = "AA:BB:CC:DD"),
            transports = transports
        )
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        val transport = transports.first()
        assertNotNull(manager.executorForTest())
        assertFalse(manager.isStopped)
        assertTrue(awaitTrue { transport.connectCount == 1 })
        assertEquals("AA:BB:CC:DD", transport.pinnedCertSha256)
        manager.stop()
    }

    @Test
    fun startIsIdempotentWhileRunning() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val manager = newManager(transports = transports)
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        manager.start()
        assertTrue(awaitTrue { transports.first().connectCount == 1 })
        Thread.sleep(50)
        assertEquals(1, transports.size)
        manager.stop()
    }

    @Test
    fun stopClosesTransportAndShutsDownExecutor() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val manager = newManager(transports = transports)
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        manager.stop()
        assertTrue(manager.isStopped)
        assertEquals(listOf(1000 to "client_stop"), transports.first().closes)
        awaitTrue { manager.executorForTest() == null }
        assertEquals(null, manager.executorForTest())
    }

    @Test
    fun handleOpenNotifiesConnectedWithTransport() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val events = RecordingEvents()
        val manager = newManager(events = events, transports = transports)
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        assertTrue(awaitTrue { events.connectedCalls.size == 1 })
        val (generation, transport) = events.connectedCalls.first()
        assertEquals(manager.connectionGenerationForTest(), generation)
        assertEquals(transports.first(), transport)
        assertTrue(manager.isConnected)
        manager.stop()
    }

    @Test
    fun handleOpenWithStaleGenerationIsIgnored() {
        val events = RecordingEvents()
        val manager = newManager(events = events)
        manager.start()
        manager.stop()
        val staleGeneration = manager.connectionGenerationForTest() - 1
        manager.handleOpen(staleGeneration)
        assertEquals(0, events.connectedCalls.size)
        assertFalse(manager.isConnected)
    }

    @Test
    fun handleClosedEmitsDisconnectedDetailAndSchedulesReconnect() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val events = RecordingEvents()
        val manager = newManager(events = events, transports = transports, normalDelays = listOf(0L))
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        transports.first().listener.onClosed(1006, "boom")
        assertTrue(awaitTrue { events.closed.isNotEmpty() })
        val closeInfo = events.closed.first()
        assertTrue(closeInfo is ConnectionCloseInfo.Closed)
        assertEquals(1006, (closeInfo as ConnectionCloseInfo.Closed).code)
        assertEquals("boom", closeInfo.reason)
        assertTrue(awaitTrue { transports.size >= 2 })
        manager.stop()
    }

    @Test
    fun handleClosedWith1001UsesMaintenanceBackoffDelay() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val events = RecordingEvents()
        val stringProvider = FakeStringProvider()
        val manager = newManager(
            events = events,
            stringProvider = stringProvider,
            transports = transports,
            normalDelays = listOf(5L),
            maintenanceDelays = listOf(2L)
        )
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        transports.first().listener.onClosed(1001, "going away")
        assertTrue(awaitTrue { transports.size >= 2 })
        val waitingCall = stringProvider.calls.last { it.first == R.string.status_waiting_reconnect }
        assertEquals(2L, waitingCall.second[0])
        manager.stop()
    }

    @Test
    fun handleErrorMarksDisconnectedAndSchedulesReconnect() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val events = RecordingEvents()
        val manager = newManager(events = events, transports = transports, normalDelays = listOf(0L))
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        transports.first().listener.onError(java.io.IOException("reset"))
        assertTrue(awaitTrue { events.closed.any { it is ConnectionCloseInfo.Error } })
        assertTrue(awaitTrue { transports.size >= 2 })
        manager.stop()
    }

    @Test
    fun handleSessionExpiredStopsReconnectingAndNotifies() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val events = RecordingEvents()
        val manager = newManager(events = events, transports = transports)
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        val generationBefore = manager.connectionGenerationForTest()
        manager.handleSessionExpired(generationBefore)
        assertEquals(1, events.sessionExpiredCount)
        assertTrue(manager.connectionGenerationForTest() > generationBefore)
        assertFalse(manager.isConnected)
        manager.stop()
    }

    @Test
    fun forceReconnectWhenStoppedIsNoOp() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val manager = newManager(transports = transports)
        manager.forceReconnect()
        assertTrue(manager.isStopped)
        Thread.sleep(50)
        assertEquals(0, transports.size)
    }

    @Test
    fun submitExecutesTaskOnExecutorWhenRunning() {
        val manager = newManager()
        manager.start()
        val executed = AtomicBoolean(false)
        val accepted = manager.submit { executed.set(true) }
        assertTrue(accepted)
        assertTrue(awaitTrue { executed.get() })
        manager.stop()
        assertFalse(manager.submit { })
    }

    @Test
    fun cachedReloginExplicitFunctionControlsLifecycleWithoutConnectionEventInterface() {
        val config = managerConfig(tokenExpiresAtUtc = System.currentTimeMillis() + 100L)
        val events = RecordingEvents()
        val stringProvider = FakeStringProvider()
        val manager = newManager(
            config = config,
            events = events,
            stringProvider = stringProvider,
            reloginResult = AuthResult.RateLimited(4L)
        )
        manager.start()
        assertTrue(awaitTrue(timeoutMs = 10000L) {
            stringProvider.calls.any {
                it.first == R.string.status_waiting_reconnect && it.second.firstOrNull() == 30L
            }
        })
        manager.stop()
    }

    @Test
    fun concurrentTransportAccessRemainsConsistentUnderReconnects() {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val manager = newManager(transports = transports, normalDelays = listOf(0L))
        manager.start()
        assertTrue(awaitTrue { transports.isNotEmpty() })
        val stop = AtomicBoolean(false)
        val reader = Thread {
            while (!stop.get()) {
                val generation = manager.currentGeneration()
                manager.currentTransport()
                manager.isCurrentGeneration(generation)
                manager.isConnected
                LockSupport.parkNanos(50_000L)
            }
        }
        reader.start()
        repeat(3) {
            val before = transports.size
            manager.forceReconnect()
            assertTrue(awaitTrue(timeoutMs = 10000L) { transports.size > before })
        }
        stop.set(true)
        reader.join(5000)
        assertFalse(manager.isStopped)
        manager.stop()
        assertTrue(manager.isStopped)
    }
}

