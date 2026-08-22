/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ClipConfigTest {

    @Test
    fun websocketUrlDerivation() {
        assertEquals(
            "wss://your-server:8443/api/v1/sync",
            ClipConfig.websocketUrlFromServerUrl("https://your-server:8443")
        )
        assertEquals(
            "wss://srv.example/api/v1/sync",
            ClipConfig.websocketUrlFromServerUrl("https://srv.example/")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun websocketUrlRejectsHttp() {
        ClipConfig.websocketUrlFromServerUrl("http://srv.example")
    }

    @Test
    fun defaultsMatchSpec() {
        assertEquals("https://your-server:8443", ClipConfig.DEFAULT_SERVER_URL)
        assertEquals(664937, ClipConfig.DEFAULT_HASH_ROUNDS)
        assertEquals(512_000L, ClipConfig.DEFAULT_MAX_TEXT_BYTES)
        assertEquals("textcascade.v1", Protocol.SUBPROTOCOL)
        assertEquals("/api/v1/sync", Protocol.SYNC_PATH)
        assertEquals(1, Protocol.SUPPORTED_PROTOCOL_VERSION)
    }

    @Test
    fun clampAndSanitizeLimits() {
        assertEquals(1L, ClipConfig.clampClipboardLimit(0L))
        assertEquals(2L * 1024 * 1024, ClipConfig.clampClipboardLimit(999_999_999L))
        assertEquals(ClipConfig.DEFAULT_MAX_TEXT_BYTES, ClipConfig.sanitizeStoredClipboardLimit(0L))
        assertEquals(ClipConfig.MAX_CLIPBOARD_BYTES, ClipConfig.sanitizeStoredClipboardLimit(Long.MAX_VALUE))
        assertEquals(1234L, ClipConfig.sanitizeStoredClipboardLimit(1234L))
    }

    @Test
    fun snapshotFromSettingsReflectsStoredFields() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.serverUrl = "https://srv.example"
        store.username = "user"
        store.token = "tok"
        store.tokenExpiresAtUtc = 123L
        store.maxTextBytes = 100_000L
        store.helloTimeoutSeconds = 5
        store.heartbeatIntervalSeconds = 15
        store.heartbeatTimeoutSeconds = 45
        store.lastServerVersion = 42L
        store.hashRounds = 1000
        store.salt = "s"
        store.cipherEnabled = false
        store.localMaxClipboardBytes = 50_000L
        store.trustAllCerts = true
        store.pinnedCertSha256 = "AA:BB:CC:DD"

        val config = ClipConfig.default(context)
        assertEquals("https://srv.example", config.session.serverUrl)
        assertEquals("wss://srv.example/api/v1/sync", config.websocketUrl)
        assertEquals("tok", config.session.token)
        assertEquals(123L, config.session.tokenExpiresAtUtc)
        assertEquals("user", config.session.username)
        assertEquals(100_000L, config.userPrefs.maxTextBytes)
        assertEquals(5, config.userPrefs.helloTimeoutSeconds)
        assertEquals(15, config.userPrefs.heartbeatIntervalSeconds)
        assertEquals(45, config.userPrefs.heartbeatTimeoutSeconds)
        assertEquals(42L, config.userPrefs.lastServerVersion)
        assertEquals(1000, config.cryptoMaterial.hashRounds)
        assertEquals("s", config.cryptoMaterial.salt)
        assertEquals(false, config.cryptoMaterial.cipherEnabled)
        assertEquals(50_000L, config.userPrefs.localMaxClipboardBytes)
        assertEquals(true, config.cryptoMaterial.trustAllCerts)
        assertEquals("AA:BB:CC:DD", config.cryptoMaterial.pinnedCertSha256)
        // clientId UUID v4
        val clientId = config.session.clientId
        assertEquals(5, clientId.split("-").size)
        assertEquals(36, clientId.length)
        assertTrue(clientId.substring(14, 15) == "4")
    }
}

