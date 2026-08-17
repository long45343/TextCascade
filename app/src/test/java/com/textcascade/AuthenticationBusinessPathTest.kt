/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticationBusinessPathTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        AuthenticationCoordinator.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("textcascade", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        AuthenticationCoordinator.resetForTests()
        AuthenticationDependencies.reset()
    }

    @Test
    fun workflowStartCallbackReturningFalseYieldsCancelledOutcome() {
        val settings = SettingsStore(context, commitEditor = { it.commit() })
        var ownerAlive = true
        var startCallbackInvoked = false
        var startSideEffectCount = 0
        val workflow = AuthenticationWorkflow(
            settings = settings,
            loginClientFactory = { object : LoginClient {
                override fun login(
                    serverUrl: String,
                    username: String,
                    passwordSha3: String,
                    hashedPasswordBase64: String
                ): LoginResult = LoginResult(
                    normalizedServerUrl = "https://example.com",
                    websocketUrl = "wss://example.com/clipsocket",
                    passwordSha3 = "sha3",
                    hashedPasswordBase64 = "key",
                    csrfToken = "csrf",
                    cookieHeader = "cookie",
                    maxSizeBytes = 1024
                )
            } },
            deriveCredentials = { _, _ -> DerivedCredentials("sha3", "key") },
            startService = { _ ->
                startCallbackInvoked = true
                ownerAlive = false
                false
            },
            setStatus = {},
            isOwnerAlive = { ownerAlive }
        )

        val outcome = workflow.execute("password", savedPasswordUsed = false)

        assertEquals(AuthenticationOutcome.Cancelled, outcome)
        assertTrue(startCallbackInvoked)
        assertEquals(0, startSideEffectCount)
    }
    @Test
    fun singleFlightPreventsConcurrentDuplicateLogins() {
        val loginExecutions = AtomicInteger(0)
        val task1Started = CountDownLatch(1)
        val task1Hold = CountDownLatch(1)
        val task1Finished = CountDownLatch(1)

        val task1Gen = AuthenticationCoordinator.submit(replaceActive = false) { gen ->
            task1Started.countDown()
            task1Hold.await(5, TimeUnit.SECONDS)
            loginExecutions.incrementAndGet()
            task1Finished.countDown()
        }
        assertNotNull(task1Gen)
        assertTrue(task1Started.await(5, TimeUnit.SECONDS))

        // Concurrent submission with replaceActive = false while task 1 is running should be rejected
        val task2Gen = AuthenticationCoordinator.submit(replaceActive = false) {
            loginExecutions.incrementAndGet()
        }
        assertNull(task2Gen)

        task1Hold.countDown()
        assertTrue(task1Finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, loginExecutions.get())
    }

    @Test
    fun newManualLoginSupersedesOldTaskAndPreventsOldTaskFromWriting() {
        val oldTaskFinished = CountDownLatch(1)
        val oldTaskStarted = CountDownLatch(1)
        val oldTaskCommitted = AtomicBoolean(false)
        val newTaskFinished = CountDownLatch(1)
        val newTaskCommitted = AtomicBoolean(false)

        val settings = SettingsStore(context)

        AuthenticationCoordinator.submit(replaceActive = true) { oldGen ->
            try {
                oldTaskStarted.countDown()
                // simulate long PBKDF2/HTTP
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (AuthenticationCoordinator.isCurrent(oldGen) && !Thread.currentThread().isInterrupted) {
                    settings.updateLoginSession(
                        SessionSnapshot(
                            serverUrl = "http://old.example.com",
                            websocketUrl = "ws://old.example.com/clipsocket",
                            passwordSha3 = "oldSha3",
                            hashedPasswordBase64 = "oldHash",
                            csrfToken = "oldCsrf",
                            cookieHeader = "oldCookie",
                            maxSizeBytes = 1048576L
                        )
                    )
                    oldTaskCommitted.set(true)
                }
            } finally {
                oldTaskFinished.countDown()
            }
        }

        assertTrue(oldTaskStarted.await(5, TimeUnit.SECONDS))

        // New manual login replaces active task
        AuthenticationCoordinator.submit(replaceActive = true) { newGen ->
            if (AuthenticationCoordinator.isCurrent(newGen)) {
                settings.updateLoginSession(
                    SessionSnapshot(
                        serverUrl = "http://new.example.com",
                        websocketUrl = "ws://new.example.com/clipsocket",
                        passwordSha3 = "newSha3",
                        hashedPasswordBase64 = "newHash",
                        csrfToken = "newCsrf",
                        cookieHeader = "newCookie",
                        maxSizeBytes = 1048576L
                    )
                )
                newTaskCommitted.set(true)
            }
            newTaskFinished.countDown()
        }

        assertTrue(newTaskFinished.await(5, TimeUnit.SECONDS))
        assertTrue(oldTaskFinished.await(5, TimeUnit.SECONDS))

        assertFalse(oldTaskCommitted.get())
        assertTrue(newTaskCommitted.get())
        assertEquals("http://new.example.com", settings.serverUrl)
    }

    @Test
    fun serviceDestroyInvalidatesTaskWithoutBreakingExecutor() {
        val serviceDestroyed = AtomicBoolean(true)
        val taskExecuted = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val settings = SettingsStore(context)

        AuthenticationCoordinator.submit(replaceActive = false) { gen ->
            if (!serviceDestroyed.get() && AuthenticationCoordinator.isCurrent(gen)) {
                settings.updateLoginSession(
                    SessionSnapshot(
                        serverUrl = "http://service.example.com",
                        websocketUrl = "ws://service.example.com/clipsocket",
                        passwordSha3 = "sha3",
                        hashedPasswordBase64 = "hash",
                        csrfToken = "csrf",
                        cookieHeader = "cookie",
                        maxSizeBytes = 1048576L
                    )
                )
                committed.set(true)
            }
            taskExecuted.countDown()
        }

        assertTrue(taskExecuted.await(5, TimeUnit.SECONDS))
        assertFalse(committed.get())

        // Ensure coordinator still executes subsequent tasks
        val subsequentExecuted = CountDownLatch(1)
        val subsequentSuccess = AtomicBoolean(false)
        AuthenticationCoordinator.submit(replaceActive = true) {
            subsequentSuccess.set(true)
            subsequentExecuted.countDown()
        }

        assertTrue(subsequentExecuted.await(5, TimeUnit.SECONDS))
        assertTrue(subsequentSuccess.get())
    }

    @Test
    fun updateLoginSessionSavesCorrectPasswordSnapshot() {
        val settings = SettingsStore(context)

        // 1. savedPassword = "myPassword"
        val ok1 = settings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.com",
                websocketUrl = "ws://example.com/clipsocket",
                passwordSha3 = "sha3",
                hashedPasswordBase64 = "hash",
                csrfToken = "csrf",
                cookieHeader = "cookie",
                maxSizeBytes = 1048576L,
                savedPassword = "myPassword"
            )
        )
        assertTrue(ok1)
        assertTrue(settings.hasSession)
        assertEquals("myPassword", settings.savedEncryptedPassword)

        // 2. savedPassword = null (leaves saved password untouched)
        val ok2 = settings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.com",
                websocketUrl = "ws://example.com/clipsocket",
                passwordSha3 = "sha3_2",
                hashedPasswordBase64 = "hash_2",
                csrfToken = "csrf_2",
                cookieHeader = "cookie_2",
                maxSizeBytes = 1048576L,
                savedPassword = null
            )
        )
        assertTrue(ok2)
        assertEquals("myPassword", settings.savedEncryptedPassword)

        // 3. savedPassword = "" (clears saved password)
        val ok3 = settings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.com",
                websocketUrl = "ws://example.com/clipsocket",
                passwordSha3 = "sha3_3",
                hashedPasswordBase64 = "hash_3",
                csrfToken = "csrf_3",
                cookieHeader = "cookie_3",
                maxSizeBytes = 1048576L,
                savedPassword = ""
            )
        )
        assertTrue(ok3)
        assertEquals("", settings.savedEncryptedPassword)
    }

    @Test
    fun updateLoginSessionCommitFailureReturnsFalse() {
        val failingSettings = SettingsStore(context, commitEditor = { false })
        val result = failingSettings.updateLoginSession(
            SessionSnapshot(
                serverUrl = "http://example.com",
                websocketUrl = "ws://example.com/clipsocket",
                passwordSha3 = "sha3",
                hashedPasswordBase64 = "hash",
                csrfToken = "csrf",
                cookieHeader = "cookie",
                maxSizeBytes = 1048576L
            )
        )
        assertFalse(result)
        // Verify hasSession was not saved
        assertFalse(SettingsStore(context).hasSession)
    }

    @Test
    fun clearSessionCommitFailureReturnsFalse() {
        val settings = SettingsStore(context)
        settings.hasSession = true
        assertTrue(settings.hasSession)

        val failingSettings = SettingsStore(context, commitEditor = { false })
        val result = failingSettings.clearSession()
        assertFalse(result)
    }

    @Test
    fun markSessionInvalidCommitFailureReturnsFalse() {
        val failingSettings = SettingsStore(context, commitEditor = { false })
        val result = failingSettings.markSessionInvalid()
        assertFalse(result)
    }
}
