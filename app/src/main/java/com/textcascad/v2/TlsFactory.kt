/*
 * TextCascade Android v2 - Native clipboard sync client
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

package com.textcascad.v2

import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object TlsFactory {
    private val defaultFactory: SSLSocketFactory by lazy {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }
    private val trustAllFactory: SSLSocketFactory by lazy {
        val trustAllManager = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) = Unit

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllManager, SecureRandom())
        }.socketFactory
    }

    fun sslSocketFactory(trustAllCerts: Boolean): SSLSocketFactory {
        return if (trustAllCerts) trustAllFactory else defaultFactory
    }

    internal fun hostnameVerifier(trustAllCerts: Boolean): HostnameVerifier? =
        if (trustAllCerts) null else HttpsURLConnection.getDefaultHostnameVerifier()
}
