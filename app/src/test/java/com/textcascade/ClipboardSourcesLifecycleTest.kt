/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardSourcesLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_LOGS)
    }

    private class FakeProcess(
        val stdout: InputStream,
        val stderr: InputStream
    ) : Process() {
        val destroyed = AtomicBoolean(false)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed.set(true)
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }

    @Test
    fun backoffSequenceMatchesSpecification() {
        val source = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = {}
        )
        val failuresField = ClipboardSources::class.java.getDeclaredField("consecutiveLogcatFailures").apply {
            isAccessible = true
        }
        val delayMethod = ClipboardSources::class.java.getDeclaredMethod("restartDelayForFailure").apply {
            isAccessible = true
        }
        val expected = listOf(5000L, 10000L, 20000L, 40000L, 80000L, 160000L, 30000L * 10) // 300,000

        expected.forEachIndexed { index, expDelay ->
            failuresField.setInt(source, index + 1)
            val calculated = delayMethod.invoke(source) as Long
            assertEquals(expDelay, calculated)
        }
        source.stop()
    }

    @Test
    fun consecutiveSixFailuresStopsWorker() {
        val launchCount = AtomicInteger(0)
        val statuses = mutableListOf<String>()
        val delaysRecorded = mutableListOf<Long>()
        val pauseStatusLatch = CountDownLatch(1)

        val source = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = { msg ->
                statuses.add(msg)
                if (msg.contains("paused") || msg.contains("暂停")) {
                    pauseStatusLatch.countDown()
                }
            },
            logcatProcessLauncher = {
                launchCount.incrementAndGet()
                // Process throws on stdout read
                FakeProcess(
                    stdout = ByteArrayInputStream(ByteArray(0)),
                    stderr = ByteArrayInputStream(ByteArray(0))
                )
            },
            sleepMs = { delay ->
                delaysRecorded.add(delay)
            }
        )

        source.start()
        assertTrue(pauseStatusLatch.await(5, TimeUnit.SECONDS))
        source.stop()

        // 6 process launches before stopping
        assertEquals(6, launchCount.get())
        assertEquals(5, delaysRecorded.size) // delays between 1st..6th
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 80000L), delaysRecorded)
        assertTrue(statuses.any { it.contains("paused") || it.contains("暂停") })
    }

    @Test
    fun stableOutput60sResetsFailureCount() {
        var simulatedNow = 1_000_000L
        val delaysRecorded = mutableListOf<Long>()
        val firstFailureLatch = CountDownLatch(1)
        val stableOutputLatch = CountDownLatch(1)
        val thirdFailureLatch = CountDownLatch(1)
        val launchCount = AtomicInteger(0)

        lateinit var source: ClipboardSources
        source = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = {},
            nowMs = { simulatedNow },
            logcatProcessLauncher = {
                val count = launchCount.incrementAndGet()
                if (count == 1) {
                    // First process: immediately dies (failure #1)
                    firstFailureLatch.countDown()
                    FakeProcess(
                        stdout = ByteArrayInputStream(ByteArray(0)),
                        stderr = ByteArrayInputStream(ByteArray(0))
                    )
                } else if (count == 2) {
                    val bytes = "stable output\n".toByteArray()
                    val input = object : InputStream() {
                        private var offset = 0
                        private var eofAdvanced = false

                        override fun read(): Int {
                            val one = ByteArray(1)
                            return if (read(one, 0, 1) < 0) -1 else one[0].toInt()
                        }

                        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                            if (offset < bytes.size) {
                                val count = minOf(len, bytes.size - offset)
                                bytes.copyInto(buffer, off, offset, offset + count)
                                offset += count
                                stableOutputLatch.countDown()
                                return count
                            }
                            if (!eofAdvanced) {
                                eofAdvanced = true
                                simulatedNow += 60_000L
                            }
                            return -1
                        }
                    }
                    FakeProcess(input, ByteArrayInputStream(ByteArray(0)))
                } else {
                    thirdFailureLatch.countDown()
                    FakeProcess(
                        stdout = ByteArrayInputStream(ByteArray(0)),
                        stderr = ByteArrayInputStream(ByteArray(0))
                    )
                }
            },
            sleepMs = { delay ->
                delaysRecorded.add(delay)
            }
        )

        source.start()
        assertTrue(firstFailureLatch.await(5, TimeUnit.SECONDS))
        assertTrue(stableOutputLatch.await(5, TimeUnit.SECONDS))
        assertTrue(thirdFailureLatch.await(5, TimeUnit.SECONDS))
        source.stop()

        assertEquals(listOf(5000L, 5000L), delaysRecorded.take(2))
    }

    @Test
    fun stderrContinuousOutputDoesNotBlockStdout() {
        val stdoutPipedOut = PipedOutputStream()
        val stdoutReadCount = AtomicInteger()
        val stdoutReadLatch = CountDownLatch(1)
        val stdoutPipedIn = object : PipedInputStream(stdoutPipedOut) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = super.read(buffer, off, len)
                if (count > 0) {
                    stdoutReadCount.addAndGet(count)
                    stdoutReadLatch.countDown()
                }
                return count
            }
        }

        val stderrPipedOut = PipedOutputStream()
        val stderrPipedIn = PipedInputStream(stderrPipedOut)

        val fakeProc = FakeProcess(stdoutPipedIn, stderrPipedIn)
        val source = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = {},
            logcatProcessLauncher = { fakeProc }
        )

        source.start()

        // Continuously write to stderr
        kotlin.concurrent.thread(isDaemon = true) {
            repeat(50) {
                runCatching {
                    stderrPipedOut.write("some stderr logging line\n".toByteArray())
                    stderrPipedOut.flush()
                }
            }
        }

        // Write to stdout
        stdoutPipedOut.write("clipboard ${context.packageName}\n".toByteArray())
        stdoutPipedOut.flush()

        assertTrue(stdoutReadLatch.await(5, TimeUnit.SECONDS))
        assertTrue(stdoutReadCount.get() > 0)
        source.stop()
        assertTrue(fakeProc.destroyed.get())
    }

    @Test
    fun startStopStartRapidInterleavingLeavesOnlyLatestWorkerActive() {
        val processesCreated = mutableListOf<FakeProcess>()
        val launches = AtomicInteger()
        val sleepGate = CountDownLatch(1)
        val delays = mutableListOf<Long>()
        val secondStdoutOut = PipedOutputStream()
        val secondStdoutIn = PipedInputStream(secondStdoutOut)
        val source = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = {},
            logcatProcessLauncher = {
                launches.incrementAndGet()
                val p = if (launches.get() == 1) FakeProcess(
                    stdout = ByteArrayInputStream(ByteArray(0)),
                    stderr = ByteArrayInputStream(ByteArray(0))
                ) else FakeProcess(
                    stdout = secondStdoutIn,
                    stderr = ByteArrayInputStream(ByteArray(0))
                )
                synchronized(processesCreated) { processesCreated.add(p) }
                p
            },
            sleepMs = { delay ->
                synchronized(delays) { delays.add(delay) }
                sleepGate.await(5, TimeUnit.SECONDS)
            }
        )

        source.start()
        waitUntil { launches.get() == 1 }
        source.stop()
        assertFalse(source.isLogcatWorkerAliveForTest())
        source.start()
        waitUntil { launches.get() == 2 }
        val first = synchronized(processesCreated) { processesCreated[0] }
        val second = synchronized(processesCreated) { processesCreated[1] }
        assertTrue(first.destroyed.get())
        assertFalse(second.destroyed.get())
        synchronized(delays) { assertEquals(listOf(5000L), delays) }
        assertTrue(source.isLogcatWorkerAliveForTest())
        source.stop()

        synchronized(processesCreated) {
            assertTrue(processesCreated.all { it.destroyed.get() })
        }
        assertFalse(source.isLogcatWorkerAliveForTest())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(10)
        assertTrue(condition())
    }
}
