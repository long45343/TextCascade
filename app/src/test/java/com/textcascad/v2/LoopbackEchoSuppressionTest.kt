/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ClipboardAccess
import com.textcascad.v2.engine.OutboundMessageResult
import com.textcascad.v2.engine.OutboundPayloadCodec
import com.textcascad.v2.engine.SyncStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackEchoSuppressionTest {

    private class FakeClipboard(var text: String? = null) : ClipboardAccess {
        override fun readText(): String? = text
        override fun writeText(text: String) {
            this.text = text
        }
    }

    private class DummyStringProvider : StringProvider {
        override fun get(id: Int, vararg args: Any): String = "id:$id"
    }

    private fun buildCodec(state: SyncStateStore): OutboundPayloadCodec {
        val config = ClipConfig(
            session = ServerSession(
                serverUrl = "https://srv.example",
                username = "u",
                token = "t",
                tokenExpiresAtUtc = 0L,
                clientId = "c",
                clientName = "cn"
            ),
            userPrefs = UserPrefs(
                maxTextBytes = 512_000L,
                helloTimeoutSeconds = 10,
                heartbeatIntervalSeconds = 20,
                heartbeatTimeoutSeconds = 60,
                lastServerVersion = 0L,
                relaunchOnBoot = false,
                websocketStatusNotification = false,
                localMaxClipboardBytes = 512_000L
            ),
            cryptoMaterial = CryptoMaterial(
                derivedKeyBase64 = "",
                hashRounds = 1000,
                salt = "s",
                cipherEnabled = false,
                trustAllCerts = false
            )
        )
        return OutboundPayloadCodec(
            config = config,
            nowMs = { 1000L },
            clipboard = FakeClipboard(),
            state = state,
            stringProvider = DummyStringProvider(),
            isConnected = { true },
            encrypt = { it },
            status = {}
        )
    }

    @Test
    fun continuousRemoteABWritesAreSuppressedWhenLocalCallbacksArriveLate() {
        val state = SyncStateStore(0L)
        val codec = buildCodec(state)

        val hashA = HashUtil.fnv1a64Hex("Text-A")
        val hashB = HashUtil.fnv1a64Hex("Text-B")

        // 远端写入 A，接着写入 B
        state.markRemoteApplied(hashA)
        state.markRemoteApplied(hashB)

        // 本地 A 延迟到达
        val resultA = codec.buildClipMessage("Text-A", "clipboard")
        assertEquals(OutboundMessageResult.Suppressed, resultA)

        // 本地 B 到达
        val resultB = codec.buildClipMessage("Text-B", "clipboard")
        assertEquals(OutboundMessageResult.Suppressed, resultB)
    }

    @Test
    fun recent16RemoteHashesHitAnd17thEvictsOldest() {
        val state = SyncStateStore(0L)
        val codec = buildCodec(state)

        // 写入 16 条远端文本
        for (i in 1..16) {
            val text = "Remote-Text-$i"
            val hash = HashUtil.fnv1a64Hex(text)
            state.markRemoteApplied(hash)
        }

        // 验证全部 16 条都在池中并被抑制
        for (i in 1..16) {
            val text = "Remote-Text-$i"
            assertTrue(state.isEchoOfRecentRemote(HashUtil.fnv1a64Hex(text)))
            assertEquals(OutboundMessageResult.Suppressed, codec.buildClipMessage(text, "clipboard"))
        }

        // 写入第 17 条远端文本
        val text17 = "Remote-Text-17"
        state.markRemoteApplied(HashUtil.fnv1a64Hex(text17))

        // 第 1 条应该已被淘汰，不再命中池
        val hash1 = HashUtil.fnv1a64Hex("Remote-Text-1")
        assertFalse(state.isEchoOfRecentRemote(hash1))

        // 第 2..17 条应该仍在池中
        for (i in 2..17) {
            val text = "Remote-Text-$i"
            assertTrue(state.isEchoOfRecentRemote(HashUtil.fnv1a64Hex(text)))
        }
    }

    @Test
    fun remoteApplyRollbackClearsRecentHash() {
        val state = SyncStateStore(0L)
        val codec = buildCodec(state)
        val text = "Fail-Text"
        val hash = HashUtil.fnv1a64Hex(text)

        state.markRemoteApplied(hash)
        assertTrue(state.isEchoOfRecentRemote(hash))

        // 模拟写入失败回滚
        state.rollbackRemoteAppliedIfCurrent(hash)
        assertFalse(state.isEchoOfRecentRemote(hash))

        // 回滚后再发送本地，不会被抑制
        val result = codec.buildClipMessage(text, "clipboard")
        assertTrue(result is OutboundMessageResult.Ready)
    }

    @Test
    fun activeLocalCopyMatchingHistoricalRemoteHashIsSuppressedByCapacity() {
        val state = SyncStateStore(0L)
        val codec = buildCodec(state)
        val text = "Duplicate-Content"
        val hash = HashUtil.fnv1a64Hex(text)

        state.markRemoteApplied(hash)
        // 模拟用户主动复制相同内容
        val result = codec.buildClipMessage(text, "clipboard")
        assertEquals(OutboundMessageResult.Suppressed, result)
    }
}