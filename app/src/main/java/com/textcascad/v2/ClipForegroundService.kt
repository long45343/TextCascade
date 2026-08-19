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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    // 通知节流
    private var lastStatusNotificationMs = 0L
    @Volatile
    private var lastForegroundNotificationMessage: String? = null
    @Volatile
    private var lastForegroundSubText: String? = null

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed.set(false)
        settings = AuthenticationDependencies.settingsStoreFactory(this)
        createChannels()
        registerUserPresentReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(settings.statusMessage.ifBlank { getString(R.string.status_connecting) })
        when (intent?.action) {
            ACTION_STOP -> {
                settings.serviceRunning = false
                stopForegroundAndService()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                engine?.forceReconnect()
            }
            ACTION_RESUME_RECONNECT -> {
                val currentEngine = engine
                when {
                    currentEngine == null -> startSync()
                    currentEngine.isStopped -> startSync()
                    else -> currentEngine.reconnectAfterUserPresent()
                }
            }
            ACTION_SAVE_RECONNECT -> {
                engine?.stop()
                sources?.stop()
                engine = null
                sources = null
                val password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty()
                if (password.isNotBlank() || (settings.savePassword && settings.savedEncryptedPassword.isNotBlank())) {
                    reloginWithCurrentConfig(password)
                } else {
                    settings.statusMessage = getString(R.string.status_login_required_fields)
                    updateNotification(settings.statusMessage)
                    stopSelf()
                }
            }
            ACTION_SUBMIT_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                if (engine == null) startSync()
                engine?.sendLocalText(text, source)
            }
            else -> startSync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed.set(true)
        authGeneration.incrementAndGet()
        unregisterUserPresentReceiver()
        sources?.stopNonBlocking()
        sources = null
        engine?.stop()
        engine = null
        settings.serviceRunning = false
        super.onDestroy()
    }

    private fun startForegroundCompat(message: String) {
        lastForegroundNotificationMessage = message
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(message), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification(message))
        }
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
        val committed = settings.markSessionInvalid()
        if (!committed) {
            engine?.stop()
            sources?.stop()
            engine = null
            sources = null
            settings.serviceRunning = false
            val msg = getString(R.string.status_session_invalidation_persist_failed)
            settings.statusMessage = msg
            updateNotification(msg)
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
                autoLogin()
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
        if (settings.statusMessage != message) {
            settings.statusMessage = message
        }
        updateNotification(message, subText)
        if (settings.websocketStatusNotification && disconnected) {
            showStatusNotification(getString(R.string.notification_websocket_lost))
        } else if (!disconnected) {
            dismissStatusNotification()
        }
    }

    override fun onCachedReloginRequired(): CachedReloginResult {
        if (!invalidateSessionSafely()) {
            return CachedReloginResult.TransientFailure(IllegalStateException("Failed to invalidate session"))
        }
        val startedText = getString(R.string.status_relogin_with_cached)
        settings.statusMessage = startedText
        updateNotification(startedText)

        val result = AuthenticationCoordinator.submitBlocking(replaceActive = false) { requestGeneration ->
            CachedReloginRunner(
                settings = settings,
                loginClient = HttpLoginClient(settings.trustAllCerts),
                isCurrent = {
                    !serviceDestroyed.get() && AuthenticationCoordinator.isCurrent(requestGeneration)
                }
            ).execute()
        } ?: CachedReloginResult.TransientFailure(IllegalStateException("Authentication executor busy"))

        when (result) {
            is CachedReloginResult.Success -> {
                if (!serviceDestroyed.get()) restartSelfForFreshConfig()
            }
            CachedReloginResult.AuthFailure -> {
                if (invalidateSessionSafely()) {
                    val msg = getString(R.string.status_password_changed_retry)
                    settings.statusMessage = msg
                    updateNotification(msg)
                    showStatusNotification(getString(R.string.notification_password_may_have_changed))
                }
            }
            is CachedReloginResult.RateLimited -> {
                val msg = getString(R.string.status_login_rate_limited)
                settings.statusMessage = msg
                updateNotification(msg)
            }
            is CachedReloginResult.TransientFailure -> {
                val msg = getString(R.string.status_relogin_failed, result.error.message.orEmpty())
                settings.statusMessage = msg
                updateNotification(msg)
            }
            CachedReloginResult.NoCredentials -> {
                if (invalidateSessionSafely()) {
                    val msg = getString(R.string.status_session_expired)
                    settings.statusMessage = msg
                    updateNotification(msg)
                }
            }
        }
        return result
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
        notification(message, subText)

    override fun onRemoteTextApplied(text: String) {
        onStatus(getString(R.string.status_remote_text_copied))
    }

    private fun startSync() {
        val config = ClipConfig.default(this)
        if (!settings.hasSession || config.serverUrl.isBlank() || config.token.isBlank()) {
            // 无有效会话，尝试自动登录
            if (settings.savePassword && settings.savedEncryptedPassword.isNotBlank()) {
                autoLogin()
            } else {
                settings.serviceRunning = false
                settings.statusMessage = getString(R.string.status_service_stopped)
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
        startForegroundCompat(connecting)
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
        ).also { it.start() }
    }

    private fun autoLogin() {
        val statusMsg = getString(R.string.status_auto_login)
        settings.statusMessage = statusMsg
        updateNotification(statusMsg)
        startForegroundCompat(statusMsg)
        if (!autoLoginQueued.compareAndSet(false, true)) return
        enqueueRelogin(
            typedPassword = "",
            automatic = true,
            taskGeneration = authGeneration.incrementAndGet()
        )
    }

    /**
     * 用当前 UI 参数重新登录（保存并重连）。
     * typedPassword 非空时用它登录并更新保存的密码；为空时回退到 savedEncryptedPassword。
     */
    private fun reloginWithCurrentConfig(typedPassword: String) {
        val statusMsg = getString(R.string.status_connecting)
        startForegroundCompat(statusMsg)
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
                    loginClientFactory = AuthenticationDependencies.loginClientFactory,
                    deriveCredentials = { value, _ ->
                        AuthenticationDependencies.deriveCredentials(settings, value)
                    },
                    startService = { _ ->
                        if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) {
                            false
                        } else {
                            AuthenticationDependencies.restartService(this)
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
                when (outcome) {
                    AuthenticationOutcome.Cancelled -> Unit
                    AuthenticationOutcome.MissingPassword -> finishAuthFailure(
                        taskGeneration,
                        requestGeneration,
                        if (automatic) getString(R.string.status_auto_login_failed, "No saved password")
                        else getString(R.string.status_login_required_fields)
                    )
                    is AuthenticationOutcome.Success -> Unit
                    is AuthenticationOutcome.ProtocolUnsupported -> finishAuthFailure(
                        taskGeneration,
                        requestGeneration,
                        getString(
                            R.string.status_protocol_unsupported,
                            outcome.serverVersion,
                            Protocol.SUPPORTED_PROTOCOL_VERSION
                        )
                    )
                    is AuthenticationOutcome.PersistenceFailure -> finishAuthFailure(
                        taskGeneration,
                        requestGeneration,
                        if (outcome.invalidationPersisted) {
                            if (automatic) getString(R.string.status_auto_login_failed, outcome.error.message.orEmpty())
                            else getString(R.string.status_login_failed, outcome.error.message.orEmpty())
                        } else getString(R.string.status_session_invalidation_persist_failed)
                    )
                    is AuthenticationOutcome.Rejected -> finishAuthFailure(
                        taskGeneration,
                        requestGeneration,
                        if (outcome.error is LoginRateLimitedException) {
                            getString(R.string.status_login_rate_limited)
                        } else if (outcome.invalidationPersisted) {
                            if (automatic) getString(R.string.status_auto_login_failed, outcome.error.message.orEmpty())
                            else getString(R.string.status_login_failed, outcome.error.message.orEmpty())
                        } else {
                            getString(R.string.status_session_invalidation_persist_failed)
                        }
                    )
                    is AuthenticationOutcome.Failed -> finishAuthFailure(
                        taskGeneration,
                        requestGeneration,
                        if (automatic) {
                            getString(R.string.status_auto_login_failed, outcome.error.message ?: outcome.error.javaClass.simpleName)
                        } else {
                            getString(R.string.status_login_failed, outcome.error.message ?: outcome.error.javaClass.simpleName)
                        }
                    )
                }
            } finally {
                if (automatic) autoLoginQueued.set(false)
            }
        }
        if (submitted == null && automatic) autoLoginQueued.set(false)
    }

    private fun isAuthTaskCurrent(taskGeneration: Long, requestGeneration: Long): Boolean =
        !serviceDestroyed.get() &&
            taskGeneration == authGeneration.get() &&
            AuthenticationCoordinator.isCurrent(requestGeneration)

    private fun finishAuthFailure(taskGeneration: Long, requestGeneration: Long, message: String) {
        if (!isAuthTaskCurrent(taskGeneration, requestGeneration)) return
        settings.statusMessage = message
        updateNotification(message)
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

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.notification_channel_status),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun updateNotification(message: String, subText: String? = null) {
        if (lastForegroundNotificationMessage == message && lastForegroundSubText == subText) return
        lastForegroundNotificationMessage = message
        lastForegroundSubText = subText
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(message, subText))
    }

    private fun notification(message: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClipForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ClipForegroundService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_small_icon)
            .setContentTitle("TextCascade")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            // R10: 节流只影响声音/振动，不阻止文本与小标题更新
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.button_reconnect), reconnectIntent)
            .addAction(0, getString(R.string.button_stop), stopIntent)
        // R10: 断连时显示 close code + reason（reason 截断 80 字符，由引擎传入）
        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }
        return builder.build()
    }

    private fun showStatusNotification(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusNotificationMs < NOTIFICATION_THROTTLE_MS) return
        lastStatusNotificationMs = now
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            STATUS_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.mipmap.ic_small_icon)
                .setContentTitle("TextCascade")
                .setContentText(message)
                .build()
        )
    }

    private fun dismissStatusNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(STATUS_NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL_SYNC = "textcascade_sync"
        private const val CHANNEL_STATUS = "textcascade_status"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_NOTIFICATION_ID = 1002
        private const val NOTIFICATION_THROTTLE_MS = 30_000L
        private const val ACTION_STOP = "com.textcascad.v2.STOP"
        private const val ACTION_RECONNECT = "com.textcascad.v2.RECONNECT"
        private const val ACTION_RESUME_RECONNECT = "com.textcascad.v2.RESUME_RECONNECT"
        private const val ACTION_SAVE_RECONNECT = "com.textcascad.v2.SAVE_RECONNECT"
        private const val ACTION_SUBMIT_TEXT = "com.textcascad.v2.SUBMIT_TEXT"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_PASSWORD = "password"

        fun resumeReconnect(context: Context) {
            val intent = Intent(context, ClipForegroundService::class.java)
                .setAction(ACTION_RESUME_RECONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, ClipForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ClipForegroundService::class.java).setAction(ACTION_STOP))
        }

        fun submitText(context: Context, text: String, source: String) {
            val intent = Intent(context, ClipForegroundService::class.java)
                .setAction(ACTION_SUBMIT_TEXT)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SOURCE, source)
            context.startService(intent)
        }

        fun saveReconnect(context: Context, password: String = "") {
            val intent = Intent(context, ClipForegroundService::class.java)
                .setAction(ACTION_SAVE_RECONNECT)
            if (password.isNotBlank()) {
                intent.putExtra(EXTRA_PASSWORD, password)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
