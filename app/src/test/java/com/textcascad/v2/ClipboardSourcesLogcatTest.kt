/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSourcesLogcatTest {

    @Test
    fun isClipboardDenialLogMatchesExpectedDenials() {
        val pkg = "com.textcascad.v2"
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.textcascad.v2, package is in background",
                pkg
            )
        )
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/SemClipboardService: com.textcascad.v2 clipboard access rejected",
                pkg
            )
        )
        assertTrue(
            ClipboardSources.isClipboardDenialLog(
                "E/MiuiClipboardService: [Security] com.textcascad.v2 requested clipboard",
                pkg
            )
        )
    }

    @Test
    fun isClipboardDenialLogRejectsUnrelatedLogs() {
        val pkg = "com.textcascad.v2"
        // Other package
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "E/ClipboardService: Denying clipboard access to com.other.app",
                pkg
            )
        )
        // Same package but not clipboard
        assertFalse(
            ClipboardSources.isClipboardDenialLog(
                "I/ActivityManager: Start proc com.textcascad.v2 for activity",
                pkg
            )
        )
    }
}

