/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
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

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

enum class XposedActivationState {
    DETECTING,
    ACTIVE,
    INACTIVE
}

enum class BackgroundStatus {
    DETECTING,
    ACTIVE,
    INACTIVE,
    READ_LOGS_NOT_GRANTED
}

class TextCascadeApplication : Application(), XposedServiceHelper.OnServiceListener {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var timeoutRunnable: Runnable? = null

    companion object {
        const val DETECTION_TIMEOUT_MS = 5000L

        @Volatile
        var currentService: XposedService? = null
            private set

        @Volatile
        var activationState: XposedActivationState = XposedActivationState.DETECTING
            private set

        private val listeners = mutableListOf<(XposedActivationState) -> Unit>()
        private val lock = Any()

        fun addActivationListener(listener: (XposedActivationState) -> Unit) {
            synchronized(lock) {
                listeners.add(listener)
            }
        }

        fun removeActivationListener(listener: (XposedActivationState) -> Unit) {
            synchronized(lock) {
                listeners.remove(listener)
            }
        }

        private fun notifyStateChanged(state: XposedActivationState) {
            val targets = synchronized(lock) { listeners.toList() }
            for (listener in targets) {
                runCatching { listener(state) }
            }
        }

        internal fun resetForTest() {
            synchronized(lock) {
                currentService = null
                activationState = XposedActivationState.DETECTING
                listeners.clear()
            }
        }

        internal fun setActivationStateForTest(state: XposedActivationState) {
            synchronized(lock) {
                activationState = state
            }
            notifyStateChanged(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            XposedServiceHelper.registerListener(this)
        }
    }

    override fun onServiceBind(service: XposedService) {
        currentService = service
        if (activationState == XposedActivationState.ACTIVE) {
            return
        }
        startDetection(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (currentService === service) {
            currentService = null
        }
        if (activationState != XposedActivationState.ACTIVE) {
            cancelTimeout()
            activationState = XposedActivationState.DETECTING
            notifyStateChanged(XposedActivationState.DETECTING)
        }
    }

    private fun startDetection(service: XposedService) {
        cancelTimeout()
        val runnable = Runnable {
            if (activationState != XposedActivationState.ACTIVE) {
                activationState = XposedActivationState.INACTIVE
                notifyStateChanged(XposedActivationState.INACTIVE)
            }
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, DETECTION_TIMEOUT_MS)

        refreshActivationIfNeeded(service)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun refreshActivationIfNeeded(serviceOverride: XposedService? = null) {
        if (activationState == XposedActivationState.ACTIVE) return
        val current = serviceOverride ?: currentService ?: run {
            activationState = XposedActivationState.DETECTING
            notifyStateChanged(XposedActivationState.DETECTING)
            return
        }
        if (current.apiVersion < 102) {
            activationState = XposedActivationState.DETECTING
            notifyStateChanged(XposedActivationState.DETECTING)
            return
        }
        runCatching {
            current.getRunningTargets()
        }.onSuccess { targets ->
            if (targets.isNotEmpty()) {
                markActive()
            }
        }.onFailure {
            activationState = XposedActivationState.DETECTING
            notifyStateChanged(XposedActivationState.DETECTING)
        }
    }

    private fun markActive() {
        cancelTimeout()
        activationState = XposedActivationState.ACTIVE
        notifyStateChanged(XposedActivationState.ACTIVE)
    }
}