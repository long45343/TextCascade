/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
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

package com.textcascade

import android.app.AlertDialog
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf

/**
 * 信任所有证书安全确认对话框：
 * 勾选「信任所有证书」弹确认对话框；确认写入设置，取消或关闭回退开关状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustAllCertsDialogTest {

    private fun launchActivity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("确认对话框应弹出", dialog)
        return dialog as AlertDialog
    }

    @Test
    fun confirmDialogWritesTrustAllCerts() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckForTest()
        assertFalse(checkbox.isChecked)

        // 用户勾选 → 弹出确认对话框
        checkbox.performClick()
        idleMain()

        val dialog = latestDialog()
        assertTrue("确认对话框应处于显示状态", dialog.isShowing)

        // 点击确认
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()

        assertTrue(checkbox.isChecked)
        assertTrue(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }

    @Test
    fun cancelDialogRevertsCheckbox() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckForTest()
        assertFalse(checkbox.isChecked)

        // 用户勾选 → 弹出确认对话框
        checkbox.performClick()
        idleMain()

        val dialog = latestDialog()
        assertTrue("确认对话框应处于显示状态", dialog.isShowing)

        // 点击取消 → 开关回退，设置不写入
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMain()

        assertFalse(checkbox.isChecked)
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }

    @Test
    fun dismissDialogRevertsCheckbox() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckForTest()

        checkbox.performClick()
        idleMain()

        val dialog = latestDialog()
        dialog.cancel()
        idleMain()

        assertFalse("取消后复选框必须回弹", checkbox.isChecked)
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }

    @Test
    fun uncheckingWhenAlreadyOnClearsSettingWithoutDialog() {
        val activity = launchActivity()
        val checkbox = activity.trustAllCertsCheckForTest()

        // 先确认开启
        checkbox.performClick()
        idleMain()
        latestDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertTrue(checkbox.isChecked)

        // 再取消勾选：不弹对话框，直接清除设置
        checkbox.performClick()
        idleMain()
        assertFalse(checkbox.isChecked)
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).trustAllCerts)
    }
}
