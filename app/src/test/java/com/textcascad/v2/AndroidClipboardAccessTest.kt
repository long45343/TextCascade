/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.textcascad.v2.engine.AndroidClipboardAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * AndroidClipboardAccess：写入读回、空剪贴板返回 null、coerceToText 无可文本化内容时按平台语义处理。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidClipboardAccessTest {

    private fun newAccess(): AndroidClipboardAccess =
        AndroidClipboardAccess(RuntimeEnvironment.getApplication())

    @Test
    fun writeThenReadReturnsWrittenText() {
        val access = newAccess()
        access.writeText("hello cascade")
        assertEquals("hello cascade", access.readText())
    }

    @Test
    fun readOnEmptyClipboardReturnsNull() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.clearPrimaryClip()
        assertNull(AndroidClipboardAccess(context).readText())
    }

    @Test
    fun readNonCoercibleClipItemReturnsEmpty() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(
            ClipData("TextCascade", arrayOf("text/plain"), ClipData.Item(null as CharSequence?))
        )
        // API 34 起 coerceToText 对无可文本化内容返回空串而非 null，引擎侧经 isNullOrBlank 等价过滤
        assertEquals("", AndroidClipboardAccess(context).readText())
    }
}
