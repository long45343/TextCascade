/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipApiClientBoundaryTest {
    @Test
    fun sameHostDefaultHttpAndHttpsPortsAreAllowedButCustomChangesAreRejected() {
        val client = ClipApiClient()
        fun allows(from: String, to: String): Boolean = client.isSameOriginRedirect(
            URI(from),
            URI(to)
        )

        assertTrue(allows("http://example.test/login", "https://example.test/login"))
        assertTrue(allows("https://example.test/login", "http://example.test/login"))
        assertTrue(allows("http://example.test:8080/login", "https://example.test:8080/login"))
        assertFalse(allows("http://example.test:8080/login", "http://example.test:8081/login"))
        assertFalse(allows("http://example.test/login", "https://other.test/login"))
    }

    @Test
    fun missingMaxSizeUsesThe512KiBDefault() {
        val result = loginWithMaxSize("{}")
        assertEquals(ClipConfig.DEFAULT_MAX_SIZE_BYTES, result.maxSizeBytes)
    }

    @Test
    fun invalidMaxSizeIsRejectedInsteadOfClamped() {
        for (value in listOf("0", "-1", (ClipConfig.MAX_CLIPBOARD_BYTES + 1).toString())) {
            try {
                loginWithMaxSize("{\"maxsize\":$value}")
                throw AssertionError("maxsize=$value should be rejected")
            } catch (error: LoginRequestFailedException) {
                assertTrue(error.message.orEmpty().contains("max-size invalid"))
            }
        }
    }

    @Test
    fun boundaryMaxSizesAreAccepted() {
        assertEquals(1L, loginWithMaxSize("{\"maxsize\":1}").maxSizeBytes)
        assertEquals(
            ClipConfig.MAX_CLIPBOARD_BYTES,
            loginWithMaxSize("{\"maxsize\":${ClipConfig.MAX_CLIPBOARD_BYTES}}\n").maxSizeBytes
        )
    }

    private fun loginWithMaxSize(maxSizeBody: String): LoginResult {
        val server = MiniHttpServer(maxSizeBody)
        server.start()
        return try {
            ClipApiClient().login(
                serverUrl = "http://127.0.0.1:${server.port}",
                username = "user",
                passwordSha3 = "sha3",
                hashedPasswordBase64 = "key"
            )
        } finally {
            server.stop()
        }
    }

    private class MiniHttpServer(private val maxSizeBody: String) {
        private val serverSocket = ServerSocket(0, 10, InetSocketAddress("127.0.0.1", 0).address)
        private lateinit var worker: Thread
        val port: Int get() = serverSocket.localPort

        fun start() {
            worker = thread(isDaemon = true, name = "textcascade-test-http") {
                while (!serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread(isDaemon = true) { handle(client) }
                }
            }
        }

        fun stop() {
            serverSocket.close()
            worker.join(1000)
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val request = readHeader(input)
                val requestLine = request.substringBefore("\r\n")
                val method = requestLine.substringBefore(" ")
                val path = requestLine.substringAfter(" ").substringBefore(" ")
                val body = when {
                    path == "/login" && method == "GET" -> "<input name=\"_csrf\" value=\"csrf\">"
                    path == "/login" -> "ok"
                    path == "/server-mode" -> "{\"mode\":\"P2S\"}"
                    path == "/max-size" -> maxSizeBody
                    path == "/csrf-token" -> "{\"token\":\"csrf-session\"}"
                    else -> "not found"
                }
                val cookie = if (path == "/login") "Set-Cookie: session=authenticated; Path=/\r\n" else ""
                val response = body.toByteArray(Charsets.UTF_8)
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(
                    "HTTP/1.1 ${if (path.startsWith("/")) 200 else 404} OK\r\n".toByteArray()
                )
                output.write("Content-Type: application/json\r\n".toByteArray())
                output.write(cookie.toByteArray())
                output.write("Connection: close\r\nContent-Length: ${response.size}\r\n\r\n".toByteArray())
                output.write(response)
                output.flush()
            }
        }

        private fun readHeader(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            var matched = 0
            while (matched < 4) {
                val value = input.read()
                if (value < 0) break
                bytes += value.toByte()
                matched = when {
                    matched == 0 && value == '\r'.code -> 1
                    matched == 1 && value == '\n'.code -> 2
                    matched == 2 && value == '\r'.code -> 3
                    matched == 3 && value == '\n'.code -> 4
                    else -> 0
                }
            }
            return bytes.toByteArray().toString(Charsets.ISO_8859_1)
        }
    }
}
