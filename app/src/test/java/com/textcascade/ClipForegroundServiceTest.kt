package com.textcascade

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipForegroundServiceTest {

    @Test
    fun saveReconnectIncludesPasswordExtraWhenProvided() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ClipForegroundService.saveReconnect(app, "new-password")
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals("com.textcascade.SAVE_RECONNECT", intent.action)
        assertEquals("new-password", intent.getStringExtra("password"))
    }

    @Test
    fun saveReconnectOmitsPasswordExtraWhenBlank() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ClipForegroundService.saveReconnect(app)
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals("com.textcascade.SAVE_RECONNECT", intent.action)
        assertNull(intent.getStringExtra("password"))
    }
}
