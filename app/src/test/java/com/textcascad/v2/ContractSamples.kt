/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

object ContractSamples {
    const val CLIENT_ID = "0f0e8d2a-4b6f-4c1e-9d3a-7f2b1c5e8a90"
    const val CLIENT_NAME = "Pixel8"
    const val CLIP_ID = "1e2d3c4b-5a69-4788-9666-5d4c3b2a1908"
    const val PAYLOAD_TEXT = "foobar"
    const val HASH_FOOBAR = "85944171f73967e8"
    const val TIME_EXAMPLE = "2026-08-18T12:00:00Z"

    const val HELLO_NO_SNAPSHOT =
        """{"type":"hello","clientId":"$CLIENT_ID","clientName":"$CLIENT_NAME","lastServerVersion":7}"""

    const val HELLO_WITH_SNAPSHOT =
        """{"type":"hello","clientId":"$CLIENT_ID","clientName":"$CLIENT_NAME","lastServerVersion":7,""" +
            """"snapshot":{"payload":"$PAYLOAD_TEXT","encrypted":false,"hash":"$HASH_FOOBAR",""" +
            """"localModifiedAtUtc":"$TIME_EXAMPLE"}}"""

    const val CLIP =
        """{"type":"clip","id":"$CLIP_ID","payload":"$PAYLOAD_TEXT","encrypted":false,"hash":"$HASH_FOOBAR"}"""

    const val PONG =
        """{"type":"pong","clientTimeUtc":"$TIME_EXAMPLE"}"""

    const val LOGIN_REQUEST =
        """{"username":"user","password":"pass"}"""

    const val LOGIN_RESPONSE =
        """{"token":"tok-123","expiresAtUtc":"2026-08-19T00:00:00Z","protocolVersion":1,""" +
            """"maxTextBytes":512000,"helloTimeoutSeconds":10,"heartbeatIntervalSeconds":20,""" +
            """"heartbeatTimeoutSeconds":60}"""

    const val WELCOME_NULL =
        """{"type":"welcome","latest":null}"""

    const val WELCOME_LATEST =
        """{"type":"welcome","latest":{"version":9,"payload":"$PAYLOAD_TEXT","encrypted":false,""" +
            """"hash":"$HASH_FOOBAR","fromClientId":"android-a","fromClientName":"Other Phone",""" +
            """"updatedAtUtc":"$TIME_EXAMPLE"}}"""

    const val SERVER_CLIP =
        """{"type":"clip","version":10,"payload":"$PAYLOAD_TEXT","encrypted":false,"hash":"$HASH_FOOBAR"}"""

    const val CLIP_ACK =
        """{"type":"clip_ack","id":"$CLIP_ID","version":11}"""

    const val PING =
        """{"type":"ping","serverTimeUtc":"$TIME_EXAMPLE"}"""

    const val BYE =
        """{"type":"bye","reason":"server_shutdown"}"""

    const val ERROR_TEXT_TOO_LARGE =
        """{"type":"error","code":"text_too_large","message":"clip exceeds maxTextBytes"}"""

    const val ERROR_UNKNOWN_FIELD_TOLERANT =
        """{"type":"clip","version":3,"payload":"p","encrypted":false,"hash":"h","futureField":123}"""
}
