/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

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
    fun sslSocketFactoryReturnsPinnedFactoryWhenPinProvided() {
        val factory = TlsFactory.sslSocketFactory(pinnedSha256Hex = "AA:BB:CC:DD")
        assertNotNull(factory)
    }

    @Test
    fun hostnameVerifierReturnsNullWhenTrustAllOrPinned() {
        assertNull(TlsFactory.hostnameVerifier(trustAllCerts = true))
        assertNull(TlsFactory.hostnameVerifier(trustAllCerts = false, pinnedSha256Hex = "AABBCCDD"))
        assertNotNull(TlsFactory.hostnameVerifier(trustAllCerts = false, pinnedSha256Hex = ""))
    }
}

