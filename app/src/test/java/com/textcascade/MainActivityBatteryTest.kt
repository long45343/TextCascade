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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 电池优化白名单引导（Doze 会冻结前台服务定时器，是闲置后首次复制延迟的根因放大器）：
 * 未豁免且未拒绝 → 弹系统对话框（一次）；已拒绝 → 不再自动弹；
 * 已豁免 → 不弹且重置引导标记；主界面保留手动申请入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityBatteryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun store(): SettingsStore = SettingsStore(context)

    @Before
    fun setUp() {
        val s = store()
        s.batteryOptimizationPromptDismissed = false
        s.batteryOptimizationPromptShownAt = 0L
    }

    @After
    fun tearDown() {
        val s = store()
        s.batteryOptimizationPromptDismissed = false
        s.batteryOptimizationPromptShownAt = 0L
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(checker: () -> Boolean): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).apply {
            get().batteryWhitelistChecker = checker
            create().start().resume()
        }.get()

    private fun batteryIntents(activity: MainActivity): List<Intent> {
        val shadow = shadowOf(activity)
        val intents = mutableListOf<Intent>()
        while (true) {
            val next = shadow.nextStartedActivity ?: break
            if (next.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                intents.add(next)
            }
        }
        return intents
    }

    @Test
    fun notExemptedAndNotDismissedPromptsOnce() {
        // 场景：首次启动应用，未豁免电池优化，必须弹出系统引导对话框
        val activity = launch { false }
        idleMain()
        val intents = batteryIntents(activity)
        assertEquals(1, intents.size)
        assertEquals(Uri.parse("package:${activity.packageName}"), intents[0].data)
        // 弹过之后在 onResume 中结算为已展示/已拒绝，不再重复自动弹出
        assertTrue(store().batteryOptimizationPromptShownAt != 0L)
        assertTrue(store().batteryOptimizationPromptDismissed)
        // UI 状态行应显示未加入白名单
        assertTrue(activity.batteryStatusTextForTest().text.contains(activity.getString(R.string.battery_status_not_exempted)))
    }

    @Test
    fun dismissedDoesNotPromptAgain() {
        // 场景：用户曾经拒绝过白名单，重新打开应用不再打扰
        store().batteryOptimizationPromptDismissed = true
        val activity = launch { false }
        idleMain()
        assertTrue("已拒绝过不得再次自动弹出意图", batteryIntents(activity).isEmpty())
        assertTrue(store().batteryOptimizationPromptDismissed)
    }

    @Test
    fun exemptedDoesNotPrompt() {
        // 场景：用户已豁免电池优化，无需弹出系统引导，状态行直接显示已加入白名单
        val activity = launch { true }
        idleMain()
        assertTrue(batteryIntents(activity).isEmpty())
        assertTrue(activity.batteryStatusTextForTest().text.contains(activity.getString(R.string.battery_status_exempted)))
    }

    @Test
    fun exemptionResetsGuideMarkers() {
        // 场景：之前被拒绝过，但后来用户在系统设置里主动豁免了，打开应用应自动清空拒绝标记
        store().batteryOptimizationPromptShownAt = 123_456L
        store().batteryOptimizationPromptDismissed = true
        launch { true }
        idleMain()
        assertEquals(0L, store().batteryOptimizationPromptShownAt)
        assertFalse(store().batteryOptimizationPromptDismissed)
    }

    @Test
    fun batteryRowClickRetriggersDialogAndClearsDismissed() {
        // 场景：用户曾拒绝自动引导，但在主界面点击了手动按钮，必须重新唤起对话框并清除拒绝记忆
        store().batteryOptimizationPromptDismissed = true
        val activity = launch { false }
        idleMain()
        assertTrue(batteryIntents(activity).isEmpty())

        activity.batteryButtonForTest().performClick()
        idleMain()

        val intents = batteryIntents(activity)
        assertEquals(1, intents.size)
        assertFalse(store().batteryOptimizationPromptDismissed)
        assertTrue(store().batteryOptimizationPromptShownAt != 0L)
    }
}
