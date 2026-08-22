package com.textcascad.v2.engine

import com.textcascad.v2.ClipConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

object WebSocketFrameCodec {
    const val MASK_CHUNK_BYTES = 8192

    fun encodeTextFrame(payload: ByteArray, maskKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        writeFrameToStream(out, opcode = 0x1, payload = payload, maskKey = maskKey)
        return out.toByteArray()
    }

    fun encodeCloseFrame(code: Int, reason: String, maskKey: ByteArray): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code shr 8) and 0xff).toByte()
        payload[1] = (code and 0xff).toByte()
        if (reasonBytes.isNotEmpty()) {
            reasonBytes.copyInto(payload, 2)
        }
        val out = ByteArrayOutputStream(payload.size + 14)
        writeFrameToStream(out, opcode = 0x8, payload = payload, maskKey = maskKey)
        return out.toByteArray()
    }

    fun encodePongFrame(payload: ByteArray, maskKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        writeFrameToStream(out, opcode = 0xA, payload = payload, maskKey = maskKey)
        return out.toByteArray()
    }

    fun writeFrameToStream(out: OutputStream, opcode: Int, payload: ByteArray, maskKey: ByteArray) {
        require(maskKey.size == 4) { "Mask key must be 4 bytes" }
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size <= 65535 -> {
                out.write(0x80 or 126)
                out.write((payload.size ushr 8) and 0xff)
                out.write(payload.size and 0xff)
            }
            else -> {
                out.write(0x80 or 127)
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((size ushr shift) and 0xff).toInt())
                }
            }
        }
        out.write(maskKey)
        val maskedChunk = ByteArray(MASK_CHUNK_BYTES)
        var offset = 0
        var maskIndex = 0
        while (offset < payload.size) {
            val length = minOf(maskedChunk.size, payload.size - offset)
            for (i in 0 until length) {
                maskedChunk[i] = (payload[offset + i].toInt() xor maskKey[maskIndex].toInt()).toByte()
                maskIndex = (maskIndex + 1) and 3
            }
            out.write(maskedChunk, 0, length)
            offset += length
        }
        out.flush()
    }

    /** 纯逻辑帧处理器；onClose 收到解析后的 close code 与 reason。 */
    @Throws(IOException::class)
    fun processIncomingFrame(
        fin: Boolean,
        opcode: Int,
        payload: ByteArray,
        currentFragmentedStream: ByteArrayOutputStream?,
        onText: (String) -> Unit,
        onSendPong: (ByteArray) -> Unit,
        onClose: (Int, String) -> Unit,
        maxMessageBytes: Long = ClipConfig.MAX_TRANSPORT_BYTES
    ): Pair<ByteArrayOutputStream?, Boolean> {
        require(maxMessageBytes in ClipConfig.MIN_CLIPBOARD_BYTES..ClipConfig.MAX_TRANSPORT_BYTES) {
            "maxMessageBytes must be between 1 and ${ClipConfig.MAX_TRANSPORT_BYTES}"
        }
        when (opcode) {
            0x0 -> {
                val stream = currentFragmentedStream
                    ?: throw IOException("Received WebSocket continuation frame without initial frame")
                if (stream.size() + payload.size > maxMessageBytes) {
                    throw IOException("Fragmented WebSocket message exceeded size limit")
                }
                stream.write(payload)
                return if (fin) {
                    val fullText = stream.toByteArray().toString(Charsets.UTF_8)
                    onText(fullText)
                    Pair(null, true)
                } else {
                    Pair(stream, true)
                }
            }
            0x1 -> {
                if (currentFragmentedStream != null) {
                    throw IOException("Received new WebSocket text frame before previous fragmented message completed")
                }
                if (payload.size > maxMessageBytes) {
                    throw IOException("WebSocket message exceeds transport limit")
                }
                return if (fin) {
                    onText(payload.toString(Charsets.UTF_8))
                    Pair(null, true)
                } else {
                    val newStream = ByteArrayOutputStream().apply {
                        write(payload)
                    }
                    Pair(newStream, true)
                }
            }
            0x2 -> {
                throw IOException("Binary WebSocket frames (0x2) are not supported")
            }
            0x8 -> {
                check(fin) { "Control frames cannot be fragmented" }
                check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                val code = if (payload.size >= 2) {
                    ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                } else {
                    1005
                }
                val reason = if (payload.size > 2) {
                    payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8)
                } else {
                    ""
                }
                onClose(code, reason)
                return Pair(currentFragmentedStream, false)
            }
            0x9 -> {
                check(fin) { "Control frames cannot be fragmented" }
                check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                onSendPong(payload)
                return Pair(currentFragmentedStream, true)
            }
            0xA -> {
                check(fin) { "Control frames cannot be fragmented" }
                check(payload.size <= 125) { "Control frame payload exceeds 125 bytes" }
                return Pair(currentFragmentedStream, true)
            }
            else -> {
                throw IOException("Unsupported WebSocket opcode: $opcode")
            }
        }
    }
}
