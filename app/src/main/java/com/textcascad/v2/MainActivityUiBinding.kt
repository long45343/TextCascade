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

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class MainActivityUiBinding(
    val root: View,
    val serverUrlInput: EditText,
    val usernameInput: EditText,
    val passwordInput: EditText,
    val passwordSavedIndicator: TextView,
    val hashRoundsInput: EditText,
    val saltInput: EditText,
    val localLimitInput: EditText,
    val cipherCheck: CheckBox,
    val savePasswordCheck: CheckBox,
    val relaunchCheck: CheckBox,
    val statusNotificationCheck: CheckBox,
    val trustAllCertsCheck: CheckBox,
    val statusText: TextView,
    val startButton: Button,
    val stopButton: Button,
    val loginButton: Button,
    val logoutButton: Button,
    val saveReconnectButton: Button,
    val overlayButton: Button
) {
    var suppressTrustAllListener = false

    fun loadSettings(settings: SettingsStore) {
        serverUrlInput.setText(settings.serverUrl)
        usernameInput.setText(settings.username)
        hashRoundsInput.setText(settings.hashRounds.toString())
        saltInput.setText(settings.salt)
        localLimitInput.setText(settings.localMaxClipboardBytes.toString())
        cipherCheck.isChecked = settings.cipherEnabled
        savePasswordCheck.isChecked = settings.savePassword
        updatePasswordSavedIndicator(settings)
        relaunchCheck.isChecked = settings.relaunchOnBoot
        statusNotificationCheck.isChecked = settings.websocketStatusNotification
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = settings.trustAllCerts
        suppressTrustAllListener = false
    }

    fun revertTrustAllCerts(settings: SettingsStore) {
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = false
        suppressTrustAllListener = false
        settings.trustAllCerts = false
    }

    fun updatePasswordSavedIndicator(settings: SettingsStore) {
        val context = root.context
        val saved = settings.savePassword && settings.savedEncryptedPassword.isNotBlank()
        if (saved) {
            passwordInput.hint = context.getString(R.string.hint_password_saved)
            passwordSavedIndicator.text = context.getString(R.string.indicator_password_saved)
            passwordSavedIndicator.setTextColor(Color.parseColor("#2E7D32"))
            passwordSavedIndicator.visibility = View.VISIBLE
        } else {
            passwordInput.hint = context.getString(R.string.hint_password)
            passwordSavedIndicator.visibility = View.GONE
        }
    }

    fun saveEditableSettings(settings: SettingsStore, onValidationError: (String) -> Unit): Boolean {
        val context = root.context
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')
        if (!serverUrl.startsWith("https://")) {
            onValidationError(context.getString(R.string.status_invalid_server_url))
            return false
        }
        val rounds = hashRoundsInput.text.toString().toIntOrNull()
        if (rounds == null || rounds < ClipConfig.MIN_HASH_ROUNDS || rounds > ClipConfig.MAX_HASH_ROUNDS) {
            onValidationError(
                context.getString(
                    R.string.status_invalid_hash_rounds,
                    ClipConfig.MIN_HASH_ROUNDS,
                    ClipConfig.MAX_HASH_ROUNDS
                )
            )
            return false
        }
        val localLimit = localLimitInput.text.toString().toLongOrNull()
        if (localLimit == null || localLimit !in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_CLIPBOARD_BYTES) {
            onValidationError(context.getString(R.string.status_invalid_local_limit))
            return false
        }
        settings.serverUrl = serverUrl
        settings.username = usernameInput.text.toString()
        settings.hashRounds = rounds
        settings.salt = saltInput.text.toString()
        settings.localMaxClipboardBytes = localLimit
        settings.cipherEnabled = cipherCheck.isChecked
        settings.savePassword = savePasswordCheck.isChecked
        if (!savePasswordCheck.isChecked) {
            settings.savedEncryptedPassword = ""
        }
        settings.relaunchOnBoot = relaunchCheck.isChecked
        settings.websocketStatusNotification = statusNotificationCheck.isChecked
        settings.trustAllCerts = trustAllCertsCheck.isChecked
        return true
    }

    fun updateStatus(
        settings: SettingsStore,
        sessionPersistenceFailed: Boolean,
        serviceRunningUiOverride: Boolean?
    ) {
        val context = root.context
        val session = if (sessionPersistenceFailed || !settings.hasSession || settings.token.isBlank()) {
            context.getString(R.string.session_not_logged_in)
        } else {
            context.getString(R.string.session_logged_in)
        }
        val serviceRunning = serviceRunningUiOverride ?: settings.serviceRunning
        val service = if (serviceRunning) {
            context.getString(R.string.service_enabled)
        } else {
            context.getString(R.string.service_stopped)
        }
        val websocketUrl = runCatching {
            ClipConfig.websocketUrlFromServerUrl(settings.serverUrl)
        }.getOrDefault("")
        val base = context.getString(
            R.string.status_summary,
            settings.statusMessage.ifBlank { context.getString(R.string.status_idle) },
            session,
            websocketUrl.ifBlank { context.getString(R.string.status_none) },
            service
        )
        statusText.text = if (settings.securityDegraded) {
            base + "\n" + context.getString(R.string.status_security_degraded)
        } else {
            base
        }
    }

    fun setBusy(
        busy: Boolean,
        message: String,
        settings: SettingsStore,
        sessionPersistenceFailed: Boolean,
        serviceRunningUiOverride: Boolean?
    ) {
        loginButton.isEnabled = !busy
        startButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        saveReconnectButton.isEnabled = !busy
        settings.statusMessage = message
        updateStatus(settings, sessionPersistenceFailed, serviceRunningUiOverride)
    }

    fun typedPassword(): String = passwordInput.text.toString()

    fun clearPasswordInput() {
        passwordInput.setText("")
    }

    companion object {
        fun inflate(
            activity: MainActivity,
            versionName: String,
            onTrustAllCertsChanged: (Boolean) -> Unit
        ): MainActivityUiBinding {
            val root = ScrollView(activity)
            val form = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }
            root.addView(form)

            val titleView = TextView(activity).apply {
                text = activity.getString(R.string.title_with_version, versionName)
                textSize = 26f
                gravity = Gravity.START
                setPadding(0, 0, 0, 24)
            }
            form.addView(titleView)

            fun input(hintText: String, singleLine: Boolean = true): EditText =
                EditText(activity).apply {
                    hint = hintText
                    isSingleLine = singleLine
                }

            fun checkbox(label: String): CheckBox =
                CheckBox(activity).apply { text = label }

            fun button(label: String): Button =
                Button(activity).apply {
                    text = label
                    isAllCaps = false
                }

            fun row(): LinearLayout =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 16, 0, 0)
                }

            fun rowButtonParams(): LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 12
                }

            val serverUrlInput = input(activity.getString(R.string.hint_server_url))
            val usernameInput = input(activity.getString(R.string.hint_username))
            val passwordInput = input(activity.getString(R.string.hint_password)).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            val hashRoundsInput = input(activity.getString(R.string.hint_hash_rounds))
            val saltInput = input(activity.getString(R.string.hint_encryption_salt))
            val localLimitInput = input(activity.getString(R.string.hint_local_max_clipboard_bytes))

            listOf(serverUrlInput, usernameInput, passwordInput, hashRoundsInput, saltInput, localLimitInput)
                .forEach(form::addView)

            val passwordSavedIndicator = TextView(activity).apply {
                textSize = 13f
                visibility = View.GONE
                setPadding(4, 2, 0, 8)
            }
            form.addView(passwordSavedIndicator, form.indexOfChild(passwordInput) + 1)

            val cipherCheck = checkbox(activity.getString(R.string.option_enable_encryption))
            val savePasswordCheck = checkbox(activity.getString(R.string.option_save_password))
            val relaunchCheck = checkbox(activity.getString(R.string.option_relaunch_on_boot))
            val statusNotificationCheck = checkbox(activity.getString(R.string.option_status_notifications))
            val trustAllCertsCheck = checkbox(activity.getString(R.string.option_trust_all_certs))

            listOf(cipherCheck, savePasswordCheck, relaunchCheck, statusNotificationCheck, trustAllCertsCheck)
                .forEach(form::addView)

            val row1 = row()
            val loginButton = button(activity.getString(R.string.button_login))
            val logoutButton = button(activity.getString(R.string.button_logout))
            row1.addView(loginButton, rowButtonParams())
            row1.addView(logoutButton, rowButtonParams())
            form.addView(row1)

            val row2 = row()
            val startButton = button(activity.getString(R.string.button_start))
            val stopButton = button(activity.getString(R.string.button_stop))
            row2.addView(startButton, rowButtonParams())
            row2.addView(stopButton, rowButtonParams())
            form.addView(row2)

            val row3 = row()
            val saveReconnectButton = button(activity.getString(R.string.button_save_reconnect))
            row3.addView(saveReconnectButton, rowButtonParams())
            form.addView(row3)

            val overlayButton = button(activity.getString(R.string.button_open_overlay_settings))
            form.addView(overlayButton)

            val statusText = TextView(activity).apply {
                textSize = 14f
                setPadding(0, 28, 0, 0)
            }
            form.addView(statusText)

            val binding = MainActivityUiBinding(
                root = root,
                serverUrlInput = serverUrlInput,
                usernameInput = usernameInput,
                passwordInput = passwordInput,
                passwordSavedIndicator = passwordSavedIndicator,
                hashRoundsInput = hashRoundsInput,
                saltInput = saltInput,
                localLimitInput = localLimitInput,
                cipherCheck = cipherCheck,
                savePasswordCheck = savePasswordCheck,
                relaunchCheck = relaunchCheck,
                statusNotificationCheck = statusNotificationCheck,
                trustAllCertsCheck = trustAllCertsCheck,
                statusText = statusText,
                startButton = startButton,
                stopButton = stopButton,
                loginButton = loginButton,
                logoutButton = logoutButton,
                saveReconnectButton = saveReconnectButton,
                overlayButton = overlayButton
            )

            trustAllCertsCheck.setOnCheckedChangeListener { _, isChecked ->
                if (!binding.suppressTrustAllListener) {
                    onTrustAllCertsChanged(isChecked)
                }
            }

            return binding
        }
    }
}
