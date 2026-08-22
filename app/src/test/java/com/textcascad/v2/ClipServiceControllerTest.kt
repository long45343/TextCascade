/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClipServiceControllerTest {

    @Test
    fun startDispatchesStartAction() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.start(context)
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_START, nextService.action)
    }

    @Test
    fun stopDispatchesStopAction() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.stop(context)
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_STOP, nextService.action)
    }

    @Test
    fun resumeReconnectDispatchesResumeAction() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.resumeReconnect(context)
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_RESUME_RECONNECT, nextService.action)
    }

    @Test
    fun submitTextDispatchesExtraTextAndSource() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.submitText(context, "copied-text", "share")
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_SUBMIT_TEXT, nextService.action)
        assertEquals("copied-text", nextService.getStringExtra(ClipServiceController.EXTRA_TEXT))
        assertEquals("share", nextService.getStringExtra(ClipServiceController.EXTRA_SOURCE))
    }

    @Test
    fun saveReconnectDispatchesPasswordIfProvided() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.saveReconnect(context, "my-secret-pass")
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_SAVE_RECONNECT, nextService.action)
        assertEquals("my-secret-pass", nextService.getStringExtra(ClipServiceController.EXTRA_PASSWORD))
    }

    @Test
    fun setLogcatEnabledDispatchesFlag() {
        val context = RuntimeEnvironment.getApplication()
        ClipServiceController.setLogcatEnabled(context, false)
        val nextService = shadowOf(context).nextStartedService
        assertNotNull(nextService)
        assertEquals(ClipServiceController.ACTION_LOGCAT_ENABLED, nextService.action)
        assertEquals(false, nextService.getBooleanExtra(ClipServiceController.EXTRA_LOGCAT_ENABLED, true))
    }
}

