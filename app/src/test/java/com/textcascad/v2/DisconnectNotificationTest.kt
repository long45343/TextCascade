/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.app.Notification
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * R10: 断连通知 setSubText 显示 close code + reason；builder setOnlyAlertOnce(true)。
 */
@RunWith(RobolectricTestRunner::class)
class DisconnectNotificationTest {

    @Test
    fun foregroundNotificationCarriesDisconnectSubTextAndOnlyAlertOnce() {
        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()
        val detail = "close 1006 unexpected EOF"
        val notification = service.notificationForTest("Disconnected: $detail", detail)
        // setOnlyAlertOnce(true) → FLAG_ONLY_ALERT_ONCE 置位
        assertTrue(
            "onlyAlertOnce 应开启",
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        )
        // subText 进入 extras
        assertEquals(
            detail,
            notification.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString()
        )
    }

    @Test
    fun normalStatusNotificationHasNoSubTextButStillOnlyAlertOnce() {
        val controller = Robolectric.buildService(ClipForegroundService::class.java).create()
        val service = controller.get()
        val notification = service.notificationForTest("Connected", null)
        assertTrue(
            "onlyAlertOnce 应开启",
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        )
        assertTrue(
            "非断连不应带 subText",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT) == null
        )
    }
}
