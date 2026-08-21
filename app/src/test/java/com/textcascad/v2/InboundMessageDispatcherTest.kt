/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.ClipboardAccess
import com.textcascad.v2.engine.InboundMessageDispatcher
import com.textcascad.v2.engine.SyncStateStore
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 入站分发器全分支（纯 JVM 假件）：welcome/clip/clip_ack/ping/pong(unknown)/bye/error、
 * hash+version 去重、版本推进、限流窗口、applyRemotePayload 落盘/回滚/限额。
 * postToMain 直接同步执行；加密解密路径依赖 org.json 与 android.util.Base64，
 * 见同文件 InboundMessageDispatcherCryptoTest（Robolectric）。
 */
class InboundMessageDispatcherTest {

    private class RecordingCallbacks : InboundMessageDispatcher.InboundCallbacks {
        val events = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        val pongs = CopyOnWriteArrayList<String>()
        val versionAdvances = CopyOnWriteArrayList<Long>()
        val appliedTexts = CopyOnWriteArrayList<String>()
        @Volatile
        var keyBase64: String = ""
        @Volatile
        var limitBytes: Long = 512_000L

        override fun onStatus(message: String) {
            events.add("status:$message")
            statuses.add(message)
        }

        override fun onSendPong(body: String) {
            events.add("pong")
            pongs.add(body)
        }

        override fun onWelcomeBackoffReset() {
            events.add("backoff-reset")
        }

        override fun onMaintenanceBackoffEnabled() {
            events.add("maintenance")
        }

        override fun onServerVersionAdvanced(version: Long) {
            events.add("version:$version")
            versionAdvances.add(version)
        }

        override fun onRemoteTextApplied(text: String) {
            events.add("applied")
            appliedTexts.add(text)
        }

        override fun derivedKeyBase64(): String = keyBase64

        override fun isPayloadWithinLimits(textBytes: ByteArray): Boolean {
            val bytes = textBytes.size.toLong()
            return bytes in 1..limitBytes
        }
    }

    private class FakeClipboard : ClipboardAccess {
        val written = CopyOnWriteArrayList<String>()
        @Volatile
        var writeError: Exception? = null

        override fun readText(): String? = null

        override fun writeText(text: String) {
            writeError?.let { throw it }
            written.add(text)
        }
    }

    private class FakeStrings : StringProvider {
        override fun get(id: Int, vararg args: Any): String =
            "S$id|${args.joinToString("|") { it.toString() }}"
    }

    private class Harness(
        val dispatcher: InboundMessageDispatcher,
        val callbacks: RecordingCallbacks,
        val clipboard: FakeClipboard,
        val state: SyncStateStore
    )

    private fun newDispatcher(
        initialServerVersion: Long = 0L,
        nowMs: () -> Long = { 10_000L }
    ): Harness {
        val callbacks = RecordingCallbacks()
        val clipboard = FakeClipboard()
        val state = SyncStateStore(initialServerVersion)
        val dispatcher = InboundMessageDispatcher(
            callbacks = callbacks,
            state = state,
            clipboard = clipboard,
            stringProvider = FakeStrings(),
            postToMain = { it.run() },
            nowMs = nowMs
        )
        return Harness(dispatcher, callbacks, clipboard, state)
    }

    private fun welcome(version: Long, payload: String = ContractSamples.PAYLOAD_TEXT) =
        Protocol.ServerMessage.Welcome(
            protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
            latest = Protocol.LatestClip(
                version = version,
                payload = payload,
                encrypted = false,
                hashHex = ContractSamples.HASH_FOOBAR
            )
        )

    private fun clip(version: Long, hashHex: String = ContractSamples.HASH_FOOBAR) =
        Protocol.ServerMessage.Clip(
            id = ContractSamples.CLIP_ID,
            version = version,
            payload = ContractSamples.PAYLOAD_TEXT,
            encrypted = false,
            hashHex = hashHex
        )

    // ---------------- welcome ----------------

    @Test
    fun welcomeAppliesLatestWritesClipboardAndAdvancesVersion() {
        val h = newDispatcher()
        h.dispatcher.dispatch(welcome(9L))
        assertEquals(listOf("backoff-reset", "version:9", "applied"), h.callbacks.events)
        assertEquals(listOf(ContractSamples.PAYLOAD_TEXT), h.clipboard.written)
        assertEquals(listOf(ContractSamples.PAYLOAD_TEXT), h.callbacks.appliedTexts)
        assertEquals(9L, h.state.serverVersion)
    }

    @Test
    fun welcomeWithNullLatestOnlyResetsBackoff() {
        val h = newDispatcher()
        h.dispatcher.dispatch(
            Protocol.ServerMessage.Welcome(
                protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION,
                latest = null
            )
        )
        assertEquals(listOf("backoff-reset"), h.callbacks.events)
        assertTrue(h.clipboard.written.isEmpty())
        assertTrue(h.callbacks.versionAdvances.isEmpty())
    }

    @Test
    fun welcomeStaleVersionSkipsApplyWithoutVersionCallback() {
        val h = newDispatcher(initialServerVersion = 20L)
        h.dispatcher.dispatch(welcome(9L)) // version 9 <= 20
        assertEquals(listOf("backoff-reset"), h.callbacks.events)
        assertTrue(h.clipboard.written.isEmpty())
        assertTrue(h.callbacks.versionAdvances.isEmpty())
    }

    @Test
    fun welcomeEchoOfOwnSentHashNotAppliedButVersionAdvances() {
        val h = newDispatcher()
        h.state.setLastSentHashHex(ContractSamples.HASH_FOOBAR)
        h.dispatcher.dispatch(welcome(9L))
        assertEquals(listOf("backoff-reset", "version:9"), h.callbacks.events)
        assertTrue(h.clipboard.written.isEmpty())
        assertEquals(9L, h.state.serverVersion)
    }

    // ---------------- clip / clip_ack ----------------

    @Test
    fun clipWritesClipboardAndAdvancesVersion() {
        val h = newDispatcher()
        h.dispatcher.dispatch(clip(10L))
        assertEquals(listOf("version:10", "applied"), h.callbacks.events)
        assertEquals(listOf(ContractSamples.PAYLOAD_TEXT), h.clipboard.written)
        assertEquals(10L, h.state.serverVersion)
    }

    @Test
    fun duplicateClipIsDedupedAfterFirstApply() {
        val h = newDispatcher()
        h.dispatcher.dispatch(clip(10L))
        h.dispatcher.dispatch(clip(10L)) // 同版本重复推送：shouldApplyRemote 拒绝
        assertEquals(listOf("version:10", "applied"), h.callbacks.events)
        assertEquals(1, h.clipboard.written.size)
    }

    @Test
    fun clipEchoOfOwnSentHashNotAppliedButVersionAdvances() {
        val h = newDispatcher()
        h.state.setLastSentHashHex(ContractSamples.HASH_FOOBAR)
        h.dispatcher.dispatch(clip(30L))
        assertEquals(listOf("version:30"), h.callbacks.events)
        assertTrue(h.clipboard.written.isEmpty())
        assertEquals(30L, h.state.serverVersion)
    }

    @Test
    fun clipAckAdvancesVersionWithoutApplying() {
        val h = newDispatcher()
        h.dispatcher.dispatch(
            Protocol.ServerMessage.ClipAck(
                id = ContractSamples.CLIP_ID,
                version = 11L
            )
        )
        assertEquals(listOf("version:11"), h.callbacks.events)
        assertTrue(h.clipboard.written.isEmpty())
        assertTrue(h.callbacks.pongs.isEmpty())
    }

    @Test
    fun staleAdvanceDoesNotRepeatVersionCallback() {
        val h = newDispatcher()
        h.dispatcher.dispatch(
            Protocol.ServerMessage.ClipAck(id = ContractSamples.CLIP_ID, version = 5L)
        )
        h.dispatcher.dispatch(
            Protocol.ServerMessage.ClipAck(id = ContractSamples.CLIP_ID, version = 3L)
        )
        assertEquals(listOf("version:5"), h.callbacks.events)
        assertEquals(5L, h.state.serverVersion)
    }

    // ---------------- ping / pong ----------------

    @Test
    fun pingRepliesPongBuiltFromInjectedClock() {
        val fixedNow = 1_690_000_000_123L
        val h = newDispatcher(nowMs = { fixedNow })
        h.dispatcher.dispatch(Protocol.ServerMessage.Ping(serverTimeUtc = ContractSamples.TIME_EXAMPLE))
        assertEquals(listOf("pong"), h.callbacks.events)
        // 与现状字节等价：Protocol.pongMessage(utcNowString(nowMs()))
        assertEquals(
            Protocol.pongMessage(Protocol.utcNowString(fixedNow)),
            h.callbacks.pongs.single()
        )
        val pong = h.callbacks.pongs.single()
        assertTrue(pong.startsWith("{\"type\":\"pong\",\"clientTimeUtc\":\""))
        assertTrue(pong.endsWith("\"}"))
        assertTrue(pong.contains("Z"))
    }

    @Test
    fun pongDownlinkParsesAsUnknownAndIsIgnored() {
        val h = newDispatcher()
        // 服务端不会下发 pong；容错解析（Protocol.parseServerMessage，由 ProtocolTest 覆盖）
        // 对未知 type 落入 Unknown（纯 JVM 下 org.json 为 stub，此处直接构造解析产物），
        // 分发器对 Unknown 与现状一致静默忽略
        h.dispatcher.dispatch(Protocol.ServerMessage.Unknown)
        assertTrue(h.callbacks.events.isEmpty())
        assertTrue(h.clipboard.written.isEmpty())
    }

    // ---------------- bye ----------------

    @Test
    fun byeEnablesMaintenanceBackoffAndLogsReason() {
        val h = newDispatcher()
        h.dispatcher.dispatch(Protocol.ServerMessage.Bye(reason = "server_shutdown"))
        assertEquals(
            listOf("maintenance", "status:S${R.string.status_server_bye}|server_shutdown"),
            h.callbacks.events
        )
    }

    @Test
    fun byeWithoutReasonFallsBackToUnknownText() {
        val h = newDispatcher()
        h.dispatcher.dispatch(Protocol.ServerMessage.Bye(reason = null))
        assertEquals(
            listOf("maintenance", "status:S${R.string.status_server_bye}|unknown"),
            h.callbacks.events
        )
    }

    // ---------------- error ----------------

    @Test
    fun errorCodesRouteToGenericStatusText() {
        for (code in listOf(
            "invalid_message",
            "empty_text",
            "hello_timeout",
            "server_busy",
            "frame_too_large",
            "mystery_code"
        )) {
            val h = newDispatcher()
            h.dispatcher.dispatch(Protocol.ServerMessage.Error(code = code, message = null))
            assertEquals(
                "code=$code",
                listOf("S${R.string.status_server_error_code}|$code"),
                h.callbacks.statuses
            )
        }
    }

    @Test
    fun textTooLargeUsesDedicatedStatusText() {
        val h = newDispatcher()
        h.dispatcher.dispatch(
            Protocol.ServerMessage.Error(code = "text_too_large", message = "clip exceeds maxTextBytes")
        )
        assertEquals(
            listOf("S${R.string.status_text_too_large_discarded}|"),
            h.callbacks.statuses
        )
    }

    @Test
    fun rateLimitedSetsPauseWindowAndStatus() {
        val h = newDispatcher(nowMs = { 10_000L })
        h.dispatcher.dispatch(Protocol.ServerMessage.Error(code = "rate_limited", message = null))
        assertEquals(11_000L, h.state.sendPausedUntilMs)
        assertEquals(
            listOf("S${R.string.status_send_rate_limited}|"),
            h.callbacks.statuses
        )
    }

    // ---------------- unknown ----------------

    @Test
    fun unknownMessageIsIgnored() {
        val h = newDispatcher()
        h.dispatcher.dispatch(Protocol.ServerMessage.Unknown)
        assertTrue(h.callbacks.events.isEmpty())
        assertTrue(h.clipboard.written.isEmpty())
    }

    // ---------------- applyRemotePayload ----------------

    @Test
    fun applyRemotePayloadPlaintextMarksHashForEchoDedup() {
        val h = newDispatcher()
        h.dispatcher.applyRemotePayload(ContractSamples.PAYLOAD_TEXT, encrypted = false, hashHex = ContractSamples.HASH_FOOBAR)
        assertEquals(listOf(ContractSamples.PAYLOAD_TEXT), h.clipboard.written)
        assertTrue(h.state.isEchoOfLastRemote(ContractSamples.HASH_FOOBAR))
        assertEquals(listOf(ContractSamples.PAYLOAD_TEXT), h.callbacks.appliedTexts)
    }

    @Test
    fun applyRemotePayloadEmptyTextSkipsWrite() {
        val h = newDispatcher()
        h.dispatcher.applyRemotePayload("", encrypted = false, hashHex = ContractSamples.HASH_FOOBAR)
        assertTrue(h.clipboard.written.isEmpty())
        assertFalse(h.state.isEchoOfLastRemote(ContractSamples.HASH_FOOBAR))
        assertTrue(h.callbacks.events.isEmpty())
    }

    @Test
    fun applyRemotePayloadOverLimitSkipsWrite() {
        val h = newDispatcher()
        h.callbacks.limitBytes = 4L
        h.dispatcher.applyRemotePayload("toolong", encrypted = false, hashHex = ContractSamples.HASH_FOOBAR)
        assertTrue(h.clipboard.written.isEmpty())
        assertFalse(h.state.isEchoOfLastRemote(ContractSamples.HASH_FOOBAR))
        assertTrue(h.callbacks.events.isEmpty())
    }

    @Test
    fun applyRemotePayloadWriteFailureRollsBackHashAndReports() {
        val h = newDispatcher()
        h.clipboard.writeError = java.io.IOException("boom")
        h.dispatcher.applyRemotePayload(ContractSamples.PAYLOAD_TEXT, encrypted = false, hashHex = ContractSamples.HASH_FOOBAR)
        // 落盘失败：hash 回滚，回显去重不生效
        assertFalse(h.state.isEchoOfLastRemote(ContractSamples.HASH_FOOBAR))
        assertEquals(
            listOf("S${R.string.status_inbound_error}|boom"),
            h.callbacks.statuses
        )
        assertTrue(h.clipboard.written.isEmpty())
    }
}

/**
 * 加密载荷路径（Robolectric：依赖 org.json 解析与 android.util.Base64 的 CryptoManager）：
 * 解密落盘、坏载荷报错、缺密钥拦截。
 */
@RunWith(RobolectricTestRunner::class)
class InboundMessageDispatcherCryptoTest {

    private class RecordingCallbacks : InboundMessageDispatcher.InboundCallbacks {
        val statuses = CopyOnWriteArrayList<String>()
        val appliedTexts = CopyOnWriteArrayList<String>()
        @Volatile
        var keyBase64: String = ""

        override fun onStatus(message: String) {
            statuses.add(message)
        }

        override fun onSendPong(body: String) = Unit
        override fun onWelcomeBackoffReset() = Unit
        override fun onMaintenanceBackoffEnabled() = Unit
        override fun onServerVersionAdvanced(version: Long) = Unit

        override fun onRemoteTextApplied(text: String) {
            appliedTexts.add(text)
        }

        override fun derivedKeyBase64(): String = keyBase64
        override fun isPayloadWithinLimits(textBytes: ByteArray): Boolean = textBytes.isNotEmpty()
    }

    private class FakeClipboard : ClipboardAccess {
        val written = CopyOnWriteArrayList<String>()
        override fun readText(): String? = null
        override fun writeText(text: String) {
            written.add(text)
        }
    }

    private fun newDispatcher(initialServerVersion: Long = 0L): Triple<InboundMessageDispatcher, RecordingCallbacks, FakeClipboard> {
        val callbacks = RecordingCallbacks()
        val clipboard = FakeClipboard()
        val dispatcher = InboundMessageDispatcher(
            callbacks = callbacks,
            state = SyncStateStore(initialServerVersion),
            clipboard = clipboard,
            stringProvider = object : StringProvider {
                override fun get(id: Int, vararg args: Any): String =
                    "S$id|${args.joinToString("|") { it.toString() }}"
            },
            postToMain = { it.run() }
        )
        return Triple(dispatcher, callbacks, clipboard)
    }

    private fun derivedKeyBase64(): String = java.util.Base64.getEncoder().encodeToString(
        CryptoManager.derivePasswordKey("user", "pass", "salt", 1000)
    )

    @Test
    fun encryptedClipDecryptsAndWritesThroughDispatcher() {
        val (dispatcher, callbacks, clipboard) = newDispatcher()
        callbacks.keyBase64 = derivedKeyBase64()
        val payload = CryptoManager.encryptedPayloadJson(
            CryptoManager.encrypt("remote secret", callbacks.keyBase64)
        )
        dispatcher.dispatch(
            Protocol.ServerMessage.Clip(
                id = ContractSamples.CLIP_ID,
                version = 50L,
                payload = payload,
                encrypted = true,
                hashHex = HashUtil.fnv1a64Hex("remote secret")
            )
        )
        assertEquals(listOf("remote secret"), clipboard.written)
        assertEquals(listOf("remote secret"), callbacks.appliedTexts)
        assertTrue(callbacks.statuses.isEmpty())
    }

    @Test
    fun malformedEncryptedPayloadReportsInboundError() {
        val (dispatcher, callbacks, clipboard) = newDispatcher()
        callbacks.keyBase64 = derivedKeyBase64()
        dispatcher.applyRemotePayload("{\"nonce\":\"x\"}", encrypted = true, hashHex = "h1")
        assertTrue(clipboard.written.isEmpty())
        assertEquals(1, callbacks.statuses.size)
        assertTrue(callbacks.statuses.single().startsWith("S${R.string.status_inbound_error}|"))
    }

    @Test
    fun blankDerivedKeyFailsBeforeDecrypt() {
        val (dispatcher, callbacks, clipboard) = newDispatcher()
        callbacks.keyBase64 = ""
        val payload = CryptoManager.encryptedPayloadJson(
            CryptoManager.encrypt("remote secret", derivedKeyBase64())
        )
        dispatcher.applyRemotePayload(payload, encrypted = true, hashHex = "h2")
        assertTrue(clipboard.written.isEmpty())
        assertEquals(
            listOf("S${R.string.status_inbound_error}|No derived key available for decryption"),
            callbacks.statuses
        )
    }
}
