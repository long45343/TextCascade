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
    private lateinit var passwordSavedIndicator: TextView
    private lateinit var hashRoundsInput: EditText
    private lateinit var saltInput: EditText
    private lateinit var localLimitInput: EditText
    private lateinit var cipherCheck: CheckBox
    private lateinit var savePasswordCheck: CheckBox
    private lateinit var relaunchCheck: CheckBox
    private lateinit var statusNotificationCheck: CheckBox
    // F5: 信任所有证书
    private lateinit var trustAllCertsCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    // F2: 保存并重连
    private lateinit var saveReconnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        requestNotificationPermission()
        handleSharedText(intent)
        buildUi()
        loadSettings()
        if (settingsStore.needsPasswordMigration()) {
            settingsStore.clearLegacyPasswordHash()
            settingsStore.savePassword = false
            loadSettings()
            setStatus(getString(R.string.status_password_migration_required))
        }
        // D3/K7: 检测是否有保存的密码因 Keystore 解密失败而被清除
        if (settingsStore.consumePasswordDecryptionFailure()) {
            settingsStore.savePassword = false
            setStatus(getString(R.string.status_password_decryption_failed))
        }
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
        // F5: 解冻/回前台时强制检查连接，若断开立即重连
        if (settingsStore.serviceRunning &&
            settingsStore.websocketUrl.isNotBlank() &&
            settingsStore.cookieHeader.isNotBlank()
        ) {
            ClipForegroundService.resumeReconnect(this)
        }
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

        form.addView(title(getString(R.string.title_with_version, appVersionName())))
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

        passwordSavedIndicator = TextView(this).apply {
            textSize = 13f
            visibility = android.view.View.GONE
            setPadding(4, 2, 0, 8)
        }
        form.addView(passwordSavedIndicator, form.indexOfChild(passwordInput) + 1)

        cipherCheck = checkbox(getString(R.string.option_enable_encryption))
        savePasswordCheck = checkbox(getString(R.string.option_save_password))
        savePasswordCheck.setOnCheckedChangeListener { _, isChecked ->
            val hasSaved = settingsStore.savedEncryptedPassword.isNotBlank()
            if (isChecked && hasSaved) {
                passwordInput.hint = getString(R.string.hint_password_saved)
                passwordSavedIndicator.text = getString(R.string.indicator_password_saved)
                passwordSavedIndicator.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                passwordSavedIndicator.visibility = android.view.View.VISIBLE
            } else if (!isChecked) {
                passwordInput.hint = getString(R.string.hint_password)
                passwordSavedIndicator.visibility = android.view.View.GONE
            }
            if (isChecked && !hasSaved) {
                passwordInput.hint = getString(R.string.hint_password)
                passwordSavedIndicator.visibility = android.view.View.GONE
            }
        }
        relaunchCheck = checkbox(getString(R.string.option_relaunch_on_boot))
        statusNotificationCheck = checkbox(getString(R.string.option_status_notifications))
        // F5: 信任所有证书
        trustAllCertsCheck = checkbox(getString(R.string.option_trust_all_certs))
        listOf(cipherCheck, savePasswordCheck, relaunchCheck, statusNotificationCheck, trustAllCertsCheck)
            .forEach(form::addView)

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

        // F2: 保存并重连
        val row3 = row()
        saveReconnectButton = button(getString(R.string.button_save_reconnect)).apply {
            setOnClickListener { saveAndReconnect() }
        }
        row3.addView(saveReconnectButton, rowButtonParams())
        form.addView(row3)

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
        updatePasswordSavedIndicator()
        relaunchCheck.isChecked = settingsStore.relaunchOnBoot
        statusNotificationCheck.isChecked = settingsStore.websocketStatusNotification
        // F5
        trustAllCertsCheck.isChecked = settingsStore.trustAllCerts
        updateStatus()
    }

    private fun updatePasswordSavedIndicator() {
        val saved = settingsStore.savePassword && settingsStore.savedEncryptedPassword.isNotBlank()
        if (saved) {
            passwordInput.hint = getString(R.string.hint_password_saved)
            passwordSavedIndicator.text = getString(R.string.indicator_password_saved)
            passwordSavedIndicator.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            passwordSavedIndicator.visibility = android.view.View.VISIBLE
        } else {
            passwordInput.hint = getString(R.string.hint_password)
            passwordSavedIndicator.visibility = android.view.View.GONE
        }
    }

    private fun saveEditableSettings(): Boolean {
        val rounds = hashRoundsInput.text.toString().toIntOrNull()
        if (rounds == null || rounds < ClipConfig.MIN_HASH_ROUNDS || rounds > ClipConfig.MAX_HASH_ROUNDS) {
            setStatus(getString(R.string.status_invalid_hash_rounds, ClipConfig.MIN_HASH_ROUNDS, ClipConfig.MAX_HASH_ROUNDS))
            return false
        }
        settingsStore.serverUrl = serverUrlInput.text.toString()
        settingsStore.username = usernameInput.text.toString()
        settingsStore.hashRounds = rounds
        settingsStore.salt = saltInput.text.toString()
        settingsStore.localMaxClipboardBytes = localLimitInput.text.toString().toLongOrNull() ?: ClipConfig.DEFAULT_MAX_SIZE_BYTES
        settingsStore.cipherEnabled = cipherCheck.isChecked
        settingsStore.savePassword = savePasswordCheck.isChecked
        if (!savePasswordCheck.isChecked) {
            settingsStore.savedPasswordHash = ""
            settingsStore.savedEncryptedPassword = ""
        }
        settingsStore.relaunchOnBoot = relaunchCheck.isChecked
        settingsStore.websocketStatusNotification = statusNotificationCheck.isChecked
        // F5
        settingsStore.trustAllCerts = trustAllCertsCheck.isChecked
        return true
    }

    private fun login() {
        if (!saveEditableSettings()) return
        val typedPassword = passwordInput.text.toString()
        setBusy(true, getString(R.string.status_logging_in))
        thread(name = "textcascade-login", isDaemon = true) {
            try {
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
                } else if (settingsStore.savePassword && settingsStore.savedEncryptedPassword.isNotBlank()) {
                    val savedPassword = settingsStore.savedEncryptedPassword
                    passwordSha3 = CryptoManager.sha3_512LowercaseHex(savedPassword)
                    hashedPassword = if (settingsStore.cipherEnabled) {
                        android.util.Base64.encodeToString(
                            CryptoManager.derivePasswordKey(
                                settingsStore.username, savedPassword,
                                settingsStore.salt, settingsStore.hashRounds
                            ),
                            android.util.Base64.NO_WRAP
                        )
                    } else ""
                } else {
                    runOnUiThread { setBusy(false, getString(R.string.status_login_required_fields)) }
                    return@thread
                }

                val result = ClipApiClient().login(
                    serverUrl = settingsStore.serverUrl,
                    username = settingsStore.username,
                    passwordSha3 = passwordSha3,
                    hashedPasswordBase64 = hashedPassword
                )

                try {
                    settingsStore.serverUrl = result.normalizedServerUrl
                    settingsStore.websocketUrl = result.websocketUrl
                    settingsStore.passwordSha3 = result.passwordSha3
                    settingsStore.hashedPasswordBase64 = result.hashedPasswordBase64
                    settingsStore.csrfToken = result.csrfToken
                    settingsStore.cookieHeader = result.cookieHeader
                    settingsStore.maxSizeBytes = result.maxSizeBytes
                    if (settingsStore.savePassword) {
                        if (typedPassword.isNotBlank()) {
                            settingsStore.savedEncryptedPassword = typedPassword
                        }
                        settingsStore.savedPasswordHash = ""
                    } else {
                        settingsStore.savedEncryptedPassword = ""
                        settingsStore.savedPasswordHash = ""
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TextCascade", "EncryptedPrefs encrypt failed, falling back to plaintext", e)
                    settingsStore.serverUrl = result.normalizedServerUrl
                    settingsStore.websocketUrl = result.websocketUrl
                    val editor = settingsStore.sharedPreferences.edit()
                        .putString("password_sha3", result.passwordSha3)
                        .putString("hashed_password_base64", result.hashedPasswordBase64)
                        .putString("csrf_token", result.csrfToken)
                        .putString("cookie_header", result.cookieHeader)
                        .putString("saved_password_hash", "")
                    if (settingsStore.savePassword && typedPassword.isNotBlank()) {
                        editor.putString("saved_encrypted_password", typedPassword)
                    }
                    editor.apply()
                    settingsStore.maxSizeBytes = result.maxSizeBytes
                }

                runOnUiThread {
                    passwordInput.setText("")
                    settingsStore.serviceRunning = true
                    settingsStore.statusMessage = getString(R.string.status_connecting)
                    ClipForegroundService.start(this)
                    loadSettings()
                    setBusy(false, getString(R.string.status_connecting))
                }
            } catch (error: Throwable) {
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
        if (!saveEditableSettings()) return
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

    // F2: 保存并重连
    private fun saveAndReconnect() {
        if (!saveEditableSettings()) return
        val typedPassword = passwordInput.text.toString()
        // R3: 有输入密码或已有保存密码时才允许重登；否则提示需要填写
        val hasPassword = typedPassword.isNotBlank() ||
            (settingsStore.savePassword && settingsStore.savedEncryptedPassword.isNotBlank())
        if (!hasPassword) {
            setStatus(getString(R.string.status_login_required_fields))
            return
        }
        ClipForegroundService.saveReconnect(this, typedPassword)
        passwordInput.setText("")
        setStatus(getString(R.string.status_connecting))
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
        saveReconnectButton.isEnabled = !busy
        setStatus(message)
    }

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
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
