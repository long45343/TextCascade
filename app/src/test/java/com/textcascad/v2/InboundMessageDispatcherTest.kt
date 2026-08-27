/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import com.textcascad.v2.engine.InboundCommand
import com.textcascad.v2.engine.InboundCommands
import com.textcascad.v2.engine.InboundMessageDispatcher
import com.textcascad.v2.engine.SyncStateStore
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2：Dispatcher 只做判定与命令生成；状态读取/推进是允许的确定性副作用。
 */
@RunWith(RobolectricTestRunner::class)
class InboundMessageDispatcherTest {

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

    private fun clip(
        version: Long,
        hashHex: String = ContractSamples.HASH_FOOBAR,
        payload: String = ContractSamples.PAYLOAD_TEXT,
        encrypted: Boolean = false
    ) = Protocol.ServerMessage.Clip(
        id = ContractSamples.CLIP_ID,
        version = version,
        payload = payload,
        encrypted = encrypted,
        hashHex = hashHex
    )

    private fun newDispatcher(initialVersion: Long = 0L, nowMs: () -> Long = { 10_000L }) =
        InboundMessageDispatcher(SyncStateStore(initialVersion), nowMs)

    @Test
    fun welcomeWithoutLatestOnlyResetsBackoff() {
        val dispatcher = newDispatcher()
        val commands = dispatcher.dispatch(
            Protocol.ServerMessage.Welcome(Protocol.SUPPORTED_PROTOCOL_VERSION, null)
        )
        assertEquals(listOf<InboundCommand>(InboundCommand.ResetBackoff), commands.commands)
    }

    @Test
    fun welcomeAppliesPlaintextAdvancesVersionAndRecordsHash() {
        val dispatcher = newDispatcher()
        val commands = dispatcher.dispatch(welcome(9L)).commands
        assertTrue(commands[0] is InboundCommand.ResetBackoff)
        assertEquals(InboundCommand.AdvanceVersion(9L), commands[1])
        assertEquals(
            InboundCommand.ApplyClipboard(ContractSamples.PAYLOAD_TEXT, false, ContractSamples.HASH_FOOBAR),
            commands[2]
        )
        assertEquals(9L, dispatcher.state.serverVersion)
        assertTrue(dispatcher.state.isEchoOfLastRemote(ContractSamples.HASH_FOOBAR))
    }

    @Test
    fun welcomeStaleVersionSkipsApplyAndAdvance() {
        val dispatcher = newDispatcher(initialVersion = 20L)
        val commands = dispatcher.dispatch(welcome(9L)).commands
        assertEquals(listOf<InboundCommand>(InboundCommand.ResetBackoff), commands)
    }

    @Test
    fun duplicateClipAdvancesOnlyForHigherVersions() {
        val dispatcher = newDispatcher()
        val first = dispatcher.dispatch(clip(10L))
        val second = dispatcher.dispatch(clip(10L))
        assertEquals(
            listOf(InboundCommand.AdvanceVersion(10L), InboundCommand.ApplyClipboard(ContractSamples.PAYLOAD_TEXT, false, ContractSamples.HASH_FOOBAR)),
            first.commands
        )
        assertEquals(InboundCommands.NONE, second)
        assertEquals(false, dispatcher.state.shouldApplyRemote(10L, "different"))
    }

    @Test
    fun echoOfOwnSentHashDoesNotEmitClipboardCommand() {
        val state = SyncStateStore(0L)
        state.setLastSentHashHex(ContractSamples.HASH_FOOBAR)
        val dispatcher = InboundMessageDispatcher(state)
        val commands = dispatcher.dispatch(clip(30L)).commands
        assertEquals(listOf<InboundCommand>(InboundCommand.AdvanceVersion(30L)), commands)
    }

    @Test
    fun clipAckEmitsVersionOnlyWhenAdvanced() {
        val dispatcher = newDispatcher()
        assertEquals(
            InboundCommands(listOf(InboundCommand.AdvanceVersion(11L))),
            dispatcher.dispatch(Protocol.ServerMessage.ClipAck(ContractSamples.CLIP_ID, 11L))
        )
        assertEquals(
            InboundCommands.NONE,
            dispatcher.dispatch(Protocol.ServerMessage.ClipAck(ContractSamples.CLIP_ID, 3L))
        )
    }

    @Test
    fun pingProducesPongWithInjectedClock() {
        val fixedNow = 1_690_000_000_123L
        val dispatcher = InboundMessageDispatcher(SyncStateStore(0L), { fixedNow })
        val command = dispatcher.dispatch(
            Protocol.ServerMessage.Ping(ContractSamples.TIME_EXAMPLE)
        ).commands.single()
        assertEquals(
            InboundCommand.Pong(Protocol.pongMessage(Protocol.utcNowString(fixedNow))),
            command
        )
    }

    @Test
    fun byeEnablesMaintenanceAndEmitsStatusResourceWithArgs() {
        val dispatcher = newDispatcher()
        val commands = dispatcher.dispatch(Protocol.ServerMessage.Bye("server_shutdown")).commands
        assertEquals(InboundCommand.EnableMaintenanceBackoff, commands[0])
        assertEquals(
            InboundCommand.Status(R.string.status_server_bye, listOf("server_shutdown")),
            commands[1]
        )
    }

    @Test
    fun errorsProduceResourceIdsAndRateLimitedUpdatesPauseWindow() {
        val now = 10_000L
        val dispatcher = InboundMessageDispatcher(SyncStateStore(0L), { now })

        assertEquals(
            InboundCommands(listOf(InboundCommand.Status(R.string.status_text_too_large_discarded))),
            dispatcher.dispatch(Protocol.ServerMessage.Error("text_too_large", null))
        )
        assertEquals(
            InboundCommands(
                listOf(
                    InboundCommand.Status(
                        R.string.status_server_error_code,
                        listOf("invalid_message")
                    )
                )
            ),
            dispatcher.dispatch(Protocol.ServerMessage.Error("invalid_message", null))
        )

        val rate = InboundMessageDispatcher(SyncStateStore(0L), { now })
        rate.dispatch(Protocol.ServerMessage.Error("rate_limited", null))
        assertEquals(now + 1000L, rate.state.sendPausedUntilMs)
    }

    @Test
    fun encryptedPayloadUsesInjectedDecryptorBeforeMarkingHash() {
        val key = java.util.Base64.getEncoder().encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000)
        )
        val encryptedJson = CryptoManager.encryptedPayloadJson(
            CryptoManager.encrypt("remote secret", key)
        )
        val dispatcher = InboundMessageDispatcher(
            SyncStateStore(0L),
            decrypt = { payload ->
                runCatching {
                    CryptoManager.decrypt(parseEncrypted(payload), key)
                }
            }
        )
        val hash = HashUtil.fnv1a64Hex("remote secret")
        val commands = dispatcher.dispatch(clip(50L, hash, encryptedJson, encrypted = true)).commands
        assertEquals(InboundCommand.AdvanceVersion(50L), commands[0])
        assertEquals(
            InboundCommand.ApplyClipboard("remote secret", false, hash),
            commands[1]
        )
        assertTrue(dispatcher.state.isEchoOfLastRemote(hash))
    }

    @Test
    fun malformedEncryptedPayloadBecomesStatusInsteadOfClipboardCommand() {
        val key = java.util.Base64.getEncoder().encodeToString(
            CryptoManager.derivePasswordKey("user", "pass", "salt", 1000)
        )
        val dispatcher = InboundMessageDispatcher(
            SyncStateStore(0L),
            decrypt = { runCatching { CryptoManager.decrypt(parseEncrypted(it), key) } }
        )
        val hash = "bad-hash"
        val commands = dispatcher.dispatch(clip(51L, hash, "{\"nonce\":\"x\"}", true)).commands
        assertEquals(InboundCommand.AdvanceVersion(51L), commands[0])
        assertEquals(
            InboundCommand.Status(R.string.status_inbound_error, listOf("invalid encrypted payload")),
            commands[1]
        )
        assertFalse(dispatcher.state.isEchoOfLastRemote(hash))
    }

    private fun parseEncrypted(payload: String): EncryptedPayload {
        val obj = org.json.JSONObject(payload)
        return EncryptedPayload(
            nonce = obj.getString("nonce"),
            ciphertext = obj.getString("ciphertext"),
            tag = obj.getString("tag")
        )
    }
}




