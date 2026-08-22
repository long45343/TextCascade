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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CheckBox
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var settingsStore: SettingsStore
    private lateinit var authDependencies: AuthenticationDependencies
    private lateinit var uiBinding: MainActivityUiBinding
    private lateinit var authController: MainActivityAuthController

    private val prefsRefreshHandler = Handler(Looper.getMainLooper())
    private val prefsRefreshRunnable = Runnable { authController.updateStatus() }

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
        settingsStore.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        if (!authController.sessionPersistenceFailed &&
            settingsStore.serviceRunning &&
            settingsStore.hasSession &&
            settingsStore.serverUrl.isNotBlank()
        ) {
            ClipServiceController.resumeReconnect(this)
        }
        authController.updateStatus()
        prefsRefreshHandler.postDelayed(prefsRefreshRunnable, 2000)
    }

    override fun onPause() {
        ClipServiceController.setLogcatEnabled(this, false)
        super.onPause()
        settingsStore.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        prefsRefreshHandler.removeCallbacks(prefsRefreshRunnable)
    }

    override fun onDestroy() {
        authController.onDestroy()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key in listOf("status_message", "service_running", "has_session", "server_url")) {
            authController.updateStatus()
        }
    }

    private fun bindListeners() {
        uiBinding.loginButton.setOnClickListener { authController.login() }
        uiBinding.logoutButton.setOnClickListener { authController.logout() }
        uiBinding.startButton.setOnClickListener { authController.startServiceFromUi() }
        uiBinding.stopButton.setOnClickListener { authController.stopServiceFromUi() }
        uiBinding.saveReconnectButton.setOnClickListener { authController.saveAndReconnect() }
        uiBinding.overlayButton.setOnClickListener { openOverlaySettings() }
        uiBinding.savePasswordCheck.setOnCheckedChangeListener { _, _ ->
            uiBinding.updatePasswordSavedIndicator(settingsStore)
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
}

