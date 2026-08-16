package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CachedReloginRunnerTest {

    @Test
    fun usesCachedDerivedCredentialsWithoutSavedPassword() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "cached-sha3"
            hashedPasswordBase64 = "cached-aes-key"
            cipherEnabled = true
            savedEncryptedPassword = "secret-password"
        }

        var capturedServer = ""
        var capturedUser = ""
        var capturedSha3 = ""
        var capturedKey = ""

        val fakeClient = object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                capturedServer = serverUrl
                capturedUser = username
                capturedSha3 = passwordSha3
                capturedKey = hashedPasswordBase64
                return LoginResult(
                    normalizedServerUrl = serverUrl,
                    websocketUrl = "ws://127.0.0.1:8080/ws",
                    passwordSha3 = passwordSha3,
                    hashedPasswordBase64 = hashedPasswordBase64,
                    csrfToken = "new-csrf",
                    cookieHeader = "new-cookie",
                    maxSizeBytes = 2048
                )
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.Success)
        assertEquals("http://127.0.0.1:8080", capturedServer)
        assertEquals("user", capturedUser)
        assertEquals("cached-sha3", capturedSha3)
        assertEquals("cached-aes-key", capturedKey)
        assertEquals("secret-password", settings.savedEncryptedPassword)
    }

    @Test
    fun successUpdatesOnlySessionFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "original-sha3"
            hashedPasswordBase64 = "original-key"
            cipherEnabled = true
        }

        val fakeClient = object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                return LoginResult(
                    normalizedServerUrl = "http://127.0.0.1:8080",
                    websocketUrl = "ws://127.0.0.1:8080/ws",
                    passwordSha3 = "different-sha3",
                    hashedPasswordBase64 = "different-key",
                    csrfToken = "updated-csrf",
                    cookieHeader = "updated-cookie",
                    maxSizeBytes = 4096
                )
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.Success)
        assertEquals("updated-csrf", settings.csrfToken)
        assertEquals("updated-cookie", settings.cookieHeader)
        assertEquals(4096L, settings.maxSizeBytes)
        assertEquals("original-sha3", settings.passwordSha3)
        assertEquals("original-key", settings.hashedPasswordBase64)
    }

    @Test
    fun cipherDisabledAllowsBlankKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "cached-sha3"
            hashedPasswordBase64 = ""
            cipherEnabled = false
        }

        var called = false
        val fakeClient = object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                called = true
                return LoginResult(serverUrl, "ws", passwordSha3, hashedPasswordBase64, "csrf", "cookie", 100)
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.Success)
        assertTrue(called)
    }

    @Test
    fun cipherEnabledRequiresKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "cached-sha3"
            hashedPasswordBase64 = ""
            cipherEnabled = true
        }

        var called = false
        val fakeClient = object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                called = true
                return LoginResult(serverUrl, "ws", passwordSha3, hashedPasswordBase64, "csrf", "cookie", 100)
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.NoCredentials)
        assertTrue(!called)
    }

    @Test
    fun unauthorizedMapsToAuthFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "sha3"
            cipherEnabled = false
        }

        val fakeClient = object : LoginClient {
            override fun login(serverUrl: String, username: String, passwordSha3: String, hashedPasswordBase64: String): LoginResult {
                throw LoginRejectedException(401, badCredentials = true, message = "Unauthorized")
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.AuthFailure)
    }

    @Test
    fun badCredentialsMapsToAuthFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "sha3"
            cipherEnabled = false
        }

        val fakeClient = object : LoginClient {
            override fun login(serverUrl: String, username: String, passwordSha3: String, hashedPasswordBase64: String): LoginResult {
                throw LoginRejectedException(200, badCredentials = true, message = "Bad credentials")
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.AuthFailure)
    }

    @Test
    fun serverErrorMapsToTransientFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "sha3"
            cipherEnabled = false
        }

        val fakeClient = object : LoginClient {
            override fun login(serverUrl: String, username: String, passwordSha3: String, hashedPasswordBase64: String): LoginResult {
                throw LoginRequestFailedException(500, "Internal Server Error")
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.TransientFailure)
    }

    @Test
    fun networkErrorMapsToTransientFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context).apply {
            serverUrl = "http://127.0.0.1:8080"
            username = "user"
            passwordSha3 = "sha3"
            cipherEnabled = false
        }

        val fakeClient = object : LoginClient {
            override fun login(serverUrl: String, username: String, passwordSha3: String, hashedPasswordBase64: String): LoginResult {
                throw IOException("connection timed out")
            }
        }

        val runner = CachedReloginRunner(settings, fakeClient)
        val result = runner.execute()

        assertTrue(result is CachedReloginResult.TransientFailure)
    }
}
