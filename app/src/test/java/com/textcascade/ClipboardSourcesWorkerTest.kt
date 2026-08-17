/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardSourcesWorkerTest {
    @Test
    fun restartDelayUsesExponentialBackoffAndFiveMinuteCap() {
        val source = newSource()
        val failures = ClipboardSources::class.java.getDeclaredField("consecutiveLogcatFailures").apply {
            isAccessible = true
        }
        val delay = ClipboardSources::class.java.getDeclaredMethod("restartDelayForFailure").apply {
            isAccessible = true
        }
        val expected = listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L)

        expected.forEachIndexed { index, expectedDelay ->
            failures.setInt(source, index + 1)
            assertEquals(expectedDelay, delay.invoke(source))
        }
    }

    @Test
    fun repeatedFailureMessagesAreThrottledAtomically() {
        var now = 0L
        val statuses = mutableListOf<String>()
        val source = ClipboardSources(
            context = ApplicationProvider.getApplicationContext<Context>(),
            callback = { _, _ -> },
            status = { statuses += it },
            nowMs = { now }
        )
        val report = ClipboardSources::class.java.getDeclaredMethod(
            "reportFailure",
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }

        report.invoke(source, 0L, "same")
        report.invoke(source, 0L, "same")
        assertEquals(1, statuses.size)

        report.invoke(source, 0L, "different")
        assertEquals(2, statuses.size)
        now = 30_000L
        report.invoke(source, 0L, "same")
        assertEquals(3, statuses.size)
        assertTrue(statuses.all { it.isNotBlank() })
    }

    private fun newSource(): ClipboardSources = ClipboardSources(
        context = ApplicationProvider.getApplicationContext(),
        callback = { _, _ -> },
        status = {}
    )
}
