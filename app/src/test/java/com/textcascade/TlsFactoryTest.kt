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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * TLS 证书 Pinning 与指纹计算测试。
 */
class TlsFactoryTest {

    private class TestX509Certificate(private val rawEncoded: ByteArray) : X509Certificate() {
        override fun getEncoded(): ByteArray = rawEncoded
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal? = null
        override fun getSubjectDN(): Principal? = null
        override fun getNotBefore(): Date = Date()
        override fun getNotAfter(): Date = Date()
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "TestX509Certificate"
        override fun getPublicKey(): PublicKey? = null
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): Set<String>? = null
        override fun getNonCriticalExtensionOIDs(): Set<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    @Test
    fun normalizeFingerprintRemovesSeparatorsAndUppercases() {
        // 场景：用户从各种证书查看器复制指纹，带冒号/空格/破折号
        assertEquals(
            "ABCDEF0123456789",
            TlsFactory.normalizeFingerprint("ab:cd:ef:01:23:45:67:89")
        )
        assertEquals(
            "ABCDEF0123456789",
            TlsFactory.normalizeFingerprint("  ab cd-ef-01:23:45:67:89  ")
        )
        assertEquals("", TlsFactory.normalizeFingerprint("   "))
    }

    @Test
    fun computeCertSha256HexCalculatesExpectedSha256() {
        val dummyBytes = "hello textcascade tls".toByteArray(Charsets.UTF_8)
        val cert = TestX509Certificate(dummyBytes)
        val hashHex = TlsFactory.computeCertSha256Hex(cert)
        assertEquals(64, hashHex.length)
        assertTrue(hashHex.matches(Regex("^[0-9A-F]{64}$")))
    }

    @Test
    fun pinningTrustManagerAcceptsMatchingPin() {
        // 场景：自签证书 SHA-256 与配置完全一致，允许连接
        val dummyBytes = "my-trusted-cert".toByteArray(Charsets.UTF_8)
        val cert = TestX509Certificate(dummyBytes)
        val expectedPin = TlsFactory.computeCertSha256Hex(cert)

        val tm = TlsFactory.x509TrustManager(pinnedSha256Hex = expectedPin)
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun pinningTrustManagerRejectsMismatchedPin() {
        // 场景：中间人替换证书，指纹不匹配，拒绝连接
        val dummyBytes = "my-trusted-cert".toByteArray(Charsets.UTF_8)
        val fakeBytes = "attacker-cert".toByteArray(Charsets.UTF_8)
        val fakeCert = TestX509Certificate(fakeBytes)

        val tm = TlsFactory.x509TrustManager(pinnedSha256Hex = "AA:BB:CC:DD")
        try {
            tm.checkServerTrusted(arrayOf(fakeCert), "RSA")
            fail("不匹配的证书必须抛出 CertificateException")
        } catch (_: CertificateException) {
            // expected
        }
    }

    @Test
    fun hostnameVerifierReturnsNullWhenTrustAllOrPinned() {
        // 场景：Pinning 模式下公钥强绑定，放行 hostname 以支持自签 IP 部署
        assertNull(TlsFactory.hostnameVerifier(trustAllCerts = true))
        assertNull(TlsFactory.hostnameVerifier(trustAllCerts = false, pinnedSha256Hex = "AABBCCDD"))
        assertNotNull(TlsFactory.hostnameVerifier(trustAllCerts = false, pinnedSha256Hex = ""))
    }
}
