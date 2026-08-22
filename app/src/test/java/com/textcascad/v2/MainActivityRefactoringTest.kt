/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityRefactoringTest {

    @Test
    fun uiBindingPopulatesAndValidatesSettings() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://my-server.example:8443"
        store.username = "testuser"
        store.hashRounds = 12345
        store.salt = "custom_salt"
        store.localMaxClipboardBytes = 65536L
        store.pinnedCertSha256 = "AA:BB:CC:DD"
        store.cipherEnabled = true
        store.savePassword = true
        store.trustAllCerts = true

        val binding = MainActivityUiBinding.inflate(activity, "2.2.0") {}
        binding.loadSettings(store)

        assertEquals("https://my-server.example:8443", binding.serverUrlInput.text.toString())
        assertEquals("testuser", binding.usernameInput.text.toString())
        assertEquals("12345", binding.hashRoundsInput.text.toString())
        assertEquals("custom_salt", binding.saltInput.text.toString())
        assertEquals("65536", binding.localLimitInput.text.toString())
        assertEquals("AA:BB:CC:DD", binding.pinnedCertInput.text.toString())
        assertTrue(binding.cipherCheck.isChecked)
        assertTrue(binding.savePasswordCheck.isChecked)
        assertTrue(binding.trustAllCertsCheck.isChecked)

        // Invalid URL validation
        binding.serverUrlInput.setText("http://insecure.example")
        var errorOccurred = false
        val valid = binding.saveEditableSettings(store) {
            errorOccurred = true
        }
        assertFalse(valid)
        assertTrue(errorOccurred)

        // Valid URL save with updated pinned certificate
        binding.serverUrlInput.setText("https://valid.example:8443/")
        binding.pinnedCertInput.setText("11:22:33:44")
        val saveResult = binding.saveEditableSettings(store) {}
        assertTrue(saveResult)
        assertEquals("https://valid.example:8443", store.serverUrl)
        assertEquals("11:22:33:44", store.pinnedCertSha256)
    }

    @Test
    fun authControllerHandlesLoginAndLogout() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://valid.example:8443"
        store.username = "user"
        store.savePassword = true
        store.savedEncryptedPassword = "encrypted-pass"
        store.updateLoginSession(
            SessionSnapshot(
                serverUrl = "https://valid.example:8443",
                token = "valid-token",
                tokenExpiresAtUtc = System.currentTimeMillis() + 60_000L,
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60
            )
        )

        var serviceStopped = false
        val deps = AuthenticationDependencies(
            settingsStoreFactory = { store },
            startService = { },
            stopService = { serviceStopped = true }
        )
        val binding = MainActivityUiBinding.inflate(activity, "2.2.0") {}
        binding.loadSettings(store)
        val controller = MainActivityAuthController(
            activity = activity,
            settingsStore = store,
            uiBinding = binding,
            dependencies = deps
        )

        controller.logout()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(serviceStopped)
    }
}