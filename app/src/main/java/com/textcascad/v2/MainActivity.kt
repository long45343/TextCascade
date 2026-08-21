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
import android.app.Activity
import android.app.AlertDialog
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
import java.util.concurrent.atomic.AtomicLong

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
    private lateinit var trustAllCertsCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var saveReconnectButton: Button
    private val authGeneration = AtomicLong(0L)
    private var sessionPersistenceFailed = false
    private var serviceRunningUiOverride: Boolean? = null
    // R6: 防止程序化设置 trustAllCertsCheck 时触发确认对话框
    private var suppressTrustAllListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = AuthenticationDependencies.settingsStoreFactory(this)
        requestNotificationPermission()
        handleSharedText(intent)
        buildUi()
        loadSettings()
        // 保存的密码因 Keystore 解密失败被清除时提示
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
        // 解冻/回前台时强制检查连接，若断开立即重连
        if (!sessionPersistenceFailed && settingsStore.serviceRunning &&
            settingsStore.hasSession &&
            settingsStore.serverUrl.isNotBlank()
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

    override fun onDestroy() {
        authGeneration.incrementAndGet()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key in listOf("status_message", "service_running", "has_session", "server_url")) {
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
            updatePasswordSavedIndicator()
        }
        relaunchCheck = checkbox(getString(R.string.option_relaunch_on_boot))
        statusNotificationCheck = checkbox(getString(R.string.option_status_notifications))
        trustAllCertsCheck = checkbox(getString(R.string.option_trust_all_certs))
        // R6: 勾选「信任所有证书」时弹确认对话框；取消则回退开关状态，不写入设置
        trustAllCertsCheck.setOnCheckedChangeListener { _, isChecked ->
            if (suppressTrustAllListener) return@setOnCheckedChangeListener
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_trust_all_certs_title)
                    .setMessage(R.string.dialog_trust_all_certs_message)
                    .setPositiveButton(R.string.button_confirm) { _, _ ->
                        settingsStore.trustAllCerts = true
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> revertTrustAllCerts() }
                    .setOnCancelListener { revertTrustAllCerts() }
                    .show()
            } else {
                settingsStore.trustAllCerts = false
            }
        }
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
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = settingsStore.trustAllCerts
        suppressTrustAllListener = false
        updateStatus()
    }

    // R6: 取消确认对话框时回退开关到未勾选，且不写入设置
    private fun revertTrustAllCerts() {
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = false
        suppressTrustAllListener = false
        settingsStore.trustAllCerts = false
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
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')
        if (!serverUrl.startsWith("https://")) {
            setStatus(getString(R.string.status_invalid_server_url))
            return false
        }
        val rounds = hashRoundsInput.text.toString().toIntOrNull()
        if (rounds == null || rounds < ClipConfig.MIN_HASH_ROUNDS || rounds > ClipConfig.MAX_HASH_ROUNDS) {
            setStatus(getString(R.string.status_invalid_hash_rounds, ClipConfig.MIN_HASH_ROUNDS, ClipConfig.MAX_HASH_ROUNDS))
            return false
        }
        val localLimit = localLimitInput.text.toString().toLongOrNull()
        if (localLimit == null || localLimit !in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_CLIPBOARD_BYTES) {
            setStatus(getString(R.string.status_invalid_local_limit))
            return false
        }
        settingsStore.serverUrl = serverUrl
        settingsStore.username = usernameInput.text.toString()
        settingsStore.hashRounds = rounds
        settingsStore.salt = saltInput.text.toString()
        settingsStore.localMaxClipboardBytes = localLimit
        settingsStore.cipherEnabled = cipherCheck.isChecked
        settingsStore.savePassword = savePasswordCheck.isChecked
        if (!savePasswordCheck.isChecked) {
            // 取消保存密码：立即清除加密保存的密码，但保留派生密钥以继续解密收件
            settingsStore.savedEncryptedPassword = ""
        }
        settingsStore.relaunchOnBoot = relaunchCheck.isChecked
        settingsStore.websocketStatusNotification = statusNotificationCheck.isChecked
        settingsStore.trustAllCerts = trustAllCertsCheck.isChecked
        return true
    }

    private fun login() {
        if (!saveEditableSettings()) return
        val typedPassword = passwordInput.text.toString()
        val savedPasswordUsed = typedPassword.isBlank()
        setBusy(true, getString(R.string.status_logging_in))
        val activityGeneration = authGeneration.incrementAndGet()
        val submitted = AuthenticationCoordinator.submit(replaceActive = true) authTask@{ requestGeneration ->
            val password = typedPassword.ifBlank {
                if (settingsStore.savePassword) settingsStore.savedEncryptedPassword else ""
            }
            val outcome = AuthenticationWorkflow(
                settings = settingsStore,
                loginClientFactory = AuthenticationDependencies.loginClientFactory,
                deriveCredentials = { value, _ ->
                    AuthenticationDependencies.deriveCredentials(settingsStore, value)
                },
                startService = { _ ->
                    if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) {
                        false
                    } else {
                        settingsStore.serviceRunning = true
                        serviceRunningUiOverride = true
                        settingsStore.statusMessage = getString(R.string.status_connecting)
                        AuthenticationDependencies.startService(this)
                        true
                    }
                },
                setStatus = {},
                isOwnerAlive = { isAuthTaskCurrent(activityGeneration, requestGeneration) }
            ).execute(
                password = password,
                savedPasswordUsed = savedPasswordUsed,
                savedPassword = if (settingsStore.savePassword) typedPassword.takeIf { it.isNotBlank() } else ""
            )

            if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
            runOnUiThread {
                val currentView = LoginViewState(
                    isLoading = true,
                    sessionPersistenceFailed = sessionPersistenceFailed,
                    serviceRunningUiOverride = serviceRunningUiOverride,
                    message = getString(R.string.status_logging_in)
                )
                val viewState = LoginOutcomeReducer.reduce(
                    outcome = outcome,
                    current = currentView,
                    currentThreadStillValid = true,
                    message = { msg ->
                        when (msg) {
                            LoginOutcomeMessage.MissingPassword -> getString(R.string.status_login_required_fields)
                            LoginOutcomeMessage.Connecting -> getString(R.string.status_connecting)
                            LoginOutcomeMessage.InvalidCredentials -> getString(R.string.status_invalid_credentials)
                            LoginOutcomeMessage.LoginRateLimited -> getString(R.string.status_login_rate_limited)
                            LoginOutcomeMessage.SessionPersistenceFailed -> getString(R.string.status_session_invalidation_persist_failed)
                            is LoginOutcomeMessage.ProtocolUnsupported -> getString(
                                R.string.status_protocol_unsupported,
                                msg.serverVersion,
                                Protocol.SUPPORTED_PROTOCOL_VERSION
                            )
                            is LoginOutcomeMessage.LoginFailed -> getString(R.string.status_login_failed, msg.detail)
                        }
                    }
                )
                sessionPersistenceFailed = viewState.sessionPersistenceFailed
                serviceRunningUiOverride = viewState.serviceRunningUiOverride
                if (viewState.clearPasswordInput) {
                    passwordInput.setText("")
                }
                if (viewState.reloadSettings) {
                    loadSettings()
                }
                setBusy(viewState.isLoading, viewState.message)
            }
        }
        if (submitted == null) {
            setBusy(false, getString(R.string.status_login_failed, "Authentication executor unavailable"))
        }
    }

    private fun logout() {
        saveEditableSettings()
        ClipForegroundService.stop(this)
        val activityGeneration = authGeneration.incrementAndGet()
        AuthenticationCoordinator.submit(replaceActive = true) authTask@{ requestGeneration ->
            try {
                if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
                if (!settingsStore.clearSession()) throw IllegalStateException("Unable to clear login session")
                if (!isAuthTaskCurrent(activityGeneration, requestGeneration)) return@authTask
                runOnUiThread {
                    loadSettings()
                    setStatus(getString(R.string.status_logged_out))
                }
            } catch (error: Throwable) {
                if (error is InterruptedException || !isAuthTaskCurrent(activityGeneration, requestGeneration)) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    return@authTask
                }
                runOnUiThread {
                    setStatus(getString(R.string.status_login_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun startServiceFromUi() {
        if (!saveEditableSettings()) return
        if (sessionPersistenceFailed) {
            setStatus(getString(R.string.status_session_invalidation_persist_failed))
            return
        }
        if (!settingsStore.hasSession || settingsStore.token.isBlank()) {
            setStatus(getString(R.string.status_login_first))
            return
        }
        ClipForegroundService.start(this)
        settingsStore.serviceRunning = true
        serviceRunningUiOverride = true
        setStatus(getString(R.string.status_connecting))
    }

    private fun stopServiceFromUi() {
        ClipForegroundService.stop(this)
        settingsStore.serviceRunning = false
        serviceRunningUiOverride = false
        setStatus(getString(R.string.status_service_stopped))
    }

    private fun saveAndReconnect() {
        if (!saveEditableSettings()) return
        val typedPassword = passwordInput.text.toString()
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
        val session = if (sessionPersistenceFailed || !settingsStore.hasSession || settingsStore.token.isBlank()) {
            getString(R.string.session_not_logged_in)
        } else {
            getString(R.string.session_logged_in)
        }
        val serviceRunning = serviceRunningUiOverride ?: settingsStore.serviceRunning
        val service = if (serviceRunning) {
            getString(R.string.service_enabled)
        } else {
            getString(R.string.service_stopped)
        }
        val websocketUrl = runCatching {
            ClipConfig.websocketUrlFromServerUrl(settingsStore.serverUrl)
        }.getOrDefault("")
        val base = getString(
            R.string.status_summary,
            settingsStore.statusMessage.ifBlank { getString(R.string.status_idle) },
            session,
            websocketUrl.ifBlank { getString(R.string.status_none) },
            service
        )
        // R3: Keystore 降级时在状态区显示警示（正常用户不触发）
        statusText.text = if (settingsStore.securityDegraded) {
            base + "\n" + getString(R.string.status_security_degraded)
        } else {
            base
        }
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

    private fun isAuthTaskCurrent(activityGeneration: Long, requestGeneration: Long): Boolean =
        activityGeneration == authGeneration.get() &&
            AuthenticationCoordinator.isCurrent(requestGeneration) &&
            !isFinishing &&
            !isDestroyed

    // R6 测试访问器
    internal fun trustAllCertsCheckboxForTest(): CheckBox = trustAllCertsCheck
}
