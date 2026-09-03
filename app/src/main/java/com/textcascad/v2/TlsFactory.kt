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

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
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

    private val trustAllManager: X509TrustManager by lazy {
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private val trustAllFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }.socketFactory
    }

    /**
     * 根据配置获取 SSLSocketFactory。
     * @param trustAllCerts 是否跳过所有验证（降级调试模式）
     * @param pinnedSha256Hex 证书/公钥 SHA-256 指纹（十六进制，支持冒号分隔或无分隔）
     * @return 对应的 SSLSocketFactory
     */
    fun sslSocketFactory(trustAllCerts: Boolean = false, pinnedSha256Hex: String = ""): SSLSocketFactory {
        val normalizedPin = normalizeFingerprint(pinnedSha256Hex)
        return when {
            normalizedPin.isNotBlank() -> createPinnedFactory(normalizedPin)
            trustAllCerts -> trustAllFactory
            else -> defaultFactory
        }
    }

    /**
     * 与 [sslSocketFactory] 同源的 X509TrustManager（OkHttp `sslSocketFactory(factory, trustManager)`
     * 重载要求显式传入同一实例）。pinning/trustAll 与工厂内自持的逻辑一致；默认分支返回系统信任源。
     */
    internal fun x509TrustManager(trustAllCerts: Boolean = false, pinnedSha256Hex: String = ""): X509TrustManager {
        val normalizedPin = normalizeFingerprint(pinnedSha256Hex)
        return when {
            normalizedPin.isNotBlank() -> createPinningTrustManager(normalizedPin)
            trustAllCerts -> trustAllManager
            else -> systemDefaultTrustManager()
        }
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 获取 HostnameVerifier。
     * @param trustAllCerts 若启用全局信任所有证书则放行全部 Hostname
     * @param pinnedSha256Hex 若启用 Pinning，因公钥强绑定目标证书，放行 Hostname
     */
    internal fun hostnameVerifier(trustAllCerts: Boolean = false, pinnedSha256Hex: String = ""): HostnameVerifier? {
        val normalizedPin = normalizeFingerprint(pinnedSha256Hex)
        return if (trustAllCerts || normalizedPin.isNotBlank()) null else HttpsURLConnection.getDefaultHostnameVerifier()
    }

    /**
     * 计算并格式化 X509 证书的 SHA-256 指纹。
     * @param cert 目标证书
     * @return 64 字符大写十六进制字符串
     */
    fun computeCertSha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * 规整化指纹字符串：去除冒号、空格、横杠并转大写。
     */
    fun normalizeFingerprint(raw: String): String =
        raw.replace(":", "").replace(" ", "").replace("-", "").trim().uppercase()

    private fun createPinningTrustManager(expectedPin: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }
                val serverCert = chain[0]
                val actualCertPin = computeCertSha256Hex(serverCert)
                if (!actualCertPin.equals(expectedPin, ignoreCase = true)) {
                    throw CertificateException("Certificate pin verification failed. Expected: $expectedPin, Actual: $actualCertPin")
                }
            }
        }
    }

    private fun createPinnedFactory(expectedPin: String): SSLSocketFactory {
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(createPinningTrustManager(expectedPin)), SecureRandom())
        }.socketFactory
    }
}

