package com.textcascad.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal class NotificationController(private val context: Context) {
    private var lastSyncMessage: String? = null
    private var lastSyncSubText: String? = null
    private var lastStatusNotificationMs = 0L

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notification_channel_status),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun startForeground(message: String, service:android.app.Service) {
        val notification = syncNotification(message)
        lastSyncMessage = message
        lastSyncSubText = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            service.startForeground(NOTIFICATION_ID, notification)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun update(message: String, subText: String? = null) {
        if (lastSyncMessage == message && lastSyncSubText == subText) return
        lastSyncMessage = message
        lastSyncSubText = subText
        notify(NOTIFICATION_ID, syncNotification(message, subText))
    }

    fun showStatus(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusNotificationMs < STATUS_THROTTLE_MS) return
        lastStatusNotificationMs = now
        notify(
            STATUS_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.mipmap.ic_small_icon)
                .setContentTitle("TextCascade")
                .setContentText(message)
                .build()
        )
    }

    fun dismissStatus() {
        notificationManager().cancel(STATUS_NOTIFICATION_ID)
    }

    internal fun buildForTest(message: String, subText: String?): Notification =
        syncNotification(message, subText)

    private fun syncNotification(message: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, ClipForegroundService::class.java).setAction(ClipServiceController.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            context,
            REQUEST_RECONNECT,
            Intent(context, ClipForegroundService::class.java).setAction(ClipServiceController.ACTION_RECONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_small_icon)
            .setContentTitle("TextCascade")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.button_reconnect), reconnectIntent)
            .addAction(0, context.getString(R.string.button_stop), stopIntent)
        if (!subText.isNullOrBlank()) builder.setSubText(subText)
        return builder.build()
    }

    private fun notify(id: Int, notification: Notification) {
        notificationManager().notify(id, notification)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    internal companion object {
        internal const val ACTION_STOP = "com.textcascad.v2.STOP"
        internal const val ACTION_RECONNECT = "com.textcascad.v2.RECONNECT"
        private const val CHANNEL_SYNC = "textcascade_sync"
        private const val CHANNEL_STATUS = "textcascade_status"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_NOTIFICATION_ID = 1002
        private const val STATUS_THROTTLE_MS = 30_000L
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
        private const val REQUEST_RECONNECT = 2
    }
}

