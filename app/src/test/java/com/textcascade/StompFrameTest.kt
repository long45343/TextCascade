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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StompFrameTest {

    @Test
    fun marshallProducesCorrectFrame() {
        val frame = StompFrame(
            command = "SEND",
            headers = linkedMapOf("destination" to "/app/cliptext"),
            body = "hello"
        )
        val result = frame.marshall()
        assertTrue(result.startsWith("SEND\n"))
        assertTrue(result.contains("destination:/app/cliptext\n"))
        assertTrue(result.contains("content-length:5\n"))
        assertTrue(result.endsWith("hello\u0000"))
    }

    @Test
    fun parseSimpleFrame() {
        val raw = "CONNECTED\nversion:1.2\nheart-beat:10000,20000\n\n\u0000"
        val frame = StompFrame.parse(raw)
        assertEquals("CONNECTED", frame.command)
        assertEquals("1.2", frame.headers["version"])
        assertEquals("10000,20000", frame.headers["heart-beat"])
        assertEquals("", frame.body)
    }

    @Test
    fun parseRoundTrip() {
        val original = StompFrame(
            command = "MESSAGE",
            headers = linkedMapOf(
                "destination" to "/user/queue/cliptext",
                "message-id" to "msg-123"
            ),
            body = """{"payload":"hello","type":"text"}"""
        )
        val marshalled = original.marshall()
        val parsed = StompFrame.parse(marshalled)
        assertEquals(original.command, parsed.command)
        assertEquals(original.body, parsed.body)
        assertEquals(original.headers["destination"], parsed.headers["destination"])
        assertEquals(original.headers["message-id"], parsed.headers["message-id"])
    }

    @Test
    fun escapeAndUnescapeColons() {
        val originalValue = "value:with:colons"
        val frame = StompFrame(
            command = "MESSAGE",
            headers = linkedMapOf("custom" to originalValue),
            body = ""
        )
        val marshalled = frame.marshall()
        // Verify the escaped form is present
        val escapedPart = "custom:value\\cwith\\ccolons"
        assertTrue(marshalled.contains(escapedPart))
        // Verify round-trip unescaping
        val parsed = StompFrame.parse(marshalled)
        assertEquals(originalValue, parsed.headers["custom"])
    }

    @Test
    fun escapeAndUnescapeBackslash() {
        val originalValue = """C:\Users\test"""
        val frame = StompFrame(
            command = "SEND",
            headers = linkedMapOf("path" to originalValue),
            body = ""
        )
        val marshalled = frame.marshall()
        val parsed = StompFrame.parse(marshalled)
        assertEquals(originalValue, parsed.headers["path"])
    }

    @Test
    fun escapeAndUnescapeNewlines() {
        val originalValue = "line1\nline2\r\nline3"
        val frame = StompFrame(
            command = "SEND",
            headers = linkedMapOf("multi" to originalValue),
            body = ""
        )
        val marshalled = frame.marshall()
        val parsed = StompFrame.parse(marshalled)
        assertEquals(originalValue, parsed.headers["multi"])
    }

    @Test
    fun contentLengthPreciseBodyExtraction() {
        // Body containing \n\n should not be truncated
        val body = "line1\n\nline2"
        val frame = StompFrame(
            command = "MESSAGE",
            headers = linkedMapOf("destination" to "/test"),
            body = body
        )
        val marshalled = frame.marshall()
        val parsed = StompFrame.parse(marshalled)
        assertEquals(body, parsed.body)
    }

    @Test
    fun contentLengthClampsToActualSize() {
        val raw = "MESSAGE\ndestination:/test\ncontent-length:100\n\nshort\u0000"
        val frame = StompFrame.parse(raw)
        assertEquals("short", frame.body)
    }

    @Test
    fun contentLengthZeroProducesEmptyBody() {
        val raw = "MESSAGE\ndestination:/test\ncontent-length:0\n\n\u0000"
        val frame = StompFrame.parse(raw)
        assertEquals("", frame.body)
    }

    @Test
    fun parseFrameWithoutBody() {
        val raw = "CONNECTED\nversion:1.2\n\n\u0000"
        val frame = StompFrame.parse(raw)
        assertEquals("CONNECTED", frame.command)
        assertEquals("1.2", frame.headers["version"])
        assertEquals("", frame.body)
    }
}
