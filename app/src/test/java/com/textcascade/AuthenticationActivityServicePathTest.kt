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
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun activityDestroyAfterCommitPreventsStartServiceSideEffects() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commits = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = {
            commits.incrementAndGet()
            it.commit()
            commitEntered.countDown()
            releaseCommit.await(5, TimeUnit.SECONDS)
            true
        })
        val starts = AtomicInteger()
        installFakeDependencies(settings, starts) { _, _ -> fakeResult() }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.get()
        fillCredentials(activity)
        clickLogin(activity)
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
        controller.pause().destroy()
        releaseCommit.countDown()
        awaitAuthentication()

        assertEquals(1, commits.get())
        assertEquals(0, starts.get())
        assertFalse(settings.serviceRunning)
        assertTrue(
            statusText(activity).text.toString().contains(activity.getString(R.string.session_not_logged_in))
        )
    }

    @Test
    fun serviceDestroyAfterCommitPreventsRestart() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commits = AtomicInteger()
        val logins = AtomicInteger()
        val restarts = AtomicInteger()
        val settings = SettingsStore(context, commitEditor = {
            commits.incrementAndGet()
            it.commit()
            commitEntered.countDown()
            releaseCommit.await(5, TimeUnit.SECONDS)
            true
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
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
        service.onDestroy()
        releaseCommit.countDown()
        awaitAuthentication()

        assertEquals(1, commits.get())
        assertEquals(1, logins.get())
        assertEquals(0, restarts.get())
        assertFalse(settings.serviceRunning)
    }

    @Test
    fun concurrentActivityAndServiceAuthenticationSingleFlightsToAcceptedActivityResult() {
        val serviceDerivationEntered = CountDownLatch(1)
        val releaseFirstDerivation = CountDownLatch(1)
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val firstDerivationEntered = AtomicBoolean(false)
        val derivationCount = AtomicInteger()
        val loginCount = AtomicInteger()
        val updateLoginSessionCount = AtomicInteger()
        val startCount = AtomicInteger()
        val restartCount = AtomicInteger()

        val settings = SettingsStore(context, commitEditor = {
            updateLoginSessionCount.incrementAndGet()
            it.commit()
            commitEntered.countDown()
            releaseCommit.await(5, TimeUnit.SECONDS)
            true
        }).apply {
            serverUrl = "https://activity.example.com"
            username = "activity-user"
            cipherEnabled = false
        }
        AuthenticationDependencies.settingsStoreFactory = { settings }
        AuthenticationDependencies.startService = { startCount.incrementAndGet() }
        AuthenticationDependencies.restartService = { restartCount.incrementAndGet() }
        AuthenticationDependencies.deriveCredentials = { _, password ->
            if (firstDerivationEntered.compareAndSet(false, true)) {
                serviceDerivationEntered.countDown()
                try {
                    releaseFirstDerivation.await(5, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                }
            }
            derivationCount.incrementAndGet()
            DerivedCredentials("sha3-" + password, "key-" + password)
        }
        AuthenticationDependencies.loginClientFactory = { object : LoginClient {
            override fun login(
                serverUrl: String,
                username: String,
                passwordSha3: String,
                hashedPasswordBase64: String
            ): LoginResult {
                loginCount.incrementAndGet()
                if (passwordSha3 == "sha3-activity-password") {
                    return fakeResult(
                        server = "https://activity.example.com",
                        websocket = "wss://activity.example.com/clipsocket"
                    )
                }
                return fakeResult(
                    server = "https://service-ignored.example.com",
                    websocket = "wss://service-ignored.example.com/clipsocket"
                )
            }
        } }

        val service = Robolectric.buildService(ClipForegroundService::class.java).create().get()
        service.onStartCommand(
            Intent().setAction("com.textcascade.SAVE_RECONNECT").putExtra("password", "service-password"),
            0,
            1
        )
        assertTrue(serviceDerivationEntered.await(5, TimeUnit.SECONDS))

        val activity = buildActivity()
        fillCredentials(activity)
        val inputs = mutableListOf<EditText>()
        collectViews(form(activity), inputs)
        inputs[2].setText("activity-password")
        clickLogin(activity)

        releaseFirstDerivation.countDown()
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
        releaseCommit.countDown()
        awaitAuthentication()
        ShadowLooper.idleMainLooper()
        service.onDestroy()

        assertEquals(1, derivationCount.get())
        assertEquals(1, loginCount.get())
        assertEquals(1, updateLoginSessionCount.get())
        assertTrue(startCount.get() + restartCount.get() <= 1)
        assertEquals(1, startCount.get())
        assertEquals(0, restartCount.get())
        assertTrue(settings.hasSession)
        assertEquals("https://activity.example.com", settings.serverUrl)
        assertEquals("wss://activity.example.com/clipsocket", settings.websocketUrl)
        assertTrue(statusText(activity).text.toString().contains(activity.getString(R.string.session_logged_in)))
    }

    private fun fakeResult(
        server: String = "https://new.example.com",
        websocket: String = "wss://new.example.com/clipsocket"
    ): LoginResult = LoginResult(
        normalizedServerUrl = server,
        websocketUrl = websocket,
        passwordSha3 = "new-sha3",
        hashedPasswordBase64 = "new-key",
        csrfToken = "csrf",
        cookieHeader = "cookie",
        maxSizeBytes = 1024
    )

    private fun awaitAuthentication() {
        assertTrue(AuthenticationCoordinator.awaitIdle())
        ShadowLooper.idleMainLooper()
    }
}
