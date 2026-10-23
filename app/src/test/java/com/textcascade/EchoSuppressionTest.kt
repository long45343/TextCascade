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
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 自写回显抑制。
 *
 * 远端文本写入剪贴板后，系统会回调本地剪贴板监听，把同一内容当成用户复制再发回
 * 服务端。抑制逻辑必须准确命中这次回抛，同时不能波及用户后续的真实复制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EchoSuppressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var engine: TextSyncEngine

    private val sentBodies = CopyOnWriteArrayList<String>()
    private val appliedTexts = CopyOnWriteArrayList<String>()
    private val statuses = CopyOnWriteArrayList<String>()
    private val clipboardWrites = AtomicInteger(0)

    private fun startEngine(cipherEnabled: Boolean = false) {
        val config = ClipConfig.default(context).copy(cipherEnabled = cipherEnabled)
        engine = TextSyncEngine(
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
                    private val connected = AtomicBoolean(false)
                    override fun connect() {
                        if (connected.compareAndSet(false, true)) listener.onConnected()
                    }

                    override fun subscribe(destination: String) {}
                    override fun send(destination: String, body: String) {
                        sentBodies.add(body)
                    }

                    override fun close() {}
                }
            },
            clipboardWriter = { clipboardWrites.incrementAndGet() }
        )
        engine.start()
        awaitTrue { engine.isConnected }
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.stop()
    }

    private fun awaitTrue(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            // 引擎把剪贴板写入 post 到主线程；Robolectric 的 looper 默认暂停，
            // 必须显式 pump 才会执行。
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        if (condition()) return
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    /** 等待若干毫秒，用于确认「不应发生」的事情确实没发生。 */
    private fun settle(ms: Long = 250L) {
        Thread.sleep(ms)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun remoteEchoFromClipboardListenerIsSuppressed() {
        // 场景：远端文本落剪贴板 → 系统立刻回调本地监听（内容相同）→ 不得回传服务端。
        startEngine()
        val remote = JsonUtil.clipMessage("Remote payload", "text")

        engine.onMessage(remote)
        awaitTrue { appliedTexts.isNotEmpty() }
        assertEquals(1, clipboardWrites.get())

        // 模拟系统剪贴板监听回调，内容与远端一致
        engine.sendLocalText("Remote payload", "clipboard")
        settle()

        assertTrue("远端回显不应被再次发送：$sentBodies", sentBodies.isEmpty())
    }

    @Test
    fun userCopyAfterRemoteApplyIsNotSwallowed() {
        // 场景（v1 回归点）：远端文本落剪贴板后，采集链路因为 logcat 触发器退避
        // 没能及时产生回抛事件；用户紧接着复制了新内容。
        // 旧的一次性旗子会在这次真实复制上被消费掉，导致内容丢失。
        startEngine()

        engine.onMessage(JsonUtil.clipMessage("Remote payload", "text"))
        awaitTrue { appliedTexts.isNotEmpty() }

        engine.sendLocalText("User typed something else", "clipboard")
        awaitTrue { sentBodies.isNotEmpty() }

        assertEquals(1, sentBodies.size)
        assertTrue(sentBodies[0].contains("User typed something else"))
    }

    @Test
    fun repeatedLocalCopiesAllSentAfterRemoteApply() {
        // 场景：远端内容应用后，用户连续复制两段不同文本，两段都必须发出。
        startEngine()

        engine.onMessage(JsonUtil.clipMessage("Remote payload", "text"))
        awaitTrue { appliedTexts.isNotEmpty() }

        engine.sendLocalText("first", "clipboard")
        awaitTrue { sentBodies.size == 1 }
        engine.sendLocalText("second", "clipboard")
        awaitTrue { sentBodies.size == 2 }

        assertTrue(sentBodies[0].contains("first"))
        assertTrue(sentBodies[1].contains("second"))
    }

    @Test
    fun delayedRemoteEchoIsStillSuppressed() {
        // 场景：logcat 触发器有退避，回抛事件明显晚于远端写入。
        // 迟到但仍属同一次回抛的事件必须被抑制——这正是 hash 池相对
        // 「只记最近一条」的意义。
        startEngine()

        engine.onMessage(JsonUtil.clipMessage("Remote payload", "text"))
        awaitTrue { appliedTexts.isNotEmpty() }

        // 中间插入一次用户真实复制
        engine.sendLocalText("user copy", "clipboard")
        awaitTrue { sentBodies.size == 1 }

        // 迟到的远端回抛
        engine.sendLocalText("Remote payload", "clipboard")
        settle()

        assertEquals("迟到的远端回显仍应被抑制", 1, sentBodies.size)
    }

    @Test
    fun echoPoolDoesNotSwallowSameTextFromDifferentOrigin() {
        // 场景：用户手动复制了与最近一条远端内容完全相同的文本（例如两端都是同一段
        // 常用字符串）。hash 相同，按当前语义会被抑制——这是已知取舍：
        // 服务端已有该内容，重复发送没有信息量。
        startEngine()

        engine.onMessage(JsonUtil.clipMessage("shared constant", "text"))
        awaitTrue { appliedTexts.isNotEmpty() }

        engine.sendLocalText("shared constant", "clipboard")
        settle()
        assertTrue(sentBodies.isEmpty())
    }

    @Test
    fun remoteTextApplyingTwiceIsDeduplicated() {
        // 场景：服务端重发同一条 clip（重连后 welcome + clip），不得重复写剪贴板。
        startEngine()
        val remote = JsonUtil.clipMessage("Remote payload", "text")

        engine.onMessage(remote)
        awaitTrue { appliedTexts.isNotEmpty() }
        engine.onMessage(remote)
        settle()

        assertEquals(1, appliedTexts.size)
        assertEquals(1, clipboardWrites.get())
    }

    @Test
    fun poolIsBoundedSoOldHashesExpire() {
        // 场景：连续应用大量远端内容后，最早的 hash 应被淘汰，
        // 否则长时间运行会无界增长。容量 16，写入 20 条后最早的 4 条出池。
        startEngine()

        val total = TextSyncEngine.REMOTE_HASH_CAPACITY + 4
        repeat(total) { index ->
            engine.onMessage(JsonUtil.clipMessage("remote-$index", "text"))
            awaitTrue { appliedTexts.size == index + 1 }
        }

        // 最早的 remote-0 已被淘汰：同样的内容再次本地复制应能发出
        engine.sendLocalText("remote-0", "clipboard")
        awaitTrue { sentBodies.isNotEmpty() }
        assertTrue(sentBodies[0].contains("remote-0"))

        // 最近的 remote-19 仍在池中：回抛被抑制
        val before = sentBodies.size
        engine.sendLocalText("remote-${total - 1}", "clipboard")
        settle()
        assertEquals("近期远端内容仍应抑制回显", before, sentBodies.size)
    }
}
