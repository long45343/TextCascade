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

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ClipboardSources(
    private val context: Context,
    private val callback: (text: String, source: String) -> Unit,
    private val status: (message: String) -> Unit
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile
    private var stopLogcat = false
    @Volatile
    private var logcatProcess: Process? = null
    private var lastLogcatLaunchMs = 0L

    fun start() {
        startNormalClipboardListener()
        startReadLogsClipboardTrigger()
    }

    fun stop() {
        listener?.let(clipboardManager::removePrimaryClipChangedListener)
        listener = null
        stopLogcat = true
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
    }

    private fun startNormalClipboardListener() {
        if (listener != null) {
            return
        }
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            readNormalClipboardText()?.let { callback(it, "clipboard") }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    private fun readNormalClipboardText(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) {
            return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    // R11: logcat 进程自动重启
    private fun startReadLogsClipboardTrigger() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            status(context.getString(R.string.status_read_logs_not_granted_xposed_hint))
            return
        }
        stopLogcat = false
        thread(name = "textcascade-read-logs", isDaemon = true) {
            // R11: 外层循环，进程崩溃后自动重启
            while (!stopLogcat) {
                runCatching {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    logcatProcess = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-T", timeStamp, "ClipboardService:E", "*:S")
                    )
                    val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            if (stopLogcat) {
                                return@useLines
                            }
                            if (line.contains(context.packageName)) {
                                val now = System.currentTimeMillis()
                                if (now - lastLogcatLaunchMs > 1000) {
                                    lastLogcatLaunchMs = now
                                    // R12: 后台启动 Activity 可能被系统拒绝，加 try/catch
                                    try {
                                        context.startActivity(ClipboardFloatingActivity.intent(context))
                                    } catch (e: Exception) {
                                        status(context.getString(R.string.status_clipboard_write_failed))
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    status(context.getString(R.string.status_read_logs_failed, it.message))
                }
                if (stopLogcat) break
                // R11: 等待一段时间后重启 logcat 进程
                runCatching { Thread.sleep(LOGCAT_RESTART_DELAY_MS) }
            }
        }
    }

    companion object {
        // R11: logcat 重启延迟
        private const val LOGCAT_RESTART_DELAY_MS = 5000L
    }
}
