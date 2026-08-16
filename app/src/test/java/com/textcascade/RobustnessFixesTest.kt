/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
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
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobustnessFixesTest {

    // T1: RB1 / NR3 watchdog 线程不随实例泄漏
    @Test
    fun rb1_watchdogThreadNotLeakedAcrossInstances() {
        val dummyListener = object : RawWebSocketClient.Listener {
            override fun onOpen() {}
            override fun onText(text: String) {}
            override fun onClosed(reason: String) {}
            override fun onError(error: Throwable) {}
        }

        val startWatchdogMethod = RawWebSocketClient::class.java.getDeclaredMethod("startWatchdog").apply {
            isAccessible = true
        }
        val stopWatchdogMethod = RawWebSocketClient::class.java.getDeclaredMethod("stopWatchdog").apply {
            isAccessible = true
        }
        val watchdogFutureField = RawWebSocketClient::class.java.getDeclaredField("watchdogFuture").apply {
            isAccessible = true
        }

        val clients = (1..5).map {
            RawWebSocketClient("ws://127.0.0.1:8080/ws", "session=1", dummyListener)
        }

        try {
            // 真实启动所有客户端的 watchdog 任务
            clients.forEach { client ->
                startWatchdogMethod.invoke(client)
                val future = watchdogFutureField.get(client) as? Future<*>
                assertNotNull("Watchdog task must be scheduled", future)
                assertFalse("Watchdog task must not be cancelled", future!!.isCancelled)
            }

            // 获取当前活动线程并统计 textcascade-watchdog 线程
            val threadGroup = Thread.currentThread().threadGroup ?: return
            val threads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2 + 10)
            val count = threadGroup.enumerate(threads)
            val watchdogThreads = (0 until count).mapNotNull { threads[it] }
                .filter { it.isAlive && it.name == "textcascade-watchdog" }

            assertTrue("Watchdog threads should not exceed 1 across multiple instances", watchdogThreads.size <= 1)
        } finally {
            // 停止所有客户端 watchdog 任务
            clients.forEach { client ->
                stopWatchdogMethod.invoke(client)
                val future = watchdogFutureField.get(client) as? Future<*>
                assertTrue("Watchdog future should be cancelled or null after stop", future == null || future.isCancelled)
                client.close()
            }
        }
    }

    // RB2 / NR1 / NR2: 非法 AES key 出站不崩溃且不更新 previousHash
    @Test
    fun rb2_outboundInvalidKeyDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(
            cipherEnabled = true,
            hashedPasswordBase64 = "invalid-base64-key"
        )
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val sentBodies = Collections.synchronizedList(mutableListOf<String>())
        val uncaughtExceptions = Collections.synchronizedList(mutableListOf<Throwable>())

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            uncaughtExceptions.add(throwable)
        }

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {
                    statuses.add(message)
                }
                override fun onRemoteTextApplied(text: String) {}
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onConnected()
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {
                        sentBodies.add(body)
                    }
                    override fun close() {}
                }
            }
        )

        try {
            engine.start()
            awaitCondition { engine.isConnected }

            statuses.clear()
            sentBodies.clear()

            // 发送加密消息（由于 key 非法，加密失败应报告错误状态，不应向外抛出未捕获异常）
            engine.sendLocalText("invalid-key-test", "clipboard")

            val expectedPrefix = context.getString(R.string.status_websocket_error, "").substringBefore("%s").trim()
            awaitCondition { statuses.any { it.startsWith(expectedPrefix) || it.contains("error", ignoreCase = true) || it.contains("错误") } }

            assertTrue(
                "Status must log websocket/encryption error",
                statuses.any { it.startsWith(expectedPrefix) || it.contains("error", ignoreCase = true) || it.contains("错误") }
            )
            assertTrue("Sent bodies should be empty on encryption error", sentBodies.isEmpty())
            assertTrue("DefaultUncaughtExceptionHandler must not receive any exceptions", uncaughtExceptions.isEmpty())

            // 再次发送相同文本，验证 previousHash 未被错误提交，依然能触发处理
            val statusCountBefore = statuses.size
            engine.sendLocalText("invalid-key-test", "clipboard")
            awaitCondition { statuses.size > statusCountBefore }
            assertEquals("Second attempt must not send body either", 0, sentBodies.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            engine.stop()
        }
    }

    // T2: RB3 远端写剪贴板失败回滚状态
    @Test
    fun rb3_remoteClipboardFailureRollsBackState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(cipherEnabled = false)
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val appliedTexts = Collections.synchronizedList(mutableListOf<String>())
        val sentBodies = Collections.synchronizedList(mutableListOf<String>())

        val failClipboardWrite = AtomicBoolean(true)

        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {
                    statuses.add(message)
                }
                override fun onRemoteTextApplied(text: String) {
                    appliedTexts.add(text)
                }
            },
            stompClientFactory = { _, _, listener, _ ->
                object : StompTransport {
                    override fun connect() {
                        listener.onConnected()
                    }
                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {
                        sentBodies.add(body)
                    }
                    override fun close() {}
                }
            },
            clipboardWriter = { text ->
                if (failClipboardWrite.get()) {
                    throw RuntimeException("Simulated clipboard manager failure")
                }
            }
        )

        try {
            engine.start()
            awaitCondition { engine.isConnected }

            // 1. 远端发来消息，但剪贴板写入失败
            val inboundMsg = JsonUtil.clipMessage("Remote text", "text")
            engine.onMessage(inboundMsg)

            awaitCondition { statuses.any { it.contains("Inbound error") || it.contains("入站错误") || it.contains("Simulated") } }
            assertTrue("onRemoteTextApplied should not be called on failure", appliedTexts.isEmpty())

            // 2. 校验状态回滚：后续本地事件不会被错误的 suppressNextLocal 吞掉
            engine.sendLocalText("Local text", "clipboard")
            awaitCondition { sentBodies.isNotEmpty() }
            assertEquals(1, sentBodies.size)
            assertTrue(sentBodies[0].contains("Local text"))

            // 3. 恢复剪贴板写入能力，再次接收相同的远端消息，应成功应用
            failClipboardWrite.set(false)
            engine.onMessage(inboundMsg)
            awaitCondition { appliedTexts.isNotEmpty() }
            assertEquals(listOf("Remote text"), appliedTexts)
        } finally {
            engine.stop()
        }
    }

    // T3: RB4 前台服务停止路径履行契约
    @Test
    fun rb4_foregroundServiceStopPathAndSafety() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.clearSession()
        store.savePassword = false
        store.savedEncryptedPassword = ""
        store.serviceRunning = true

        val serviceController = Robolectric.buildService(ClipForegroundService::class.java)
        val service = serviceController.create().get()

        val stopIntent = Intent(context, ClipForegroundService::class.java).setAction("com.textcascade.STOP")
        service.onStartCommand(stopIntent, 0, 1)

        assertFalse("serviceRunning should be false after STOP", store.serviceRunning)

        serviceController.destroy()
    }

    // T4: RW2 / NR4 出站写帧与关闭互斥
    @Test
    fun rw2_sendFrameAndCloseSocketAreMutuallyExclusive() {
        // 静态反射校验：sendFrame, writeHandshake, closeSocket 必须带有 @Synchronized (ACC_SYNCHRONIZED)
        val sendFrameMethod = RawWebSocketClient::class.java.declaredMethods.first { it.name.startsWith("sendFrame") }
        val closeSocketMethod = RawWebSocketClient::class.java.declaredMethods.first { it.name.startsWith("closeSocket") }
        val writeHandshakeMethod = RawWebSocketClient::class.java.declaredMethods.first { it.name.startsWith("writeHandshake") }

        assertTrue("sendFrame must be synchronized", Modifier.isSynchronized(sendFrameMethod.modifiers))
        assertTrue("closeSocket must be synchronized", Modifier.isSynchronized(closeSocketMethod.modifiers))
        assertTrue("writeHandshake must be synchronized", Modifier.isSynchronized(writeHandshakeMethod.modifiers))

        // 构造阻塞式输出流验证真实互斥
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)

        val blockingOutput = object : OutputStream() {
            override fun write(b: Int) {
                writeEntered.countDown()
                if (!allowWrite.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Timeout waiting for allowWrite latch")
                }
            }
        }

        val client = RawWebSocketClient(
            url = "ws://127.0.0.1:8080/ws",
            cookieHeader = "test",
            listener = object : RawWebSocketClient.Listener {
                override fun onOpen() {}
                override fun onText(text: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) {}
            }
        )

        val outputField = RawWebSocketClient::class.java.getDeclaredField("output").apply { isAccessible = true }
        outputField.set(client, BufferedOutputStream(blockingOutput))

        val closeFinished = AtomicBoolean(false)
        val threadExceptions = Collections.synchronizedList(mutableListOf<Throwable>())

        val threadA = Thread {
            try {
                client.sendFrame(0x1, ByteArray(1))
            } catch (t: Throwable) {
                threadExceptions.add(t)
            }
        }

        val threadB = Thread {
            try {
                // 等待 A 进入 sendFrame 后执行 closeSocket()
                writeEntered.await(5, TimeUnit.SECONDS)
                client.closeSocket()
                closeFinished.set(true)
            } catch (t: Throwable) {
                threadExceptions.add(t)
            }
        }

        try {
            threadA.start()
            assertTrue("Thread A should enter sendFrame and begin writing", writeEntered.await(5, TimeUnit.SECONDS))

            threadB.start()
            Thread.sleep(60)

            // 当 Thread A 仍在写帧阻塞时，Thread B 无法完成 closeSocket()
            assertFalse("closeSocket() should not complete while sendFrame holds the lock", closeFinished.get())

            // 允许写完成并释放锁
            allowWrite.countDown()

            threadA.join(5000)
            threadB.join(5000)

            assertFalse("Thread A should have completed", threadA.isAlive)
            assertFalse("Thread B should have completed", threadB.isAlive)
            assertTrue("closeSocket() should complete after sendFrame releases lock", closeFinished.get())
            assertTrue("No exceptions in worker threads: $threadExceptions", threadExceptions.isEmpty())
        } finally {
            allowWrite.countDown()
            client.close()
        }
    }

    // T5: RB6 / NR5 generation 阻止旧 worker 复活与中断恢复
    @Test
    fun rb6_generationPreventsOldWorkerResurrection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchCount = AtomicInteger(0)

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        shadowApp.grantPermissions(android.Manifest.permission.READ_LOGS)

        val dummyProcess = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() {}
        }

        val restartDelayMs = 30L
        val sources = ClipboardSources(
            context = context,
            callback = { _, _ -> },
            status = {},
            logcatProcessLauncher = {
                launchCount.incrementAndGet()
                dummyProcess
            },
            logcatRestartDelayMs = restartDelayMs
        )

        sources.start()
        awaitCondition { launchCount.get() >= 1 }
        val countAfterStart = launchCount.get()

        // 停止 sources，generation 递增
        sources.stop()

        // 等待至少 4 倍 restart delay，确保旧 worker 从 sleep 中醒来后进行 generation 校验并退出
        Thread.sleep(restartDelayMs * 4)

        val countAfterStop = launchCount.get()
        assertEquals("Old worker must not launch new process after waking from restart sleep", countAfterStart, countAfterStop)

        // 验证 stop 幂等性
        sources.stop()
        assertEquals(countAfterStart, launchCount.get())

        // 验证重新 start 后会创建新 generation worker
        sources.start()
        awaitCondition { launchCount.get() > countAfterStop }
        sources.stop()
    }

    // RB7: 哈希轮数真实输入与保存校验
    @Test
    fun rb7_hashRoundsBoundsValidation() {
        assertEquals(1, ClipConfig.MIN_HASH_ROUNDS)
        assertEquals(5_000_000, ClipConfig.MAX_HASH_ROUNDS)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.hashRounds = ClipConfig.DEFAULT_HASH_ROUNDS

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        val hashRoundsField = MainActivity::class.java.getDeclaredField("hashRoundsInput")
        hashRoundsField.isAccessible = true
        val hashRoundsInput = hashRoundsField.get(activity) as EditText

        val saveMethod = MainActivity::class.java.getDeclaredMethod("saveEditableSettings")
        saveMethod.isAccessible = true

        // 1. 非法输入：0 -> 保存失败，配置值不被覆盖
        hashRoundsInput.setText("0")
        val result0 = saveMethod.invoke(activity) as Boolean
        assertFalse("0 should be invalid", result0)
        assertEquals(ClipConfig.DEFAULT_HASH_ROUNDS, store.hashRounds)

        // 2. 非法输入：-1 -> 保存失败
        hashRoundsInput.setText("-1")
        val resultNeg = saveMethod.invoke(activity) as Boolean
        assertFalse("-1 should be invalid", resultNeg)
        assertEquals(ClipConfig.DEFAULT_HASH_ROUNDS, store.hashRounds)

        // 3. 非法输入：abc -> 保存失败
        hashRoundsInput.setText("abc")
        val resultAbc = saveMethod.invoke(activity) as Boolean
        assertFalse("abc should be invalid", resultAbc)
        assertEquals(ClipConfig.DEFAULT_HASH_ROUNDS, store.hashRounds)

        // 4. 非法输入：5000001 -> 保存失败
        hashRoundsInput.setText("5000001")
        val resultOver = saveMethod.invoke(activity) as Boolean
        assertFalse("5000001 should be invalid", resultOver)
        assertEquals(ClipConfig.DEFAULT_HASH_ROUNDS, store.hashRounds)

        // 5. 有效输入：664937 -> 保存成功，配置更新
        hashRoundsInput.setText("664937")
        val resultValid1 = saveMethod.invoke(activity) as Boolean
        assertTrue("664937 should be valid", resultValid1)
        assertEquals(664937, store.hashRounds)

        // 6. 边界有效输入：1 -> 保存成功
        hashRoundsInput.setText("1")
        val resultValid2 = saveMethod.invoke(activity) as Boolean
        assertTrue("1 should be valid", resultValid2)
        assertEquals(1, store.hashRounds)
    }

    // RB8: Keystore 解密失败保留密文
    @Test
    fun rb8_keystoreDecryptFailureRetainsCiphertext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)

        val corruptedSecret = "aks:CorruptedCiphertextData"
        store.sharedPreferences.edit().putString("saved_encrypted_password", corruptedSecret).apply()

        val value = store.savedEncryptedPassword

        assertEquals("", value)
        assertTrue(store.passwordDecryptionFailed)
        assertEquals(corruptedSecret, store.sharedPreferences.getString("saved_encrypted_password", null))
    }

    // T6: RB9 Xposed Hook 安装失败状态回滚
    @Test
    fun rb9_xposedInstalledStateRollback() {
        val attemptCount = AtomicInteger(0)
        var shouldSucceed = false

        val installer = ClipboardHookInstaller { _, _, _ ->
            attemptCount.incrementAndGet()
            shouldSucceed
        }

        val dummyClassLoader = this.javaClass.classLoader

        // 1. 模拟 Hook 尝试失败
        shouldSucceed = false
        val success1 = installer.installHooks(dummyClassLoader)
        assertFalse("installHooks should return false when hook fails", success1)
        assertFalse("installed flag must be rolled back to false on failure", installer.installed.get())
        assertTrue("Should have tried hook signatures", attemptCount.get() > 0)

        // 2. 再次调用，因为之前回滚了状态，应当继续尝试
        val attemptsBefore = attemptCount.get()
        shouldSucceed = true
        val success2 = installer.installHooks(dummyClassLoader)
        assertTrue("installHooks should return true when hook succeeds", success2)
        assertTrue("installed flag must be true after success", installer.installed.get())
        assertTrue("Should have attempted hooks again", attemptCount.get() > attemptsBefore)

        // 3. 成功后再次调用，应当跳过
        val attemptsAfterSuccess = attemptCount.get()
        val success3 = installer.installHooks(dummyClassLoader)
        assertFalse("installHooks should return false when already installed", success3)
        assertEquals("Should not attempt hooks again after success", attemptsAfterSuccess, attemptCount.get())
    }

    // T7: RB10 HTTP 握手响应头 64KB 上限
    @Test
    fun rb10_handshakeHeaderSizeLimit() {
        val normalHeader = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        val parsedNormal = RawWebSocketClient.readHttpHeadersFromStream(ByteArrayInputStream(normalHeader.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(normalHeader, parsedNormal)

        val hugeHeaderBuilder = StringBuilder("HTTP/1.1 101 Switching Protocols\r\n")
        repeat(700) { i ->
            hugeHeaderBuilder.append("X-Large-Header-$i: ${"a".repeat(100)}\r\n")
        }
        hugeHeaderBuilder.append("\r\n")
        val hugeHeader = hugeHeaderBuilder.toString()
        assertTrue("Header size must exceed 64KB for test", hugeHeader.toByteArray(Charsets.ISO_8859_1).size > 64 * 1024)

        var headerTooLargeThrown = false
        try {
            RawWebSocketClient.readHttpHeadersFromStream(ByteArrayInputStream(hugeHeader.toByteArray(Charsets.ISO_8859_1)))
        } catch (e: IOException) {
            headerTooLargeThrown = true
        }
        assertTrue("Should throw IOException on header exceeding 64KB", headerTooLargeThrown)
    }

    // T8: RB12 / NR6 continuation 分片与控制帧处理
    @Test
    fun rb12_continuationFrameHandling() {
        val textsReceived = mutableListOf<String>()
        val pongsSent = mutableListOf<ByteArray>()

        // 1. 正常分片序列: TEXT(fin=0) -> CONT(fin=0) -> CONT(fin=1)
        var stream: ByteArrayOutputStream? = null

        val r1 = RawWebSocketClient.processIncomingFrame(
            fin = false,
            opcode = 0x1,
            payload = "Hello, ".toByteArray(Charsets.UTF_8),
            currentFragmentedStream = stream,
            onText = { textsReceived.add(it) },
            onSendPong = { pongsSent.add(it) }
        )
        stream = r1.first
        assertTrue("Stream should be non-null for fragmented message", stream != null)
        assertTrue("onText should not be called yet", textsReceived.isEmpty())

        val r2 = RawWebSocketClient.processIncomingFrame(
            fin = false,
            opcode = 0x0,
            payload = "World! ".toByteArray(Charsets.UTF_8),
            currentFragmentedStream = stream,
            onText = { textsReceived.add(it) },
            onSendPong = { pongsSent.add(it) }
        )
        stream = r2.first
        assertTrue("Stream should be non-null", stream != null)
        assertTrue("onText should not be called yet", textsReceived.isEmpty())

        val r3 = RawWebSocketClient.processIncomingFrame(
            fin = true,
            opcode = 0x0,
            payload = "Done.".toByteArray(Charsets.UTF_8),
            currentFragmentedStream = stream,
            onText = { textsReceived.add(it) },
            onSendPong = { pongsSent.add(it) }
        )
        stream = r3.first
        assertTrue("Stream should be null after fin=true", stream == null)
        assertEquals("Should receive exactly 1 complete text", listOf("Hello, World! Done."), textsReceived)

        // 2. 无初始 0x1 的 0x0 CONT 帧报错
        var continuationWithoutInitialThrown = false
        try {
            RawWebSocketClient.processIncomingFrame(
                fin = true,
                opcode = 0x0,
                payload = "invalid".toByteArray(Charsets.UTF_8),
                currentFragmentedStream = null,
                onText = {},
                onSendPong = {}
            )
        } catch (e: IOException) {
            continuationWithoutInitialThrown = true
        }
        assertTrue("Continuation frame without initial stream should throw IOException", continuationWithoutInitialThrown)

        // 3. 分片过程中又收到新的 0x1 TEXT 帧报错
        var newTextBeforeCompletedThrown = false
        try {
            val partialStream = ByteArrayOutputStream().apply { write("Partial".toByteArray()) }
            RawWebSocketClient.processIncomingFrame(
                fin = true,
                opcode = 0x1,
                payload = "New".toByteArray(Charsets.UTF_8),
                currentFragmentedStream = partialStream,
                onText = {},
                onSendPong = {}
            )
        } catch (e: IOException) {
            newTextBeforeCompletedThrown = true
        }
        assertTrue("New text frame during fragmentation should throw IOException", newTextBeforeCompletedThrown)

        // 4. 控制帧 Ping (0x9) 触发 Pong 发送
        val pingPayload = "ping-123".toByteArray(Charsets.UTF_8)
        val rPing = RawWebSocketClient.processIncomingFrame(
            fin = true,
            opcode = 0x9,
            payload = pingPayload,
            currentFragmentedStream = null,
            onText = {},
            onSendPong = { pongsSent.add(it) }
        )
        assertEquals(1, pongsSent.size)
        assertTrue(pongsSent[0].contentEquals(pingPayload))

        // 5. 控制帧分片非法 (fin=false)
        var fragmentedControlFrameThrown = false
        try {
            RawWebSocketClient.processIncomingFrame(
                fin = false,
                opcode = 0x9,
                payload = ByteArray(4),
                currentFragmentedStream = null,
                onText = {},
                onSendPong = {}
            )
        } catch (e: IllegalStateException) {
            fragmentedControlFrameThrown = true
        }
        assertTrue("Fragmented control frame must throw IllegalStateException", fragmentedControlFrameThrown)

        // 6. NR6: 分片累计超过消息上限抛 IOException
        var sizeLimitThrown = false
        var sizeLimitStream: ByteArrayOutputStream? = null
        try {
            val s1 = RawWebSocketClient.processIncomingFrame(
                fin = false,
                opcode = 0x1,
                payload = "123456".toByteArray(Charsets.UTF_8),
                currentFragmentedStream = null,
                onText = {},
                onSendPong = {},
                maxMessageBytes = 8L
            )
            sizeLimitStream = s1.first
            assertTrue("Stream should be non-null after 6-byte fragment", sizeLimitStream != null)

            RawWebSocketClient.processIncomingFrame(
                fin = true,
                opcode = 0x0,
                payload = "789".toByteArray(Charsets.UTF_8),
                currentFragmentedStream = sizeLimitStream,
                onText = {},
                onSendPong = {},
                maxMessageBytes = 8L
            )
        } catch (e: IOException) {
            if (e.message?.contains("exceeded size limit", ignoreCase = true) == true ||
                e.message?.contains("size limit", ignoreCase = true) == true) {
                sizeLimitThrown = true
            }
        }
        assertTrue("Exceeding maxMessageBytes should throw IOException with size limit message", sizeLimitThrown)

        // 7. NR6: 控制帧 payload 超过 125 字节必须报错且不发送 Pong
        pongsSent.clear()
        var oversizedControlFrameThrown = false
        try {
            RawWebSocketClient.processIncomingFrame(
                fin = true,
                opcode = 0x9,
                payload = ByteArray(126),
                currentFragmentedStream = null,
                onText = {},
                onSendPong = { pongsSent.add(it) }
            )
        } catch (e: IllegalStateException) {
            oversizedControlFrameThrown = true
        }
        assertTrue("Control frame > 125 bytes must throw IllegalStateException", oversizedControlFrameThrown)
        assertTrue("onSendPong must not be called on oversized control frame", pongsSent.isEmpty())
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }
}
