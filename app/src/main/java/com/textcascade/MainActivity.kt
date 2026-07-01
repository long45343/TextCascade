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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var settingsStore: SettingsStore
    private lateinit var serverUrlInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var hashRoundsInput: EditText
    private lateinit var saltInput: EditText
    private lateinit var localLimitInput: EditText
    private lateinit var cipherCheck: CheckBox
    private lateinit var savePasswordCheck: CheckBox
    private lateinit var relaunchCheck: CheckBox
    private lateinit var statusNotificationCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        requestNotificationPermission()
        handleSharedText(intent)
        buildUi()
        loadSettings()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleSharedText(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        settingsStore.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateStatus()
        prefsRefreshHandler.postDelayed(prefsRefreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        settingsStore.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        prefsRefreshHandler.removeCallbacks(prefsRefreshRunnable)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key in listOf("status_message", "service_running", "cookie_header", "websocket_url")) {
            updateStatus()
        }
    }

    private fun buildUi() {
        val root = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(form)

        form.addView(title("TextCascade"))
        serverUrlInput = input(getString(R.string.hint_server_url), singleLine = true)
        usernameInput = input(getString(R.string.hint_username), singleLine = true)
        passwordInput = input(getString(R.string.hint_password), singleLine = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        hashRoundsInput = input(getString(R.string.hint_hash_rounds), singleLine = true)
        saltInput = input(getString(R.string.hint_encryption_salt), singleLine = true)
        localLimitInput = input(getString(R.string.hint_local_max_clipboard_bytes), singleLine = true)

        listOf(serverUrlInput, usernameInput, passwordInput, hashRoundsInput, saltInput, localLimitInput)
            .forEach(form::addView)

        cipherCheck = checkbox(getString(R.string.option_enable_encryption))
        savePasswordCheck = checkbox(getString(R.string.option_save_password))
        relaunchCheck = checkbox(getString(R.string.option_relaunch_on_boot))
        statusNotificationCheck = checkbox(getString(R.string.option_status_notifications))
        listOf(cipherCheck, savePasswordCheck, relaunchCheck, statusNotificationCheck).forEach(form::addView)

        val row1 = row()
        loginButton = button(getString(R.string.button_login)).apply { setOnClickListener { login() } }
        logoutButton = button(getString(R.string.button_logout)).apply { setOnClickListener { logout() } }
        row1.addView(loginButton, rowButtonParams())
        row1.addView(logoutButton, rowButtonParams())
        form.addView(row1)

        val row2 = row()
        startButton = button(getString(R.string.button_start)).apply { setOnClickListener { startServiceFromUi() } }
        stopButton = button(getString(R.string.button_stop)).apply { setOnClickListener { stopServiceFromUi() } }
        row2.addView(startButton, rowButtonParams())
        row2.addView(stopButton, rowButtonParams())
        form.addView(row2)

        val overlayButton = button(getString(R.string.button_open_overlay_settings)).apply {
            setOnClickListener { openOverlaySettings() }
        }
        form.addView(overlayButton)

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 28, 0, 0)
        }
        form.addView(statusText)
        setContentView(root)
    }

    private fun loadSettings() {
        serverUrlInput.setText(settingsStore.serverUrl)
        usernameInput.setText(settingsStore.username)
        hashRoundsInput.setText(settingsStore.hashRounds.toString())
        saltInput.setText(settingsStore.salt)
        localLimitInput.setText(settingsStore.localMaxClipboardBytes.toString())
        cipherCheck.isChecked = settingsStore.cipherEnabled
       savePasswordCheck.isChecked = settingsStore.savePassword
       passwordInput.hint = if (settingsStore.savePassword && settingsStore.savedPasswordHash.isNotBlank()) {
           getString(R.string.hint_password_saved)
       } else {
           getString(R.string.hint_password)
       }
        relaunchCheck.isChecked = settingsStore.relaunchOnBoot
        statusNotificationCheck.isChecked = settingsStore.websocketStatusNotification
        updateStatus()
    }

    private fun saveEditableSettings() {
        settingsStore.serverUrl = serverUrlInput.text.toString()
        settingsStore.username = usernameInput.text.toString()
        settingsStore.hashRounds = hashRoundsInput.text.toString().toIntOrNull() ?: ClipConfig.DEFAULT_HASH_ROUNDS
        settingsStore.salt = saltInput.text.toString()
        settingsStore.localMaxClipboardBytes = localLimitInput.text.toString().toLongOrNull() ?: ClipConfig.DEFAULT_MAX_SIZE_BYTES
        settingsStore.cipherEnabled = cipherCheck.isChecked
        settingsStore.savePassword = savePasswordCheck.isChecked
       if (!savePasswordCheck.isChecked) {
            settingsStore.savedPasswordHash = ""
       }
        settingsStore.relaunchOnBoot = relaunchCheck.isChecked
        settingsStore.websocketStatusNotification = statusNotificationCheck.isChecked
    }

   private fun login() {
       saveEditableSettings()
       val typedPassword = passwordInput.text.toString()
        val passwordSha3: String
        val hashedPassword: String
        if (typedPassword.isNotBlank()) {
            passwordSha3 = CryptoManager.sha3_512LowercaseHex(typedPassword)
            hashedPassword = if (settingsStore.cipherEnabled) {
                android.util.Base64.encodeToString(
                    CryptoManager.derivePasswordKey(
                        settingsStore.username, typedPassword,
                        settingsStore.salt, settingsStore.hashRounds
                    ),
                    android.util.Base64.NO_WRAP
                )
            } else ""
        } else if (settingsStore.savePassword && settingsStore.savedPasswordHash.isNotBlank()) {
            passwordSha3 = settingsStore.savedPasswordHash
            hashedPassword = settingsStore.hashedPasswordBase64
        } else {
            setStatus(getString(R.string.status_login_required_fields))
            return
        }
        setBusy(true, getString(R.string.status_logging_in))
       thread(name = "textcascade-login", isDaemon = true) {
           runCatching {
               ClipApiClient().login(
                   serverUrl = settingsStore.serverUrl,
                   username = settingsStore.username,
                    passwordSha3 = passwordSha3,
                    hashedPasswordBase64 = hashedPassword
               )
            }.onSuccess { result ->
                settingsStore.serverUrl = result.normalizedServerUrl
                settingsStore.websocketUrl = result.websocketUrl
                settingsStore.passwordSha3 = result.passwordSha3
                settingsStore.hashedPasswordBase64 = result.hashedPasswordBase64
                settingsStore.csrfToken = result.csrfToken
                settingsStore.cookieHeader = result.cookieHeader
                settingsStore.maxSizeBytes = result.maxSizeBytes
               if (settingsStore.savePassword) {
                    settingsStore.savedPasswordHash = passwordSha3
               } else {
                    settingsStore.savedPasswordHash = ""
               }
                runOnUiThread {
                    passwordInput.setText("")
                    settingsStore.serviceRunning = true
                    settingsStore.statusMessage = getString(R.string.status_connecting)
                    ClipForegroundService.start(this)
                    loadSettings()
                    setBusy(false, getString(R.string.status_connecting))
                }
            }.onFailure { error ->
                runOnUiThread {
                    setBusy(false, getString(R.string.status_login_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun logout() {
        saveEditableSettings()
        ClipForegroundService.stop(this)
        thread(name = "textcascade-logout", isDaemon = true) {
            ClipApiClient().logout(settingsStore.serverUrl, settingsStore.cookieHeader, settingsStore.csrfToken)
            settingsStore.clearSession()
            runOnUiThread {
                loadSettings()
                setStatus(getString(R.string.status_logged_out))
            }
        }
    }

    private fun startServiceFromUi() {
        saveEditableSettings()
        if (settingsStore.websocketUrl.isBlank() || settingsStore.cookieHeader.isBlank()) {
            setStatus(getString(R.string.status_login_first))
            return
        }
        ClipForegroundService.start(this)
        settingsStore.serviceRunning = true
        setStatus(getString(R.string.status_connecting))
    }

    private fun stopServiceFromUi() {
        ClipForegroundService.stop(this)
        settingsStore.serviceRunning = false
        setStatus(getString(R.string.status_service_stopped))
    }

    private fun handleSharedText(intent: Intent) {
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) {
            ClipForegroundService.submitText(this, text, "share")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun updateStatus() {
        val session = if (settingsStore.cookieHeader.isBlank()) {
            getString(R.string.session_not_logged_in)
        } else {
            getString(R.string.session_logged_in)
        }
        val service = if (settingsStore.serviceRunning) {
            getString(R.string.service_enabled)
        } else {
            getString(R.string.service_stopped)
        }
        statusText.text = getString(
            R.string.status_summary,
            settingsStore.statusMessage.ifBlank { getString(R.string.status_idle) },
            session,
            settingsStore.websocketUrl.ifBlank { getString(R.string.status_none) },
            service
        )
    }

    private fun setStatus(message: String) {
        settingsStore.statusMessage = message
        updateStatus()
    }

    private fun setBusy(busy: Boolean, message: String) {
        loginButton.isEnabled = !busy
        startButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        setStatus(message)
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 26f
            gravity = Gravity.START
            setPadding(0, 0, 0, 24)
        }
    }

    private fun input(hint: String, singleLine: Boolean): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.isSingleLine = singleLine
        }
    }

    private fun checkbox(text: String): CheckBox {
        return CheckBox(this).apply {
            this.text = text
        }
    }

    private fun button(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
        }
    }

    private fun row(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
    }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12
        }
    }
    private val prefsRefreshHandler = Handler(Looper.getMainLooper())
    private val prefsRefreshRunnable = Runnable { updateStatus() }
}
