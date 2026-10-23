/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.textcascade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat 触发器行匹配。每条用例对应一个真实出现过的日志行形态：
 * 命中意味着会拉起 ClipboardFloatingActivity 去读剪贴板，
 * 误命中意味着后台无辜弹窗并多跑一次剪贴板读取。
 */
class ClipboardDenialLogTest {

    private val pkg = "com.textcascade"

    @Test
    fun aospBackgroundDenialTriggers() {
        // 场景：Android 10+ 应用在后台访问剪贴板，system_server 打印拒绝
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.textcascade, package is not in focus",
                pkg
            )
        )
    }

    @Test
    fun samsungDenialTriggers() {
        // 场景：三星 ROM，SemClipboardService 拒绝后台读取
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/SemClipboardService: Denying clipboard access to com.textcascade",
                pkg
            )
        )
    }

    @Test
    fun xiaomiDenialTriggers() {
        // 场景：MIUI，MiuiClipboardService 打印拒绝
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/MiuiClipboardService: clipboard access denied for com.textcascade",
                pkg
            )
        )
    }

    @Test
    fun huaweiDenialTriggers() {
        // 场景：EMUI，HwClipboardService 打印拒绝
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/HwClipboardService: com.textcascade clipboard access rejected",
                pkg
            )
        )
    }

    @Test
    fun otherPackageDenialIsIgnored() {
        // 场景：另一个应用被拒，与本应用无关
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.other.app, package is not in focus",
                pkg
            )
        )
    }

    @Test
    fun processStartMentioningPackageDoesNotTrigger() {
        // 场景（v1 回归点）：系统启动本进程时打印包名，旧实现仅凭包名就命中，
        // 会在每次后台拉起进程时误弹悬浮窗。
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "I/ActivityManager: Start proc 12345:com.textcascade/u0a123 for broadcast",
                pkg
            )
        )
    }

    @Test
    fun logcatWithoutClipboardOrDenialDoesNotTrigger() {
        // 场景：包含本应用包名的普通组件调用，不含任何剪贴板或拒绝关键字，不得误拉起悬浮窗
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "D/ViewRootImpl[MainActivity]: ViewPostIme pointer 0 action 0 for com.textcascade",
                pkg
            )
        )
    }

    @Test
    fun denialWithoutTargetPackageDoesNotTrigger() {
        // 场景：拒绝日志里只有 UID 没有包名，无法确认是本应用
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to uid 10123",
                pkg
            )
        )
    }

    @Test
    fun emptyLineDoesNotTrigger() {
        assertFalse(ClipboardSources.isClipboardDenialLog("", pkg))
    }

    @Test
    fun packagePrefixOfLongerNameIsNotTreatedAsMatch() {
        // 场景：拒绝日志里出现的是 com.textcascade.pro（另一个应用），
        // 它的名字以本包名开头。纯 contains 会误判成本应用并多拉一次悬浮窗。
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.textcascade.pro",
                pkg
            )
        )
    }

    @Test
    fun packageFollowedByPunctuationTriggers() {
        // 场景：包名后紧跟逗号/括号等常见分隔符，必须仍然命中
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.textcascade.",
                pkg
            )
        )
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: [com.textcascade] Denying clipboard access",
                pkg
            )
        )
    }
}
