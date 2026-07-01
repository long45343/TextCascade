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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TextSyncEngine(
    private val context: Context,
    private val config: ClipConfig,
    private val callbacks: Callbacks,
    private val disconnectedStatus: (message: String) -> Unit = callbacks::onStatus
) : StompClient.Listener {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRemoteTextApplied(text: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var stompClient: StompClient? = null
   @Volatile
   private var stopped = false
   @Volatile
   private var connected = false
    @Volatile
    private var firstDisconnectTime = 0L
   private var previousHash: Long? = null
    private var suppressNextLocal = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        stompClient?.close()
        stompClient = null
        executor.shutdownNow()
    }

    fun sendLocalText(text: String, source: String) {
        executor.execute {
            sendLocalTextInternal(text, source)
        }
    }

   override fun onConnected() {
       connected = true
        firstDisconnectTime = 0L
       status(context.getString(R.string.status_connected))
        stompClient?.subscribe("/user/queue/cliptext")
    }

    override fun onMessage(body: String) {
        executor.execute {
            runCatching {
                val message = JsonUtil.parseClipMessage(body)
                if (message.type != "text") {
                    return@runCatching
                }
                var text = message.payload
                if (config.cipherEnabled) {
                    text = CryptoManager.decrypt(
                        JsonUtil.parseEncryptedPayload(text),
                        config.hashedPasswordBase64
                    )
                }
                val hash = HashUtil.fnv1a64(text)
                if (previousHash == hash) {
                    return@runCatching
                }
                if (!isWithinLimits(text, context.getString(R.string.direction_inbound))) {
                    return@runCatching
                }
                previousHash = hash
                suppressNextLocal = true
                mainHandler.post {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TextCascade", text))
                    callbacks.onRemoteTextApplied(text)
                }
            }.onFailure {
                status(context.getString(R.string.status_inbound_error, it.message))
            }
        }
    }

   override fun onClosed(reason: String) {
       connected = false
        if (firstDisconnectTime == 0L) {
            firstDisconnectTime = System.currentTimeMillis()
        }
       disconnectedStatus(context.getString(R.string.status_disconnected, reason))
       scheduleReconnect()
   }

   override fun onError(error: Throwable) {
       connected = false
        if (firstDisconnectTime == 0L) {
            firstDisconnectTime = System.currentTimeMillis()
        }
       status(context.getString(R.string.status_websocket_error, error.message))
       scheduleReconnect()
   }

    private fun connect() {
        if (stopped) {
            return
        }
        status(context.getString(R.string.status_connecting))
        stompClient?.close()
        stompClient = StompClient(config.websocketUrl, config.cookieHeader, this).also {
            it.connect()
        }
    }

   private fun scheduleReconnect() {
       if (stopped) {
           return
       }
        val delay = reconnectDelaySeconds()
        status(context.getString(R.string.status_connecting))
        executor.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    private fun reconnectDelaySeconds(): Long {
        if (firstDisconnectTime == 0L) return 10L
        val elapsed = (System.currentTimeMillis() - firstDisconnectTime) / 1000
        return when {
            elapsed < 600 -> 10L
            elapsed < 1800 -> 60L
            elapsed < 3600 -> 180L
            else -> 300L
        }
    }

    fun forceReconnect() {
        firstDisconnectTime = 0L
        stopped = false
        executor.execute { connect() }
   }

    private fun sendLocalTextInternal(text: String, source: String) {
        if (text.isBlank()) {
            return
        }
        if (suppressNextLocal) {
            suppressNextLocal = false
            return
        }
        if (!connected) {
            status(context.getString(R.string.status_ignored_not_connected, source))
            return
        }
        if (!isWithinLimits(text, context.getString(R.string.direction_outbound))) {
            return
        }

        val hash = HashUtil.fnv1a64(text)
        if (previousHash == hash) {
            return
        }
        previousHash = hash

        var payload = text
        if (config.cipherEnabled) {
            payload = JsonUtil.encryptedPayload(CryptoManager.encrypt(text, config.hashedPasswordBase64))
        }
        stompClient?.send(
            destination = "/app/cliptext",
            body = JsonUtil.clipMessage(payload, "text")
        )
        status(context.getString(R.string.status_connected_broadcasting))
    }

    private fun isWithinLimits(text: String, direction: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        val localLimit = config.localMaxClipboardBytes.takeIf { it > 0 } ?: config.maxSizeBytes
        val ok = bytes <= config.maxSizeBytes && bytes <= localLimit
        if (!ok) {
            status(context.getString(R.string.status_clipboard_too_large, direction, bytes))
        }
        return ok
    }

    private fun status(message: String) {
        callbacks.onStatus(message)
    }
}
