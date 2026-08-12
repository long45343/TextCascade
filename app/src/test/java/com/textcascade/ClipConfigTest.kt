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

class ClipConfigTest {

    @Test
    fun httpToWs() {
        val result = ClipConfig.websocketUrlFromServerUrl("http://localhost:8080")
        assertEquals("ws://localhost:8080/clipsocket", result)
    }

    @Test
    fun httpsToWss() {
        val result = ClipConfig.websocketUrlFromServerUrl("https://example.com")
        assertEquals("wss://example.com/clipsocket", result)
    }

    @Test
    fun preservesPath() {
        val result = ClipConfig.websocketUrlFromServerUrl("http://example.com/clipcascade")
        assertEquals("ws://example.com/clipcascade/clipsocket", result)
    }

    @Test
    fun preservesPort() {
        val result = ClipConfig.websocketUrlFromServerUrl("https://example.com:8443")
        assertTrue(result.startsWith("wss://example.com:8443/clipsocket"))
    }

    @Test
    fun trimsTrailingSlash() {
        val result = ClipConfig.websocketUrlFromServerUrl("http://localhost:8080/")
        assertEquals("ws://localhost:8080/clipsocket", result)
    }

    @Test
    fun preservesQueryParams() {
        val result = ClipConfig.websocketUrlFromServerUrl("http://localhost:8080?token=abc")
        assertTrue(result.contains("token=abc"))
    }

    @Test(expected = IllegalStateException::class)
    fun unsupportedSchemeThrows() {
        ClipConfig.websocketUrlFromServerUrl("ftp://example.com")
    }
}
