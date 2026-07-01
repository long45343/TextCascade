/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
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

import org.json.JSONObject

object JsonUtil {
    fun clipMessage(payload: String, type: String = "text"): String {
        return JSONObject()
            .put("payload", payload)
            .put("type", type)
            .toString()
    }

    fun parseClipMessage(json: String): ClipMessage {
        val obj = JSONObject(json)
        return ClipMessage(
            payload = obj.getString("payload"),
            type = obj.optString("type", "text")
        )
    }

    fun encryptedPayload(payload: EncryptedPayload): String {
        return JSONObject()
            .put("nonce", payload.nonce)
            .put("ciphertext", payload.ciphertext)
            .put("tag", payload.tag)
            .toString()
    }

    fun parseEncryptedPayload(json: String): EncryptedPayload {
        val obj = JSONObject(json)
        return EncryptedPayload(
            nonce = obj.getString("nonce"),
            ciphertext = obj.getString("ciphertext"),
            tag = obj.getString("tag")
        )
    }

    fun longField(json: String, name: String, defaultValue: Long): Long {
        return JSONObject(json).optLong(name, defaultValue)
    }

    fun stringField(json: String, name: String, defaultValue: String = ""): String {
        return JSONObject(json).optString(name, defaultValue)
    }
}

data class ClipMessage(
    val payload: String,
    val type: String
)
