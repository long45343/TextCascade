/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticationActivityServicePathTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("textcascade", Context.MODE_PRIVATE).edit().clear().commit()
        AuthenticationCoordinator.resetForTests()
        AuthenticationDependencies.reset()
    }

    @After
    fun tearDown() {
        AuthenticationCoordinator.resetForTests()
        AuthenticationDependencies.reset()
    }

    @Test
    fun activityCommitFailureAndInvalidationFailureDoesNotStartServiceOrShowLoggedIn() {
        val commits = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = { commits.incrementAndGet(); false }).apply {
            hasSession = true
            websocketUrl = "wss://old.example.com/clipsocket"
            serviceRunning = false
        }
        val starts = AtomicInteger()
        installFakeDependencies(settings, starts) { _, _ ->
            LoginResult(
                normalizedServerUrl = "https://new.example.com",
                websocketUrl = "wss://new.example.com/clipsocket",
                passwordSha3 = "new-sha3",
                hashedPasswordBase64 = "new-key",
                csrfToken = "csrf",
                cookieHeader = "cookie",
                maxSizeBytes = 1024
            )
        }

        val activity = buildActivity()
        fillCredentials(activity)
        clickLogin(activity)
        awaitAuthentication()

        assertEquals(2, commits.get())
        assertEquals(0, starts.get())
        assertFalse(settings.serviceRunning)
        assertTrue(statusText(activity).text.toString().contains(
            activity.getString(R.string.status_session_invalidation_persist_failed)
        ))
        assertTrue(statusText(activity).text.toString().contains(activity.getString(R.string.session_not_logged_in)))
    }

    @Test
    fun activityCommitFailureWithSuccessfulInvalidationClearsSessionAndDoesNotStartService() {
        val commits = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = {
            commits.incrementAndGet()
            if (commits.get() > 1) it.commit() else false
        }).apply {
            hasSession = true
            websocketUrl = "wss://old.example.com/clipsocket"
        }
        val starts = AtomicInteger()
        installFakeDependencies(settings, starts) { _, _ -> fakeResult() }

        val activity = buildActivity()
        fillCredentials(activity)
        clickLogin(activity)
        awaitAuthentication()

        assertEquals(2, commits.get())
        assertFalse(settings.hasSession)
        assertEquals(0, starts.get())
        assertTrue(statusText(activity).text.toString().contains(activity.getString(R.string.session_not_logged_in)))
    }

    @Test
    fun rejectedLoginWithFailedInvalidationShowsPersistenceFailure() {
        val commits = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = { commits.incrementAndGet(); false })
        val starts = AtomicInteger()
        AuthenticationDependencies.settingsStoreFactory = { settings }
        AuthenticationDependencies.startService = { starts.incrementAndGet() }
        AuthenticationDependencies.loginClientFactory = { object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult = throw LoginRejectedException(401, true, "bad credentials")
        } }
        AuthenticationDependencies.deriveCredentials = { _, _ -> DerivedCredentials("sha3", "key") }

        val activity = buildActivity()
        fillCredentials(activity)
        clickLogin(activity)
        awaitAuthentication()

        assertEquals(1, commits.get())
        assertEquals(0, starts.get())
        assertTrue(statusText(activity).text.toString().contains(
            activity.getString(R.string.status_session_invalidation_persist_failed)
        ))
    }

    @Test
    fun successfulActivityLoginStartsServiceOnlyAfterCommit() {
        val commits = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = {
            commits.incrementAndGet()
            it.commit()
        })
        val starts = AtomicInteger()
        installFakeDependencies(settings, starts) { _, _ -> fakeResult() }

        val activity = buildActivity()
        fillCredentials(activity)
        clickLogin(activity)
        awaitAuthentication()

        assertEquals(1, commits.get())
        assertEquals(1, starts.get())
        assertTrue(settings.hasSession)
        assertTrue(settings.serviceRunning)
        assertTrue(statusText(activity).text.toString().contains(activity.getString(R.string.session_logged_in)))
    }

    @Test
    fun serviceSaveReconnectUsesWorkflowAndRestartsAfterCommit() {
        val commits = AtomicInteger()
        val logins = AtomicInteger()
        val restarts = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = {
            commits.incrementAndGet()
            it.commit()
        }).apply {
            serverUrl = "https://example.com"
            username = "user"
            savePassword = true
            savedEncryptedPassword = "saved-password"
            cipherEnabled = false
        }
        AuthenticationDependencies.settingsStoreFactory = { settings }
        AuthenticationDependencies.deriveCredentials = { _, _ -> DerivedCredentials("sha3", "key") }
        AuthenticationDependencies.loginClientFactory = { object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                logins.incrementAndGet()
                return fakeResult()
            }
        } }
        AuthenticationDependencies.restartService = { restarts.incrementAndGet() }

        val service = Robolectric.buildService(ClipForegroundService::class.java).create().get()
        service.onStartCommand(
            Intent().setAction("com.textcascade.SAVE_RECONNECT").putExtra("password", "typed-password"),
            0,
            1
        )
        awaitAuthentication()

        assertEquals(1, logins.get())
        assertEquals(1, commits.get())
        assertEquals(1, restarts.get())
        assertTrue(settings.hasSession)
        service.onDestroy()
    }

    @Test
    fun serviceDestroyCancelsAuthenticationBeforeCommitAndRestart() {
        val loginStarted = CountDownLatch(1)
        val releaseLogin = CountDownLatch(1)
        val commits = AtomicInteger()
        val restarts = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = { commits.incrementAndGet(); true }).apply {
            serverUrl = "https://example.com"
            username = "user"
            cipherEnabled = false
        }
        AuthenticationDependencies.settingsStoreFactory = { settings }
        AuthenticationDependencies.deriveCredentials = { _, _ -> DerivedCredentials("sha3", "key") }
        AuthenticationDependencies.loginClientFactory = { object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                loginStarted.countDown()
                releaseLogin.await(5, TimeUnit.SECONDS)
                return fakeResult()
            }
        } }
        AuthenticationDependencies.restartService = { restarts.incrementAndGet() }

        val service = Robolectric.buildService(ClipForegroundService::class.java).create().get()
        service.onStartCommand(
            Intent().setAction("com.textcascade.SAVE_RECONNECT").putExtra("password", "typed-password"),
            0,
            1
        )
        assertTrue(loginStarted.await(5, TimeUnit.SECONDS))
        service.onDestroy()
        releaseLogin.countDown()
        awaitAuthentication()

        assertEquals(0, commits.get())
        assertEquals(0, restarts.get())
    }

    private fun installFakeDependencies(
        settings: SettingsStore,
        starts: AtomicInteger,
        login: (String, String) -> LoginResult
    ) {
        AuthenticationDependencies.settingsStoreFactory = { settings }
        AuthenticationDependencies.startService = { starts.incrementAndGet() }
        AuthenticationDependencies.deriveCredentials = { _, _ -> DerivedCredentials("sha3", "key") }
        AuthenticationDependencies.loginClientFactory = { object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult = login(serverUrl, username)
        } }
    }

    private fun fakeResult(): LoginResult = LoginResult(
        normalizedServerUrl = "https://new.example.com",
        websocketUrl = "wss://new.example.com/clipsocket",
        passwordSha3 = "new-sha3",
        hashedPasswordBase64 = "new-key",
        csrfToken = "csrf",
        cookieHeader = "cookie",
        maxSizeBytes = 1024
    )

    private fun buildActivity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun form(activity: MainActivity): LinearLayout {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as ViewGroup).getChildAt(0) as LinearLayout
    }

    private fun fillCredentials(activity: MainActivity) {
        val inputs = mutableListOf<EditText>()
        collectViews(form(activity), inputs)
        inputs[1].setText("user")
        inputs[2].setText("password")
    }

    private fun clickLogin(activity: MainActivity) {
        val button = findButton(form(activity), activity.getString(R.string.button_login))
        button.performClick()
    }

    private fun collectViews(view: View, output: MutableList<EditText>) {
        if (view is EditText) output += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectViews(view.getChildAt(index), output)
        }
    }

    private fun findButton(view: View, text: String): Button {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { return findButton(view.getChildAt(index), text) }
            }
        }
        error("Button not found: $text")
    }

    private fun statusText(activity: MainActivity): TextView {
        val field = MainActivity::class.java.getDeclaredField("statusText").apply { isAccessible = true }
        return field.get(activity) as TextView
    }

    private fun awaitAuthentication() {
        assertTrue(AuthenticationCoordinator.awaitIdle())
        ShadowLooper.idleMainLooper()
    }
}
