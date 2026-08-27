/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascad.v2

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ClipForegroundService : Service(), TextSyncEngine.Callbacks, StringProvider {
    private lateinit var settings: SettingsStore
    private var engine: TextSyncEngine? = null
    private var sources: ClipboardSources? = null
    private var userPresentReceiver: BroadcastReceiver? = null
    // 会话失效重登只尝试一次
    private val sessionRecoveryAttempted = AtomicBoolean(false)
    private val authGeneration = AtomicLong(0L)
    private val serviceDestroyed = AtomicBoolean(false)
    private val autoLoginQueued = AtomicBoolean(false)
    private lateinit var authDependencies: AuthenticationDependencies
    private lateinit var notifications: NotificationController
    private lateinit var authentication: ServiceAuthenticationController

    private val activationListener: (XposedActivationState) -> Unit = { state ->
        onActivationStateChanged(state)
    }

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed.set(false)
        authDependencies = AuthenticationDependencies()
        settings = authDependencies.settingsStoreFactory(this)
        notifications = NotificationController(this)
        notifications.createChannels()
        authentication = ServiceAuthenticationController(
            settings = settings,
            dependencies = authDependencies,
            authGeneration = authGeneration,
            serviceDestroyed = serviceDestroyed,
            autoLoginQueued = autoLoginQueued,
            strings = this,
            showStatus = { message ->
                settings.statusMessage = message
                settings.connectionStatusMessage = message
                val bgStatusText = currentBackgroundStatusText()
                notifications.update(message, bgStatusText)
                notifications.startForeground(message, bgStatusText, this)
            },
            finishFailure = ::finishAuthFailure,
            restart = { restartSelfForFreshConfig() }
        )
        registerUserPresentReceiver()
        TextCascadeApplication.addActivationListener(activationListener)
        evaluateBackgroundStatus(TextCascadeApplication.activationState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialConn = settings.connectionStatusMessage.ifBlank { settings.statusMessage }.ifBlank { getString(R.string.status_connecting) }
        val bgStatusText = currentBackgroundStatusText()
        notifications.startForeground(initialConn, bgStatusText, this)
        when (intent?.action) {
            ClipServiceController.ACTION_STOP -> {
                settings.serviceRunning = false
                stopForegroundAndService()
                return START_NOT_STICKY
            }
            ClipServiceController.ACTION_RECONNECT -> {
                engine?.forceReconnect()
            }
            ClipServiceController.ACTION_RESUME_RECONNECT -> {
                val currentEngine = engine
                when {
                    currentEngine == null -> startSync()
                    currentEngine.isStopped -> startSync()
                    else -> currentEngine.reconnectAfterUserPresent()
                }
            }
            ClipServiceController.ACTION_SAVE_RECONNECT -> {
                engine?.stop()
                sources?.stop()
                engine = null
                sources = null
                val password = intent.getStringExtra(ClipServiceController.EXTRA_PASSWORD).orEmpty()
                if (password.isNotBlank() || (settings.savePassword && settings.savedEncryptedPassword.isNotBlank())) {
                    reloginWithCurrentConfig(password)
                } else {
                    val msg = getString(R.string.status_login_required_fields)
                    settings.statusMessage = msg
                    settings.connectionStatusMessage = msg
                    notifications.update(msg, currentBackgroundStatusText())
                    stopSelf()
                }
            }
            ClipServiceController.ACTION_SUBMIT_TEXT -> {
                val text = intent.getStringExtra(ClipServiceController.EXTRA_TEXT).orEmpty()
                val source = intent.getStringExtra(ClipServiceController.EXTRA_SOURCE).orEmpty()
                if (engine == null) startSync()
                engine?.sendLocalText(text, source)
            }
            ClipServiceController.ACTION_LOGCAT_ENABLED -> {
                val enabled = intent.getBooleanExtra(ClipServiceController.EXTRA_LOGCAT_ENABLED, true)
                if (enabled) {
                    if (engine == null && settings.hasSession) startSync()
                    sources?.setLogcatEnabled(true)
                } else {
                    sources?.setLogcatEnabled(false)
                }
            }
            else -> startSync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed.set(true)
        authGeneration.incrementAndGet()
        TextCascadeApplication.removeActivationListener(activationListener)
        unregisterUserPresentReceiver()
        sources?.stopNonBlocking()
        sources = null
        engine?.stop()
        engine = null
        settings.serviceRunning = false
        super.onDestroy()
    }

    private fun stopForegroundAndService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun invalidateSessionSafely(): Boolean {
        val committed = settings.markSessionInvalid(settings.appPreferences.setSessionActive(false))
        if (!committed) {
            engine?.stop()
            sources?.stop()
            engine = null
            sources = null
            settings.serviceRunning = false
            val msg = getString(R.string.status_session_invalidation_persist_failed)
            settings.statusMessage = msg
            settings.connectionStatusMessage = msg
            notifications.update(msg, currentBackgroundStatusText())
            stopForegroundAndService()
            return false
        }
        return true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStatus(message: String) {
        onStatus(message, disconnected = false, subText = null)
    }

    override fun onSessionExpired() {
        if (!invalidateSessionSafely()) return
        if (sessionRecoveryAttempted.compareAndSet(false, true)) {
            // 有保存密码时静默重登一次；否则停止服务并提示重新登录
            if (settings.savePassword && settings.savedEncryptedPassword.isNotBlank()) {
                authentication.autoLogin()
            } else {
                engine?.stop()
                sources?.stop()
                engine = null
                sources = null
                settings.serviceRunning = false
                onStatus(getString(R.string.status_session_expired), disconnected = false)
                stopForegroundAndService()
            }
        }
    }

    private fun onStatus(message: String, disconnected: Boolean, subText: String? = null) {
        settings.statusMessage = message
        settings.connectionStatusMessage = message
        val bgStatusText = currentBackgroundStatusText()
        notifications.update(message, bgStatusText, subText)
        if (settings.websocketStatusNotification && disconnected) {
            notifications.showStatus(getString(R.string.notification_websocket_lost))
        } else if (!disconnected) {
            notifications.dismissStatus()
        }
    }

    private fun onActivationStateChanged(state: XposedActivationState) {
        evaluateBackgroundStatus(state)
        val connMsg = settings.connectionStatusMessage.ifBlank { settings.statusMessage }.ifBlank {
            getString(R.string.status_idle)
        }
        notifications.update(connMsg, currentBackgroundStatusText())
    }

    private fun evaluateBackgroundStatus(state: XposedActivationState) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            settings.backgroundStatus = BackgroundStatus.ACTIVE.name
            return
        }
        val readLogsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_LOGS
        ) == PackageManager.PERMISSION_GRANTED

        val bgStatus = when (state) {
            XposedActivationState.DETECTING -> {
                sources?.stopReadLogsFallback()
                BackgroundStatus.DETECTING
            }
            XposedActivationState.ACTIVE -> {
                sources?.stopReadLogsFallback()
                BackgroundStatus.ACTIVE
            }
            XposedActivationState.INACTIVE -> {
                if (readLogsGranted) {
                    sources?.startReadLogsFallback()
                    BackgroundStatus.INACTIVE
                } else {
                    sources?.stopReadLogsFallback()
                    BackgroundStatus.READ_LOGS_NOT_GRANTED
                }
            }
        }
        settings.backgroundStatus = bgStatus.name
    }

    private fun currentBackgroundStatusText(): String? {
        val bgStatusName = settings.backgroundStatus
        return when (bgStatusName) {
            BackgroundStatus.ACTIVE.name, "" -> null
            BackgroundStatus.DETECTING.name -> getString(R.string.background_status_detecting)
            BackgroundStatus.INACTIVE.name -> getString(R.string.background_status_inactive)
            BackgroundStatus.READ_LOGS_NOT_GRANTED.name -> getString(R.string.background_status_read_logs_not_granted)
            else -> bgStatusName
        }
    }

    override fun onCachedReloginRequired(): AuthResult {
        if (!invalidateSessionSafely()) {
            return AuthResult.Failed(IllegalStateException("Failed to invalidate session"))
        }
        val startedText = getString(R.string.status_relogin_with_cached)
        settings.statusMessage = startedText
        settings.connectionStatusMessage = startedText
        notifications.update(startedText, currentBackgroundStatusText())

        val result = authentication.cachedReloginBlocking()
        when (result) {
            is AuthResult.Success -> {
                settings.serviceRunning = true
                restartSelfForFreshConfig()
            }
            is AuthResult.AuthRejected -> {
                invalidateSessionSafely()
                val msg = getString(R.string.status_password_changed_retry)
                showReloginStatus(msg)
                notifications.showStatus(getString(R.string.notification_password_may_have_changed))
            }
            is AuthResult.RateLimited -> {
                val msg = getString(R.string.status_login_rate_limited)
                showReloginStatus(msg)
            }
            is AuthResult.NoCredentials -> {
                invalidateSessionSafely()
                showReloginStatus(getString(R.string.status_session_expired))
            }
            is AuthResult.Failed -> {
                val msg = getString(R.string.status_relogin_failed, result.error.message.orEmpty())
                showReloginStatus(msg)
            }
            is AuthResult.PersistenceFailure ->
                if (!result.invalidationPersisted) {
                    finishAuthFailure(getString(R.string.status_session_invalidation_persist_failed))
                } else {
                    val msg = getString(R.string.status_relogin_failed, result.error.message.orEmpty())
                    showReloginStatus(msg)
                }
            else -> Unit
        }
        return result
    }

    private fun showReloginStatus(message: String) {
        settings.statusMessage = message
        settings.connectionStatusMessage = message
        notifications.update(message, currentBackgroundStatusText())
    }
    override fun onServerVersionAdvanced(version: Long) {
        settings.lastServerVersion = version
    }

    internal fun restartSelfForFreshConfig() {
        val intent = Intent(this, ClipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // R8: StringProvider 实现——解耦引擎对 Android 资源的直接依赖
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) getString(id) else getString(id, *args)

    // R10 测试访问器
    internal fun notificationForTest(message: String, subText: String?): Notification =
        notifications.buildForTest(message, currentBackgroundStatusText(), subText)

    override fun onRemoteTextApplied(text: String) {
        onStatus(getString(R.string.status_remote_text_copied))
    }

    private fun startSync() {
        val config = ClipConfig.default(this)
        if (!settings.hasSession || config.session.serverUrl.isBlank() || config.session.token.isBlank()) {
            // 无有效会话，尝试自动登录
            if (settings.savePassword && settings.savedEncryptedPassword.isNotBlank()) {
                authentication.autoLogin()
            } else {
                settings.serviceRunning = false
                val stopped = getString(R.string.status_service_stopped)
                settings.statusMessage = stopped
                settings.connectionStatusMessage = stopped
                stopForegroundAndService()
            }
            return
        }
        sessionRecoveryAttempted.set(false)
        startEngine(config)
    }

    private fun startEngine(config: ClipConfig) {
        val connecting = getString(R.string.status_connecting)
        settings.serviceRunning = true
        settings.statusMessage = connecting
        settings.connectionStatusMessage = connecting
        val bgStatusText = currentBackgroundStatusText()
        notifications.startForeground(connecting, bgStatusText, this)
        engine?.stop()
        sources?.stop()
        engine = TextSyncEngine(
            context = this,
            config = config,
            callbacks = this,
            stringProvider = this,
            disconnectedStatus = { message, subText -> onStatus(message, disconnected = true, subText = subText) }
        ).also { it.start() }
        sources = ClipboardSources(
            context = this,
            callback = { text, source -> engine?.sendLocalText(text, source) },
            status = ::onStatus
        ).also {
            it.start()
            evaluateBackgroundStatus(TextCascadeApplication.activationState)
        }
    }

    private fun reloginWithCurrentConfig(typedPassword: String) {
        authentication.reloginWithCurrentConfig(typedPassword)
    }

    private fun finishAuthFailure(message: String) {
        settings.statusMessage = message
        settings.connectionStatusMessage = message
        notifications.update(message, currentBackgroundStatusText())
        settings.serviceRunning = false
        stopForegroundAndService()
    }

    private fun registerUserPresentReceiver() {
        if (userPresentReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    engine?.reconnectAfterUserPresent()
                }
            }
        }
        userPresentReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterUserPresentReceiver() {
        userPresentReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        userPresentReceiver = null
    }

}




