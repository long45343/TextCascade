/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.os.Looper
import android.widget.CheckBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.Shadows.shadowOf

/**
 * R6: 勾选「信任所有证书」弹确认对话框；确认写入设置，取消回退开关状态。
 */
@RunWith(RobolectricTestRunner::class)
class TrustAllCertsDialogTest {

    private fun launchActivity(): MainActivity {
        AuthenticationDependencies.reset()
        // 推进到 resume 以便 AlertDialog 可显示
        return Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog(): android.app.AlertDialog =
        assertNotNull("确认对话框应弹出", ShadowAlertDialog.getLatestAlertDialog()).let {
            ShadowAlertDialog.getLatestAlertDialog()!!
        }

    @Test
    fun confirmDialogWritesTrustAllCerts() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckboxForTest()
        assertFalse(checkbox.isChecked)
        // 用户勾选 → 弹出确认对话框
        checkbox.performClick()
        idleMain()
        val dialog = latestDialog()
        assertTrue("确认对话框应弹出", dialog.isShowing)
        // 点击确认
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertTrue(checkbox.isChecked)
        assertTrue(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }

    @Test
    fun cancelDialogRevertsCheckbox() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckboxForTest()
        assertFalse(checkbox.isChecked)
        // 用户勾选 → 弹出确认对话框
        checkbox.performClick()
        idleMain()
        val dialog = latestDialog()
        assertTrue("确认对话框应弹出", dialog.isShowing)
        // 点击取消 → 开关回退，设置不写入
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMain()
        assertFalse(checkbox.isChecked)
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }

    @Test
    fun uncheckingWhenAlreadyOnClearsSettingWithoutDialog() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckboxForTest()
        // 先确认开启
        checkbox.performClick()
        idleMain()
        latestDialog()
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertTrue(checkbox.isChecked)
        // 再取消勾选：不弹对话框，直接清除设置
        checkbox.performClick()
        idleMain()
        assertFalse(checkbox.isChecked)
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }
}
