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

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
    val pinnedCertInput: EditText,
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
        pinnedCertInput.setText(settings.pinnedCertSha256)
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
        settings.pinnedCertSha256 = pinnedCertInput.text.toString().trim()
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
            activity: Activity,
            versionName: String,
            onTrustAllCertsChanged: (Boolean) -> Unit
        ): MainActivityUiBinding {
            val root = activity.layoutInflater.inflate(R.layout.activity_main, null)

            val titleView = root.findViewById<TextView>(R.id.app_title)
            if (versionName.isNotBlank() && titleView != null) {
                titleView.text = activity.getString(R.string.title_with_version, versionName)
            }

            val serverUrlInput = root.findViewById<EditText>(R.id.server_url_input)
            val usernameInput = root.findViewById<EditText>(R.id.username_input)
            val passwordInput = root.findViewById<EditText>(R.id.password_input)
            val passwordSavedIndicator = root.findViewById<TextView>(R.id.password_saved_indicator)
            val hashRoundsInput = root.findViewById<EditText>(R.id.hash_rounds_input)
            val saltInput = root.findViewById<EditText>(R.id.salt_input)
            val localLimitInput = root.findViewById<EditText>(R.id.local_limit_input)
            val pinnedCertInput = root.findViewById<EditText>(R.id.pinned_cert_input)

            val cipherCheck = root.findViewById<CheckBox>(R.id.cipher_check)
            val savePasswordCheck = root.findViewById<CheckBox>(R.id.save_password_check)
            val relaunchCheck = root.findViewById<CheckBox>(R.id.relaunch_check)
            val statusNotificationCheck = root.findViewById<CheckBox>(R.id.status_notification_check)
            val trustAllCertsCheck = root.findViewById<CheckBox>(R.id.trust_all_certs_check)

            val startButton = root.findViewById<Button>(R.id.start_button)
            val stopButton = root.findViewById<Button>(R.id.stop_button)
            val loginButton = root.findViewById<Button>(R.id.login_button)
            val logoutButton = root.findViewById<Button>(R.id.logout_button)
            val saveReconnectButton = root.findViewById<Button>(R.id.save_reconnect_button)
            val overlayButton = root.findViewById<Button>(R.id.overlay_button)
            val statusText = root.findViewById<TextView>(R.id.status_text)

            val binding = MainActivityUiBinding(
                root = root,
                serverUrlInput = serverUrlInput,
                usernameInput = usernameInput,
                passwordInput = passwordInput,
                passwordSavedIndicator = passwordSavedIndicator,
                hashRoundsInput = hashRoundsInput,
                saltInput = saltInput,
                localLimitInput = localLimitInput,
                pinnedCertInput = pinnedCertInput,
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


