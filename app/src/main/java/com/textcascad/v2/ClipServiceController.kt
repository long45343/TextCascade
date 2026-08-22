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

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 前台服务 IPC 控制器：统一封装所有向 ClipForegroundService 发送的 Action 与 Extra。
 */
object ClipServiceController {
    const val ACTION_START = "com.textcascad.v2.START"
    const val ACTION_STOP = NotificationController.ACTION_STOP
    const val ACTION_RECONNECT = NotificationController.ACTION_RECONNECT
    const val ACTION_RESUME_RECONNECT = "com.textcascad.v2.RESUME_RECONNECT"
    const val ACTION_SAVE_RECONNECT = "com.textcascad.v2.SAVE_RECONNECT"
    const val ACTION_SUBMIT_TEXT = "com.textcascad.v2.SUBMIT_TEXT"
    const val ACTION_LOGCAT_ENABLED = "com.textcascad.v2.LOGCAT_ENABLED"

    const val EXTRA_LOGCAT_ENABLED = "logcat_enabled"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_PASSWORD = "password"

    fun start(context: Context) {
        dispatchServiceIntent(context, Intent(context, ClipForegroundService::class.java).setAction(ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, ClipForegroundService::class.java).setAction(ACTION_STOP))
    }

    fun resumeReconnect(context: Context) {
        val intent = Intent(context, ClipForegroundService::class.java).setAction(ACTION_RESUME_RECONNECT)
        dispatchServiceIntent(context, intent)
    }

    fun submitText(context: Context, text: String, source: String) {
        val intent = Intent(context, ClipForegroundService::class.java)
            .setAction(ACTION_SUBMIT_TEXT)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_SOURCE, source)
        context.startService(intent)
    }

    fun saveReconnect(context: Context, password: String = "") {
        val intent = Intent(context, ClipForegroundService::class.java).setAction(ACTION_SAVE_RECONNECT)
        if (password.isNotBlank()) {
            intent.putExtra(EXTRA_PASSWORD, password)
        }
        dispatchServiceIntent(context, intent)
    }

    fun setLogcatEnabled(context: Context, enabled: Boolean) {
        val intent = Intent(context, ClipForegroundService::class.java)
            .setAction(ACTION_LOGCAT_ENABLED)
            .putExtra(EXTRA_LOGCAT_ENABLED, enabled)
        context.startService(intent)
    }

    private fun dispatchServiceIntent(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

