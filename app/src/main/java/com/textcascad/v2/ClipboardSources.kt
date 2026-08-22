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
import android.content.ClipboardManager
import android.content.Context
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
    private val status: (message: String) -> Unit,
    private val logcatProcessLauncher: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
    private val logcatRestartDelayMs: Long = LOGCAT_RESTART_DELAY_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val lifecycleLock = Any()
    private var generation = 0L
    @Volatile private var logcatEnabled = true
    private var logcatProcess: Process? = null
    private var logcatWorker: Thread? = null
    private var lastLogcatLaunchMs = 0L
    private var consecutiveLogcatFailures = 0
    private var logcatStableStartTimeMs = 0L
    private var lastFailureMessage = ""
    private var lastFailureReportMs = 0L

    fun start() {
        startNormalClipboardListener()
        startReadLogsClipboardTrigger()
    }

    fun stop() {
        stopInternal(joinWorker = true)
    }

    fun stopNonBlocking() {
        stopInternal(joinWorker = false)
    }

    fun setLogcatEnabled(enabled: Boolean) {
        logcatEnabled = enabled
        if (!enabled) {
            synchronized(lifecycleLock) {
                runCatching { logcatProcess?.destroy() }
            }
        } else if (!isLogcatWorkerAliveForTest()) {
            startReadLogsClipboardTrigger()
        }
    }

    private fun stopInternal(joinWorker: Boolean) {
        listener?.let(clipboardManager::removePrimaryClipChangedListener)
        listener = null
        val workerToJoin: Thread?
        synchronized(lifecycleLock) {
            generation++
            runCatching { logcatProcess?.destroy() }
            logcatWorker?.interrupt()
            logcatProcess = null
            workerToJoin = logcatWorker
            logcatWorker = null
        }
        if (joinWorker) {
            workerToJoin?.let { runCatching { it.join(STDERR_JOIN_TIMEOUT_MS + 500L) } }
        }
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

    // RB6: 基于 generation 的 logcat 线程与进程控制
    private fun startReadLogsClipboardTrigger() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            status(context.getString(R.string.status_read_logs_not_granted_xposed_hint))
            return
        }

        val currentWorkerId: Long
        synchronized(lifecycleLock) {
            generation++
            currentWorkerId = generation
            runCatching { logcatProcess?.destroy() }
            logcatWorker?.interrupt()
            logcatProcess = null
            consecutiveLogcatFailures = 0
            logcatStableStartTimeMs = 0L
            lastFailureMessage = ""
            lastFailureReportMs = 0L
        }

        lateinit var worker: Thread
        synchronized(lifecycleLock) {
            if (currentWorkerId != generation) return
            worker = thread(name = "textcascade-read-logs", isDaemon = true, start = false) {
                while (true) {
                    synchronized(lifecycleLock) {
                        if (currentWorkerId != generation) return@thread
                    }

                    var process: Process? = null
                    var stderrThread: Thread? = null
                    var outputObserved = false
                    try {
                        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(nowMs()))
                        val command = arrayOf(
                            "logcat",
                            "-b", "system",
                            "-T", timeStamp,
                            "ClipboardService:E",
                            "SemClipboardService:E",
                            "MiuiClipboardService:E",
                            "HwClipboardService:E",
                            "ClipboardManager:E",
                            "*:S"
                        )
                        val proc = logcatProcessLauncher(command)
                        synchronized(lifecycleLock) {
                            if (currentWorkerId != generation) {
                                proc.destroy()
                                return@thread
                            }
                            process = proc
                            logcatProcess = proc
                        }

                        stderrThread = thread(name = "textcascade-logcat-stderr", isDaemon = true) {
                            proc.errorStream.use { input ->
                                val buffer = ByteArray(4096)
                                while (isCurrentWorker(currentWorkerId)) {
                                    if (input.read(buffer) < 0) break
                                }
                            }
                        }

                        val reader = BufferedReader(InputStreamReader(proc.inputStream))
                        reader.useLines { lines ->
                            for (line in lines) {
                                synchronized(lifecycleLock) {
                                    if (currentWorkerId != generation) return@useLines
                                }
                                var shouldLaunch = false
                                synchronized(lifecycleLock) {
                                    if (currentWorkerId == generation) {
                                        outputObserved = true
                                        val now = nowMs()
                                        if (logcatStableStartTimeMs == 0L) {
                                            logcatStableStartTimeMs = now
                                        } else if (now - logcatStableStartTimeMs >= LOGCAT_STABLE_RESET_MS) {
                                            consecutiveLogcatFailures = 0
                                            logcatStableStartTimeMs = now
                                        }
                                        if (isClipboardDenialLog(line, context.packageName) && now - lastLogcatLaunchMs > 1000) {
                                            lastLogcatLaunchMs = now
                                            shouldLaunch = true
                                        }
                                    }
                                }
                                if (shouldLaunch) {
                                    try {
                                        context.startActivity(ClipboardFloatingActivity.intent(context))
                                    } catch (e: Exception) {
                                        status(context.getString(R.string.status_clipboard_write_failed))
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        reportFailure(currentWorkerId, e.message ?: e.javaClass.simpleName)
                    } finally {
                        runCatching { process?.destroy() }
                        runCatching { process?.inputStream?.close() }
                        runCatching { process?.errorStream?.close() }
                        stderrThread?.let { runCatching { it.join(STDERR_JOIN_TIMEOUT_MS) } }
                        synchronized(lifecycleLock) {
                            if (logcatProcess === process) {
                                logcatProcess = null
                            }
                            if (currentWorkerId != generation) return@thread
                        }
                    }

                    var shouldPause = false
                    synchronized(lifecycleLock) {
                        if (currentWorkerId == generation) {
                            val stableForMs = if (logcatStableStartTimeMs == 0L) 0L else nowMs() - logcatStableStartTimeMs
                            if (!outputObserved || stableForMs < LOGCAT_STABLE_RESET_MS) {
                                consecutiveLogcatFailures++
                                logcatStableStartTimeMs = 0L
                                shouldPause = consecutiveLogcatFailures >= MAX_LOGCAT_FAILURES
                            }
                        }
                    }
                    if (!isCurrentWorker(currentWorkerId)) return@thread
                    if (shouldPause) {
                        status(context.getString(R.string.status_logcat_paused))
                        return@thread
                    }

                    // 应用不可见时暂停 logcat：每 2s 轮询一次，避免后台持续占用
                    if (!logcatEnabled) {
                        try {
                            while (!logcatEnabled && isCurrentWorker(currentWorkerId)) {
                                Thread.sleep(2000L)
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@thread
                        }
                        if (!isCurrentWorker(currentWorkerId)) return@thread
                        continue
                    }

                    val delay = restartDelayForFailure()
                    try {
                        sleepMs(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@thread
                    }
                }
            }
            logcatWorker = worker
            worker.start()
        }
    }

    private fun isCurrentWorker(workerId: Long): Boolean = synchronized(lifecycleLock) {
        workerId == generation
    }

    internal fun isLogcatWorkerAliveForTest(): Boolean = synchronized(lifecycleLock) {
        logcatWorker?.isAlive == true
    }

    internal fun activeLogcatProcessForTest(): Process? = synchronized(lifecycleLock) {
        logcatProcess
    }

    private fun restartDelayForFailure(): Long {
        synchronized(lifecycleLock) {
            if (logcatRestartDelayMs != LOGCAT_RESTART_DELAY_MS) {
                return logcatRestartDelayMs
            }
            val exponent = (consecutiveLogcatFailures - 1).coerceIn(0, 6)
            return minOf(LOGCAT_MAX_RESTART_DELAY_MS, LOGCAT_RESTART_DELAY_MS shl exponent)
        }
    }

    private fun reportFailure(workerId: Long, message: String) {
        val shouldReport = synchronized(lifecycleLock) {
            if (workerId != generation) {
                false
            } else {
                val now = nowMs()
                if (message == lastFailureMessage && now - lastFailureReportMs < FAILURE_STATUS_THROTTLE_MS) {
                    false
                } else {
                    lastFailureMessage = message
                    lastFailureReportMs = now
                    true
                }
            }
        }
        if (shouldReport) status(context.getString(R.string.status_logcat_failure, message))
    }

    companion object {
        internal fun isClipboardDenialLog(line: String, targetPackage: String): Boolean {
            if (!line.contains(targetPackage, ignoreCase = false)) return false
            return line.contains("Denying clipboard access", ignoreCase = true) ||
                   line.contains("clipboard", ignoreCase = true)
        }

        private const val LOGCAT_RESTART_DELAY_MS = 5000L
        private const val LOGCAT_MAX_RESTART_DELAY_MS = 300_000L
        private const val LOGCAT_STABLE_RESET_MS = 60_000L
        private const val MAX_LOGCAT_FAILURES = 6
        private const val FAILURE_STATUS_THROTTLE_MS = 30_000L
        private const val STDERR_JOIN_TIMEOUT_MS = 500L
    }
}

