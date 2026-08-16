/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
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

package com.textcascade

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
import kotlin.concurrent.thread

class ClipForegroundService : Service(), TextSyncEngine.Callbacks {
    private lateinit var settings: SettingsStore
    private var engine: TextSyncEngine? = null
    private var sources: ClipboardSources? = null
    private var userPresentReceiver: BroadcastReceiver? = null
    // R2: 会话失效重登只尝试一次
    private val sessionRecoveryAttempted = AtomicBoolean(false)
    // F3: 通知节流
    private var lastStatusNotificationMs = 0L

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
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
                // R2: 用当前设置重新登录；密码通过 Intent extra 内存传递，不落盘
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
                engine?.sendLocalText(text, source)
            }
            else -> startSync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterUserPresentReceiver()
        sources?.stop()
        sources = null
        engine?.stop()
        engine = null
        settings.serviceRunning = false
        super.onDestroy()
    }

        private fun startForegroundCompat(message: String) {
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStatus(message: String) {
        onStatus(message, disconnected = false)
    }

    // R2: 会话失效回调
    override fun onSessionExpired() {
        if (sessionRecoveryAttempted.compareAndSet(false, true)) {
            // 有保存密码时静默重登一次
            if (settings.savePassword && settings.savedEncryptedPassword.isNotBlank()) {
                autoLogin()
            } else {
                onStatus(getString(R.string.status_session_expired), disconnected = false)
            }
        }
    }

    private fun onStatus(message: String, disconnected: Boolean) {
        settings.statusMessage = message
        updateNotification(message)
        if (settings.websocketStatusNotification && disconnected) {
            showStatusNotification(getString(R.string.notification_websocket_lost))
        } else if (!disconnected) {
            dismissStatusNotification()
        }
    }

    override fun onCachedReloginRequired(): CachedReloginResult {
        val startedText = getString(R.string.status_relogin_with_cached)
        settings.statusMessage = startedText
        updateNotification(startedText)

        val result = CachedReloginRunner(
            settings = settings,
            loginClient = ClipApiClient()
        ).execute()

        when (result) {
            is CachedReloginResult.Success -> {
                restartSelfForFreshConfig()
            }
            CachedReloginResult.AuthFailure -> {
                val msg = getString(R.string.status_password_changed_retry)
                settings.statusMessage = msg
                updateNotification(msg)
                showStatusNotification(getString(R.string.notification_password_may_have_changed))
            }
            is CachedReloginResult.TransientFailure -> {
                val msg = getString(R.string.status_relogin_failed, result.error.message.orEmpty())
                settings.statusMessage = msg
                updateNotification(msg)
            }
            CachedReloginResult.NoCredentials -> {
                val msg = getString(R.string.status_session_expired)
                settings.statusMessage = msg
                updateNotification(msg)
            }
        }
        return result
    }

    private fun restartSelfForFreshConfig() {
        val intent = Intent(this, ClipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRemoteTextApplied(text: String) {
        onStatus(getString(R.string.status_remote_text_copied))
    }

    private fun startSync() {
        val config = ClipConfig.default(this)
        if (config.websocketUrl.isBlank() || config.cookieHeader.isBlank()) {
            // F1: 无有效会话，尝试自动登录
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
            disconnectedStatus = { message -> onStatus(message, disconnected = true) }
        ).also { it.start() }
        sources = ClipboardSources(
            context = this,
            callback = { text, source -> engine?.sendLocalText(text, source) },
            status = ::onStatus
        ).also { it.start() }
    }

    // F1: 自动登录
    private fun autoLogin() {
        val statusMsg = getString(R.string.status_auto_login)
        settings.statusMessage = statusMsg
        updateNotification(statusMsg)
        startForegroundCompat(statusMsg)
        settings.serviceRunning = true
        thread(name = "textcascade-auto-login", isDaemon = true) {
            // K3: 从加密密码实时派生 SHA3 和 PBKDF2
            val savedPassword = settings.savedEncryptedPassword
            if (savedPassword.isBlank()) {
                settings.statusMessage = getString(R.string.status_auto_login_failed, "No saved password")
                updateNotification(settings.statusMessage)
                settings.serviceRunning = false
                stopForegroundAndService()
                return@thread
            }

            runCatching {
                val passwordSha3 = CryptoManager.sha3_512LowercaseHex(savedPassword)
                val hashedPasswordBase64 = if (settings.cipherEnabled) {
                    android.util.Base64.encodeToString(
                        CryptoManager.derivePasswordKey(
                            settings.username, savedPassword,
                            settings.salt, settings.hashRounds
                        ),
                        android.util.Base64.NO_WRAP
                    )
                } else ""
                ClipApiClient().login(
                    serverUrl = settings.serverUrl,
                    username = settings.username,
                    passwordSha3 = passwordSha3,
                    hashedPasswordBase64 = hashedPasswordBase64
                )
            }.onSuccess { result ->
                settings.websocketUrl = result.websocketUrl
                settings.cookieHeader = result.cookieHeader
                settings.csrfToken = result.csrfToken
                settings.maxSizeBytes = result.maxSizeBytes
                // 重启同步
                val intent = Intent(this, ClipForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }.onFailure {
                settings.statusMessage = getString(R.string.status_auto_login_failed, it.message)
                updateNotification(getString(R.string.status_auto_login_failed, it.message))
                settings.serviceRunning = false
                stopForegroundAndService()
            }
        }
    }

    /**
     * R1: 用当前 UI 参数重新登录（保存并重连）。
     * typedPassword 非空时用它派生并更新保存的密码；为空时回退到 savedEncryptedPassword。
     */
    private fun reloginWithCurrentConfig(typedPassword: String) {
        val statusMsg = getString(R.string.status_connecting)
        startForegroundCompat(statusMsg)
        settings.serviceRunning = true
        thread(name = "textcascade-relogin", isDaemon = true) {
            val password = typedPassword.ifBlank {
                if (settings.savePassword) settings.savedEncryptedPassword else ""
            }
            if (password.isBlank()) {
                settings.statusMessage = getString(R.string.status_login_required_fields)
                updateNotification(settings.statusMessage)
                settings.serviceRunning = false
                stopForegroundAndService()
                return@thread
            }

            runCatching {
                val passwordSha3 = CryptoManager.sha3_512LowercaseHex(password)
                val hashedPasswordBase64 = if (settings.cipherEnabled) {
                    android.util.Base64.encodeToString(
                        CryptoManager.derivePasswordKey(
                            settings.username, password,
                            settings.salt, settings.hashRounds
                        ),
                        android.util.Base64.NO_WRAP
                    )
                } else ""
                ClipApiClient().login(
                    serverUrl = settings.serverUrl,
                    username = settings.username,
                    passwordSha3 = passwordSha3,
                    hashedPasswordBase64 = hashedPasswordBase64
                )
            }.onSuccess { result ->
                settings.serverUrl = result.normalizedServerUrl
                settings.websocketUrl = result.websocketUrl
                settings.passwordSha3 = result.passwordSha3
                settings.hashedPasswordBase64 = result.hashedPasswordBase64
                settings.csrfToken = result.csrfToken
                settings.cookieHeader = result.cookieHeader
                settings.maxSizeBytes = result.maxSizeBytes
                if (settings.savePassword && typedPassword.isNotBlank()) {
                    settings.savedEncryptedPassword = typedPassword
                }
                val intent = Intent(this, ClipForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }.onFailure {
                settings.statusMessage = getString(R.string.status_login_failed, it.message)
                updateNotification(getString(R.string.status_login_failed, it.message))
                settings.serviceRunning = false
                stopForegroundAndService()
            }
        }
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

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_small_icon)
            .setContentTitle("TextCascade")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.button_reconnect), reconnectIntent)
            .addAction(0, getString(R.string.button_stop), stopIntent)
            .build()
    }

    // F3: 通知节流
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
        // F3: 30 秒同向事件节流
        private const val NOTIFICATION_THROTTLE_MS = 30_000L
        private const val ACTION_STOP = "com.textcascade.STOP"
        private const val ACTION_RECONNECT = "com.textcascade.RECONNECT"
        private const val ACTION_RESUME_RECONNECT = "com.textcascade.RESUME_RECONNECT"
        private const val ACTION_SAVE_RECONNECT = "com.textcascade.SAVE_RECONNECT"
        private const val ACTION_SUBMIT_TEXT = "com.textcascade.SUBMIT_TEXT"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SOURCE = "source"
        // R2: 仅用于保存并重连，内存传递不持久化
        private const val EXTRA_PASSWORD = "password"

        // F4: 解冻/回前台时由 MainActivity 调用，幂等触发重连
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

        // F2: 保存并重连
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
