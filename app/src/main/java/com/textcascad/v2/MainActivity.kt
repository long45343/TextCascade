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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.CheckBox
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var authDependencies: AuthenticationDependencies
    private lateinit var uiBinding: MainActivityUiBinding
    private lateinit var authController: MainActivityAuthController

    private val prefsRefreshHandler = Handler(Looper.getMainLooper())
    private val prefsRefreshRunnable = Runnable { authController.updateStatus() }

    /** 电池优化豁免检测；注入式便于 Robolectric 测试（shadow 不稳）。 */
    internal var batteryWhitelistChecker: () -> Boolean = {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authDependencies = AuthenticationDependencies()
        settingsStore = authDependencies.settingsStoreFactory(this)

        uiBinding = MainActivityUiBinding.inflate(
            activity = this,
            versionName = appVersionName(),
            onTrustAllCertsChanged = { isChecked ->
                if (isChecked) {
                    showTrustAllCertsConfirmationDialog()
                } else {
                    settingsStore.trustAllCerts = false
                }
            }
        )
        setContentView(uiBinding.root)

        authController = MainActivityAuthController(
            activity = this,
            settingsStore = settingsStore,
            uiBinding = uiBinding,
            dependencies = authDependencies
        )

        bindListeners()
        requestNotificationPermission()
        handleSharedText(intent)
        uiBinding.loadSettings(settingsStore)

        if (settingsStore.consumePasswordDecryptionFailure()) {
            settingsStore.savePassword = false
            authController.setStatus(getString(R.string.status_password_decryption_failed))
        }

        requestBatteryOptimizationExemption()
        uiBinding.updateBatteryStatus(batteryWhitelistChecker())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleSharedText(intent)
        }
    }

    override fun onResume() {
        ClipServiceController.setLogcatEnabled(this, true)
        super.onResume()
        settingsStore.registerListener(preferenceChangeListener)
        settingsStore.registerRuntimeListener(runtimeStateListener)
        if (!authController.sessionPersistenceFailed &&
            settingsStore.serviceRunning &&
            settingsStore.hasSession &&
            settingsStore.serverUrl.isNotBlank()
        ) {
            ClipServiceController.resumeReconnect(this)
        }
        authController.updateStatus()
        reconcileBatteryOptimizationState()
        uiBinding.updateBatteryStatus(batteryWhitelistChecker())
        prefsRefreshHandler.postDelayed(prefsRefreshRunnable, 2000)
    }

    override fun onPause() {
        ClipServiceController.setLogcatEnabled(this, false)
        super.onPause()
        settingsStore.unregisterListener(preferenceChangeListener)
        settingsStore.unregisterRuntimeListener(runtimeStateListener)
        prefsRefreshHandler.removeCallbacks(prefsRefreshRunnable)
    }

    override fun onDestroy() {
        authController.onDestroy()
        super.onDestroy()
    }

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in OBSERVED_KEYS) {
                authController.updateStatus()
            }
        }

    private val runtimeStateListener = object : RuntimeStateStore.Listener {
        override fun onChanged() {
            authController.updateStatus()
        }
    }

    private fun bindListeners() {
        uiBinding.loginButton.setOnClickListener { authController.login() }
        uiBinding.logoutButton.setOnClickListener { authController.logout() }
        uiBinding.overlayButton.setOnClickListener { openOverlaySettings() }
        uiBinding.batteryButton.setOnClickListener {
            // 手动入口：重新触发系统对话框并复位拒绝记忆
            settingsStore.batteryOptimizationPromptDismissed = false
            settingsStore.batteryOptimizationPromptShownAt = System.currentTimeMillis()
            requestBatteryOptimizationExemption()
            uiBinding.updateBatteryStatus(batteryWhitelistChecker())
        }
        uiBinding.savePasswordCheck.setOnCheckedChangeListener { _, _ ->
            uiBinding.updatePasswordSavedIndicator(settingsStore)
        }
    }

    /**
     * 未豁免且未被拒绝过：弹系统对话框引导电池优化白名单（Doze 会冻结前台服务定时器，
     * 是闲置后首次复制延迟的根因放大器）。可拒绝；拒绝后不重复弹，设置页保留手动入口。
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

    private fun showTrustAllCertsConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_trust_all_certs_title)
            .setMessage(R.string.dialog_trust_all_certs_message)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                settingsStore.trustAllCerts = true
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                uiBinding.revertTrustAllCerts(settingsStore)
            }
            .setOnCancelListener {
                uiBinding.revertTrustAllCerts(settingsStore)
            }
            .show()
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) {
            ClipServiceController.submitText(this, text, "share")
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

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }

    // R6 测试访问器
    internal fun trustAllCertsCheckboxForTest(): CheckBox = uiBinding.trustAllCertsCheck

    // 电池白名单行测试访问器
    internal fun uiBindingForTest(): MainActivityUiBinding = uiBinding

    companion object {
        private val OBSERVED_KEYS = setOf(
            "status_message",
            "connection_status_message",
            "background_status",
            "service_running",
            "has_session",
            "server_url",
            "security_degraded"
        )
    }
}

