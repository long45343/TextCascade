/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ClipForegroundService : Service(), TextSyncEngine.Callbacks {
    private lateinit var settings: SettingsStore
    private var engine: TextSyncEngine? = null
    private var sources: ClipboardSources? = null
    private var userPresentReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createChannels()
        registerUserPresentReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
       when (intent?.action) {
           ACTION_STOP -> stopSelf()
            ACTION_RECONNECT -> {
                engine?.forceReconnect()
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStatus(message: String) {
        onStatus(message, disconnected = false)
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

    override fun onRemoteTextApplied(text: String) {
        onStatus(getString(R.string.status_remote_text_copied))
    }

    private fun startSync() {
        val config = ClipConfig.default(this)
        if (config.websocketUrl.isBlank() || config.cookieHeader.isBlank()) {
            stopSelf()
            return
        }
        val connecting = getString(R.string.status_connecting)
        settings.serviceRunning = true
        settings.statusMessage = connecting
        startForeground(NOTIFICATION_ID, notification(connecting))
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

   private fun showStatusNotification(message: String) {
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
       private const val ACTION_STOP = "com.textcascade.STOP"
        private const val ACTION_RECONNECT = "com.textcascade.RECONNECT"
       private const val ACTION_SUBMIT_TEXT = "com.textcascade.SUBMIT_TEXT"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SOURCE = "source"

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
    }
}
