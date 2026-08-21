/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginOutcomeReducerTest {

    private fun currentState(
        isLoading: Boolean = true,
        sessionPersistenceFailed: Boolean = true,
        serviceRunningUiOverride: Boolean? = null
    ) = LoginViewState(
        isLoading = isLoading,
        sessionPersistenceFailed = sessionPersistenceFailed,
        serviceRunningUiOverride = serviceRunningUiOverride,
        message = "busy"
    )

    private fun messageResolver(): Pair<MutableList<LoginOutcomeMessage>, (LoginOutcomeMessage) -> String> {
        val captured = mutableListOf<LoginOutcomeMessage>()
        return captured to { msg ->
            captured.add(msg)
            "resolved:${captured.size}"
        }
    }

    private fun successResult() = LoginResult(
        normalizedServerUrl = "https://srv.example",
        websocketUrl = "wss://srv.example/api/v1/sync",
        token = "tok",
        tokenExpiresAtUtc = 42L,
        protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
        maxTextBytes = 512_000L,
        helloTimeoutSeconds = 10,
        heartbeatIntervalSeconds = 20,
        heartbeatTimeoutSeconds = 60
    )

    @Test
    fun cancelledReturnsCurrentUnchanged() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Cancelled, current, true, resolve
        )

        assertSame(current, result)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun staleThreadReturnsCurrentUnchanged() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Failed(Exception("late")), current, false, resolve
        )

        assertSame(current, result)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun missingPasswordEndsBusyWithRequiredFieldsMessage() {
        val current = currentState(sessionPersistenceFailed = false, serviceRunningUiOverride = true)
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.MissingPassword, current, true, resolve
        )

        assertFalse(result.isLoading)
        assertEquals(listOf<LoginOutcomeMessage>(LoginOutcomeMessage.MissingPassword), captured)
        assertEquals("resolved:1", result.message)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(true, result.serviceRunningUiOverride)
        assertFalse(result.clearPasswordInput)
        assertFalse(result.reloadSettings)
    }

    @Test
    fun successClearsPersistenceFailureAndEnablesServiceOverride() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Success(successResult()), current, true, resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(true, result.serviceRunningUiOverride)
        assertTrue(result.clearPasswordInput)
        assertTrue(result.reloadSettings)
        assertEquals(listOf<LoginOutcomeMessage>(LoginOutcomeMessage.Connecting), captured)
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun protocolUnsupportedDisablesOverrideWithVersionMessage() {
        val current = currentState(serviceRunningUiOverride = true)
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.ProtocolUnsupported(serverVersion = 2), current, true, resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.ProtocolUnsupported(2)),
            captured
        )
        assertEquals("resolved:1", result.message)
        assertFalse(result.clearPasswordInput)
        assertFalse(result.reloadSettings)
    }

    @Test
    fun persistenceFailureWithPersistedInvalidationReportsErrorDetail() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.PersistenceFailure(
                error = IllegalStateException("persist boom"),
                invalidationPersisted = true
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginFailed("persist boom")),
            captured
        )
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun persistenceFailureWithoutPersistedInvalidationFlagsSessionFailure() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.PersistenceFailure(
                error = IllegalStateException("persist boom"),
                invalidationPersisted = false
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertTrue(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.SessionPersistenceFailed),
            captured
        )
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun rejectedRateLimitedTakesPriorityOverInvalidationState() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Rejected(
                error = LoginRateLimitedException(statusCode = 429, retryAfterSeconds = null),
                invalidationPersisted = true
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginRateLimited), captured)
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun rejectedWithoutPersistedInvalidationFlagsSessionFailure() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Rejected(
                error = LoginRejectedException(statusCode = 401, message = "bad creds"),
                invalidationPersisted = false
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertTrue(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.SessionPersistenceFailed),
            captured
        )
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun rejectedLoginRejectedWithPersistedInvalidationMapsToInvalidCredentials() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Rejected(
                error = LoginRejectedException(statusCode = 401, message = "bad creds"),
                invalidationPersisted = true
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(listOf<LoginOutcomeMessage>(LoginOutcomeMessage.InvalidCredentials), captured)
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun rejectedOtherApiErrorWithPersistedInvalidationMapsToLoginFailed() {
        val current = currentState()
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Rejected(
                error = LoginRequestFailedException(statusCode = 500, message = "server error"),
                invalidationPersisted = true
            ),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginFailed("server error")),
            captured
        )
        assertEquals("resolved:1", result.message)
    }

    @Test
    fun failedOutcomeClearsOverrideWithLoginFailedMessage() {
        val current = currentState(serviceRunningUiOverride = true)
        val (captured, resolve) = messageResolver()

        val result = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Failed(error = java.io.IOException("network down")),
            current,
            true,
            resolve
        )

        assertFalse(result.isLoading)
        assertFalse(result.sessionPersistenceFailed)
        assertEquals(false, result.serviceRunningUiOverride)
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginFailed("network down")),
            captured
        )
        assertEquals("resolved:1", result.message)
        assertFalse(result.clearPasswordInput)
        assertFalse(result.reloadSettings)
    }

    @Test
    fun loginFailedDetailFallsBackToSimpleClassNameWhenMessageNull() {
        val (capturedFailed, resolveFailed) = messageResolver()
        val failedResult = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.Failed(error = RuntimeException()),
            currentState(),
            true,
            resolveFailed
        )
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginFailed("RuntimeException")),
            capturedFailed
        )
        assertEquals("resolved:1", failedResult.message)

        val (capturedRejected, resolveRejected) = messageResolver()
        val rejectedResult = LoginOutcomeReducer.reduce(
            AuthenticationOutcome.PersistenceFailure(
                error = RuntimeException(),
                invalidationPersisted = true
            ),
            currentState(),
            true,
            resolveRejected
        )
        assertEquals(
            listOf<LoginOutcomeMessage>(LoginOutcomeMessage.LoginFailed("RuntimeException")),
            capturedRejected
        )
        assertEquals("resolved:1", rejectedResult.message)
    }
}
