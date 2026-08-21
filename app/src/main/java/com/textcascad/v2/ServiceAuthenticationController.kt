package com.textcascad.v2

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class ServiceAuthenticationController(
    private val settings: SettingsStore,
    private val dependencies: AuthenticationDependencies,
    private val authGeneration: AtomicLong,
    private val serviceDestroyed: AtomicBoolean,
    private val autoLoginQueued: AtomicBoolean,
    private val strings: StringProvider,
    private val showStatus: (String) -> Unit,
    private val finishFailure: (String) -> Unit,
    private val restart: () -> Unit
) {
    fun autoLogin() {
        val statusMessage = strings(R.string.status_auto_login)
        settings.statusMessage = statusMessage
        showStatus(statusMessage)
        if (!autoLoginQueued.compareAndSet(false, true)) return
        enqueueRelogin(
            typedPassword = "",
            automatic = true,
            taskGeneration = authGeneration.incrementAndGet()
        )
    }

    fun reloginWithCurrentConfig(typedPassword: String) {
        showStatus(strings(R.string.status_connecting))
        enqueueRelogin(
            typedPassword = typedPassword,
            automatic = false,
            taskGeneration = authGeneration.incrementAndGet()
        )
    }

    private fun enqueueRelogin(typedPassword: String, automatic: Boolean, taskGeneration: Long) {
        val submitted = AuthenticationCoordinator.submit(replaceActive = !automatic) authTask@{ requestGeneration ->
            try {
                if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) return@authTask
                val password = typedPassword.ifBlank {
                    if (settings.savePassword) settings.savedEncryptedPassword else ""
                }
                val outcome = AuthenticationWorkflow(
                    settings = settings,
                    loginClientFactory = dependencies.loginClientFactory,
                    deriveCredentials = { value, _ -> deriveCredentials(settings, value) },
                    startService = { _ ->
                        if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) {
                            false
                        } else {
                            restart()
                            true
                        }
                    },
                    setStatus = {},
                    isOwnerAlive = { isAuthTaskCurrent(taskGeneration, requestGeneration) }
                ).execute(
                    password = password,
                    savedPasswordUsed = typedPassword.isBlank(),
                    savedPassword = if (!settings.savePassword) "" else typedPassword.takeIf { it.isNotBlank() }
                )
                if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) return@authTask
                handleOutcome(outcome, automatic, taskGeneration, requestGeneration)
            } finally {
                if (automatic) autoLoginQueued.set(false)
            }
        }
        if (submitted == null && automatic) autoLoginQueued.set(false)
    }

    private fun handleOutcome(
        outcome: AuthenticationOutcome,
        automatic: Boolean,
        taskGeneration: Long,
        requestGeneration: Long
    ) {
        when (outcome) {
            AuthenticationOutcome.Cancelled -> Unit
            AuthenticationOutcome.MissingPassword -> finishAuthFailure(
                taskGeneration,
                requestGeneration,
                if (automatic) {
                    strings(R.string.status_auto_login_failed, "No saved password")
                } else {
                    strings(R.string.status_login_required_fields)
                }
            )
            is AuthenticationOutcome.Success -> Unit
            is AuthenticationOutcome.ProtocolUnsupported -> finishAuthFailure(
                taskGeneration,
                requestGeneration,
                strings(
                    R.string.status_protocol_unsupported,
                    outcome.serverVersion,
                    Protocol.SUPPORTED_PROTOCOL_VERSION
                )
            )
            is AuthenticationOutcome.PersistenceFailure -> finishAuthFailure(
                taskGeneration,
                requestGeneration,
                if (outcome.invalidationPersisted) {
                    authenticationErrorMessage(outcome.error.message.orEmpty(), automatic)
                } else {
                    strings(R.string.status_session_invalidation_persist_failed)
                }
            )
            is AuthenticationOutcome.Rejected -> finishAuthFailure(
                taskGeneration,
                requestGeneration,
                if (outcome.error is LoginRateLimitedException) {
                    strings(R.string.status_login_rate_limited)
                } else if (outcome.invalidationPersisted) {
                    authenticationErrorMessage(outcome.error.message.orEmpty(), automatic)
                } else {
                    strings(R.string.status_session_invalidation_persist_failed)
                }
            )
            is AuthenticationOutcome.Failed -> finishAuthFailure(
                taskGeneration,
                requestGeneration,
                authenticationErrorMessage(
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                    automatic
                )
            )
        }
    }

    private fun authenticationErrorMessage(detail: String, automatic: Boolean): String =
        if (automatic) {
            strings(R.string.status_auto_login_failed, detail)
        } else {
            strings(R.string.status_login_failed, detail)
        }

    private fun isAuthTaskCurrent(taskGeneration: Long, requestGeneration: Long): Boolean =
        !serviceDestroyed.get() &&
            taskGeneration == authGeneration.get() &&
            AuthenticationCoordinator.isCurrent(requestGeneration)

    private fun finishAuthFailure(taskGeneration: Long, requestGeneration: Long, message: String) {
        if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) return
        finishFailure(message)
    }
}
