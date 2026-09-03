/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Q4 电池优化白名单引导：未豁免且未拒绝 → 弹系统对话框（一次）；
 * 已拒绝 → 不再弹；已豁免 → 不弹且重置引导标记。
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityBatteryTest {

    private fun store(): SettingsStore = SettingsStore(RuntimeEnvironment.getApplication())

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(checker: () -> Boolean) =
        Robolectric.buildActivity(MainActivity::class.java).apply {
            get().batteryWhitelistChecker = checker
            create().start().resume()
        }.get()

    private fun startedIntents(activity: MainActivity): List<Intent> {
        val shadow = shadowOf(activity)
        val intents = mutableListOf<Intent>()
        while (true) {
            val next = shadow.nextStartedActivity ?: break
            intents.add(next)
        }
        return intents
    }

    /** 队列里会混入通知权限请求 intent，只看电池白名单对话框。 */
    private fun batteryIntents(activity: MainActivity): List<Intent> =
        startedIntents(activity).filter { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }

    @Test
    fun notExemptedAndNotDismissedPromptsOnce() {
        val activity = launch { false }
        idleMain()
        val intents = batteryIntents(activity)
        assertEquals(1, intents.size)
        assertEquals(Uri.parse("package:${activity.packageName}"), intents[0].data)
        // 弹过之后仍未豁免 → 记为已拒绝，不再重复自动弹出
        assertTrue(store().batteryOptimizationPromptShownAt != 0L)
        assertTrue(store().batteryOptimizationPromptDismissed)
    }

    @Test
    fun dismissedDoesNotPromptAgain() {
        store().batteryOptimizationPromptDismissed = true
        val activity = launch { false }
        idleMain()
        assertTrue(batteryIntents(activity).isEmpty())
        assertTrue(store().batteryOptimizationPromptDismissed)
    }

    @Test
    fun exemptedDoesNotPrompt() {
        val activity = launch { true }
        idleMain()
        assertTrue(batteryIntents(activity).isEmpty())
    }

    @Test
    fun exemptionResetsGuideMarkers() {
        store().batteryOptimizationPromptShownAt = 123_456L
        store().batteryOptimizationPromptDismissed = true
        launch { true }
        idleMain()
        assertEquals(0L, store().batteryOptimizationPromptShownAt)
        assertFalse(store().batteryOptimizationPromptDismissed)
    }

    @Test
    fun batteryRowClickRetriggersDialogAndClearsDismissed() {
        store().batteryOptimizationPromptDismissed = true
        val activity = launch { false }
        idleMain()
        assertTrue(batteryIntents(activity).isEmpty())
        // 手动入口：清拒绝记忆并重新触发系统对话框
        activity.uiBindingForTest().batteryButton.performClick()
        idleMain()
        val intents = batteryIntents(activity)
        assertEquals(1, intents.size)
        assertFalse(store().batteryOptimizationPromptDismissed)
        assertTrue(store().batteryOptimizationPromptShownAt != 0L)
    }
}
