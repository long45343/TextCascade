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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private lateinit var pinnedCertInput: EditText
    private lateinit var cipherCheck: CheckBox
    private lateinit var savePasswordCheck: CheckBox
    private lateinit var relaunchCheck: CheckBox
    private lateinit var statusNotificationCheck: CheckBox
    // F5: 信任所有证书
    private lateinit var trustAllCertsCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var backgroundStatusText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var batteryButton: Button
    private var suppressTrustAllListener = false
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    // F2: 保存并重连
    private lateinit var saveReconnectButton: Button
    private var sessionMigrationStarted = false
    private val authGeneration = AtomicLong(0L)
    private var sessionPersistenceFailed = false
    private var serviceRunningUiOverride: Boolean? = null

    /** 电池优化豁免检测；注入式便于 Robolectric 测试。 */
    internal var batteryWhitelistChecker: () -> Boolean = {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = AuthenticationDependencies.settingsStoreFactory(this)
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

        requestBatteryOptimizationExemption()
        updateBatteryStatus(batteryWhitelistChecker())
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
        if (!sessionPersistenceFailed && settingsStore.serviceRunning &&
            settingsStore.websocketUrl.isNotBlank() &&
            settingsStore.hasSession
        ) {
            ClipForegroundService.resumeReconnect(this)
        }
        migrateLegacySessionIfNeeded()
        updateStatus()
        reconcileBatteryOptimizationState()
        updateBatteryStatus(batteryWhitelistChecker())
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
        if (key in listOf("status_message", "service_running", "has_session", "websocket_url", "pinned_cert_sha256", "background_status", "security_degraded")) {
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
        pinnedCertInput = input(getString(R.string.hint_pinned_cert_sha256), singleLine = true)

        listOf(serverUrlInput, usernameInput, passwordInput, hashRoundsInput, saltInput, localLimitInput, pinnedCertInput)
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
        trustAllCertsCheck.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressTrustAllListener) {
                if (isChecked) {
                    showTrustAllCertsConfirmationDialog()
                } else {
                    settingsStore.trustAllCerts = false
                }
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

        batteryStatusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 20, 0, 4)
        }
        form.addView(batteryStatusText)

        batteryButton = button(getString(R.string.button_request_battery_whitelist)).apply {
            setOnClickListener {
                // 手动入口：复位拒绝记忆并重新触发系统对话框
                settingsStore.batteryOptimizationPromptDismissed = false
                settingsStore.batteryOptimizationPromptShownAt = System.currentTimeMillis()
                requestBatteryOptimizationExemption()
                updateBatteryStatus(batteryWhitelistChecker())
            }
        }
        form.addView(batteryButton)

        backgroundStatusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        form.addView(backgroundStatusText)

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 0)
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
        pinnedCertInput.setText(settingsStore.pinnedCertSha256)
        cipherCheck.isChecked = settingsStore.cipherEnabled
        savePasswordCheck.isChecked = settingsStore.savePassword
        updatePasswordSavedIndicator()
        relaunchCheck.isChecked = settingsStore.relaunchOnBoot
        statusNotificationCheck.isChecked = settingsStore.websocketStatusNotification
        // F5
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = settingsStore.trustAllCerts
        suppressTrustAllListener = false
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
        val localLimit = localLimitInput.text.toString().toLongOrNull()
        if (localLimit == null || localLimit !in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_CLIPBOARD_BYTES) {
            setStatus(getString(R.string.status_invalid_local_limit))
            return false
        }
        settingsStore.serverUrl = serverUrlInput.text.toString()
        settingsStore.username = usernameInput.text.toString()
        settingsStore.hashRounds = rounds
        settingsStore.salt = saltInput.text.toString()
        settingsStore.localMaxClipboardBytes = localLimit
        settingsStore.pinnedCertSha256 = pinnedCertInput.text.toString().trim()
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
                deriveCredentials = { value, usedSavedPassword ->
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
                when (outcome) {
                    AuthenticationOutcome.Cancelled -> Unit
                    AuthenticationOutcome.MissingPassword -> {
                        setBusy(false, getString(R.string.status_login_required_fields))
                    }
                    is AuthenticationOutcome.Success -> {
                        sessionPersistenceFailed = false
                        serviceRunningUiOverride = true
                        passwordInput.setText("")
                        loadSettings()
                        setBusy(false, getString(R.string.status_connecting))
                    }
                    is AuthenticationOutcome.PersistenceFailure -> {
                        sessionPersistenceFailed = !outcome.invalidationPersisted
                        serviceRunningUiOverride = false
                        val message = if (outcome.invalidationPersisted) {
                            getString(R.string.status_login_failed, outcome.error.message ?: outcome.error.javaClass.simpleName)
                        } else {
                            getString(R.string.status_session_invalidation_persist_failed)
                        }
                        setBusy(false, message)
                    }
                    is AuthenticationOutcome.Rejected -> {
                        if (!outcome.invalidationPersisted) {
                            sessionPersistenceFailed = true
                            serviceRunningUiOverride = false
                            setBusy(false, getString(R.string.status_session_invalidation_persist_failed))
                        } else {
                            sessionPersistenceFailed = false
                            serviceRunningUiOverride = false
                            setBusy(false, getString(R.string.status_login_failed, outcome.error.message ?: outcome.error.javaClass.simpleName))
                        }
                    }
                    is AuthenticationOutcome.Failed -> {
                        sessionPersistenceFailed = false
                        serviceRunningUiOverride = false
                        setBusy(false, getString(R.string.status_login_failed, outcome.error.message ?: outcome.error.javaClass.simpleName))
                    }
                }
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
                ClipApiClient(settingsStore.trustAllCerts).logout(
                    settingsStore.serverUrl,
                    settingsStore.cookieHeader,
                    settingsStore.csrfToken
                )
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
        if (!settingsStore.hasSession || settingsStore.websocketUrl.isBlank()) {
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
        val isServiceRunning = serviceRunningUiOverride ?: settingsStore.serviceRunning
        val isSessionValid = !sessionPersistenceFailed && settingsStore.hasSession && settingsStore.websocketUrl.isNotBlank()
        val connMessage = settingsStore.statusMessage.ifBlank { getString(R.string.status_idle) }
        val isConnected = connMessage == getString(R.string.status_connected) ||
            isTransientActionMessage(connMessage)
        val isSecure = !settingsStore.securityDegraded

        val isAllNormal = isServiceRunning && isSessionValid && isConnected && isSecure

        if (isAllNormal) {
            if (isTransientActionMessage(connMessage)) {
                statusText.text = getString(R.string.status_prefix_connection, connMessage)
            } else {
                statusText.text = "${getString(R.string.status_all_normal)} · ${getString(R.string.session_logged_in)}"
            }
        } else {
            val abnormalParts = mutableListOf<String>()
            if (!isServiceRunning) {
                abnormalParts.add(getString(R.string.status_prefix_service, getString(R.string.service_stopped)))
            }
            val sessionText = if (isSessionValid) getString(R.string.session_logged_in) else getString(R.string.session_not_logged_in)
            abnormalParts.add(getString(R.string.status_prefix_session, sessionText))
            if (!isConnected) {
                abnormalParts.add(getString(R.string.status_prefix_connection, connMessage))
            } else if (!isServiceRunning || !isSessionValid) {
                abnormalParts.add(getString(R.string.status_prefix_connection, connMessage))
            }
            if (!isSecure) {
                abnormalParts.add(getString(R.string.status_security_degraded))
            }
            statusText.text = abnormalParts.joinToString("\n")
        }

        val bgStatusName = settingsStore.backgroundStatus
        val bgText = when (bgStatusName) {
            BackgroundStatus.ACTIVE.name -> getString(R.string.background_status_active)
            BackgroundStatus.INACTIVE.name -> getString(R.string.background_status_inactive)
            BackgroundStatus.READ_LOGS_NOT_GRANTED.name -> getString(R.string.background_status_read_logs_not_granted)
            BackgroundStatus.DETECTING.name, "" -> getString(R.string.background_status_detecting)
            else -> bgStatusName
        }
        backgroundStatusText.text = getString(R.string.background_status_summary, bgText)
    }

    private fun isTransientActionMessage(message: String): Boolean =
        message == getString(R.string.status_connected_broadcasting) ||
        message == getString(R.string.status_remote_text_copied) ||
        message == "Connected - broadcasting" ||
        message == "Remote text copied"

    private fun showTrustAllCertsConfirmationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_trust_all_certs_title)
            .setMessage(R.string.dialog_trust_all_certs_message)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                settingsStore.trustAllCerts = true
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                revertTrustAllCerts()
            }
            .setOnCancelListener {
                revertTrustAllCerts()
            }
            .show()
    }

    private fun revertTrustAllCerts() {
        suppressTrustAllListener = true
        trustAllCertsCheck.isChecked = false
        settingsStore.trustAllCerts = false
        suppressTrustAllListener = false
    }

    internal fun trustAllCertsCheckForTest(): CheckBox = trustAllCertsCheck
    internal fun pinnedCertInputForTest(): EditText = pinnedCertInput
    internal fun backgroundStatusTextForTest(): TextView = backgroundStatusText
    internal fun statusTextForTest(): TextView = statusText

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

    private fun migrateLegacySessionIfNeeded() {
        if (sessionMigrationStarted || settingsStore.hasSession || settingsStore.websocketUrl.isBlank()) return
        sessionMigrationStarted = true
        thread(name = "textcascade-session-migration", isDaemon = true) {
            if (settingsStore.cookieHeader.isNotBlank()) {
                settingsStore.hasSession = true
                runOnUiThread { updateStatus() }
            }
        }
    }

    private fun isAuthTaskCurrent(activityGeneration: Long, requestGeneration: Long): Boolean =
        activityGeneration == authGeneration.get() &&
            AuthenticationCoordinator.isCurrent(requestGeneration) &&
            !isFinishing &&
            !isDestroyed

    /** 更新电池优化白名单状态文案与按钮。 */
    internal fun updateBatteryStatus(exempted: Boolean) {
        batteryStatusText.text = if (exempted) {
            getString(R.string.battery_status_exempted)
        } else {
            getString(R.string.battery_status_not_exempted)
        }
        batteryButton.text = getString(
            if (exempted) R.string.button_open_battery_settings else R.string.button_request_battery_whitelist
        )
    }

    /**
     * 未豁免且未被拒绝过：弹系统对话框引导电池优化白名单（Doze 会冻结前台服务定时器，
     * 是闲置后首次复制延迟的根因放大器）。可拒绝；拒绝后不重复弹，主界面保留手动入口。
     */
    private fun requestBatteryOptimizationExemption() {
        if (batteryWhitelistChecker() || settingsStore.batteryOptimizationPromptDismissed) {
            return
        }
        settingsStore.batteryOptimizationPromptShownAt = System.currentTimeMillis()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    /**
     * onResume 结算：已豁免 → 重置引导状态（清弹窗/拒绝标记）；
     * 曾弹过仍未豁免 → 视为拒绝，不再自动弹出。
     */
    private fun reconcileBatteryOptimizationState() {
        if (batteryWhitelistChecker()) {
            settingsStore.batteryOptimizationPromptShownAt = 0L
            settingsStore.batteryOptimizationPromptDismissed = false
        } else if (settingsStore.batteryOptimizationPromptShownAt != 0L) {
            settingsStore.batteryOptimizationPromptDismissed = true
        }
    }

    internal fun batteryButtonForTest(): Button = batteryButton
    internal fun batteryStatusTextForTest(): TextView = batteryStatusText
}
