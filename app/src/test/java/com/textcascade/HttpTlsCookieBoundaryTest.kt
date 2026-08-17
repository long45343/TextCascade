/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTlsCookieBoundaryTest {

    private class FakeHttpURLConnection(
        url: URL,
        private val responseCodeValue: Int = 200,
        private val responseBody: ByteArray = ByteArray(0),
        private val headerFieldsMap: Map<String?, List<String>> = emptyMap(),
        private val errorBody: ByteArray? = null,
        private val throwOnStreamRead: Boolean = false
    ) : HttpURLConnection(url) {
        val requestedUrl: URL get() = url
        val disconnected = AtomicBoolean(false)
        val outputStreamBytes = ByteArrayOutputStream()
        val requestHeaders = LinkedHashMap<String, String>()

        override fun getResponseCode(): Int = responseCodeValue

        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            return headerFieldsMap[name]?.firstOrNull()
        }

        override fun getHeaderFields(): Map<String?, List<String>> = headerFieldsMap

        override fun getInputStream(): InputStream {
            if (responseCodeValue >= 400) {
                throw IOException("HTTP $responseCodeValue")
            }
            if (throwOnStreamRead) {
                return object : InputStream() {
                    override fun read(): Int = throw IOException("Simulated network stream read error")
                }
            }
            return ByteArrayInputStream(responseBody)
        }

        override fun getErrorStream(): InputStream? {
            if (errorBody != null) return ByteArrayInputStream(errorBody)
            return if (responseCodeValue >= 400) ByteArrayInputStream(responseBody) else null
        }

        override fun getOutputStream(): OutputStream = outputStreamBytes

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun disconnect() {
            disconnected.set(true)
        }

        override fun usingProxy(): Boolean = false
        override fun connect() {}
    }

    private class FakeHttpsURLConnection(url: URL) : HttpsURLConnection(url) {
        var sslFactorySet: javax.net.ssl.SSLSocketFactory? = null
        var hostnameVerifierSet: HostnameVerifier? = null
        val requestHeaders = LinkedHashMap<String, String>()
        val outputStreamBytes = ByteArrayOutputStream()
        val disconnected = AtomicBoolean(false)

        override fun setSSLSocketFactory(sf: javax.net.ssl.SSLSocketFactory?) {
            sslFactorySet = sf
        }

        override fun setHostnameVerifier(v: HostnameVerifier?) {
            hostnameVerifierSet = v
        }

        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getPeerPrincipal(): java.security.Principal = java.security.Principal { "peer" }
        override fun getLocalPrincipal(): java.security.Principal? = null
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream("OK".toByteArray())
        override fun getOutputStream(): OutputStream = outputStreamBytes
        override fun setRequestProperty(key: String, value: String) { requestHeaders[key] = value }
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy(): Boolean = false
        override fun connect() {}
    }

    @Test
    fun requestThrowsExceptionDisconnectsConnection() {
        val fakeConn = FakeHttpURLConnection(
            url = URL("http://example.com/test"),
            throwOnStreamRead = true
        )
        val client = ClipApiClient(connectionFactory = { fakeConn })
        try {
            client.request(url = "http://example.com/test", method = "GET")
            fail("Expected IOException")
        } catch (_: IOException) {
            assertTrue(fakeConn.disconnected.get())
        }
    }

    @Test
    fun responseBodyOver2MBThrowsIOExceptionAndDisconnects() {
        val oversizedData = ByteArray(ClipConfig.MAX_TRANSPORT_BYTES.toInt() + 1)
        val fakeConn = FakeHttpURLConnection(
            url = URL("http://example.com/large"),
            responseCodeValue = 200,
            responseBody = oversizedData
        )
        val client = ClipApiClient(connectionFactory = { fakeConn })
        try {
            client.request(url = "http://example.com/large", method = "GET")
            fail("Expected IOException for oversized response")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("size limit") == true)
            assertTrue(fakeConn.disconnected.get())
        }
    }

    @Test
    fun errorBodyOver64KBThrowsIOExceptionAndDisconnects() {
        val oversizedError = ByteArray(64 * 1024 + 1)
        val fakeConn = FakeHttpURLConnection(
            url = URL("http://example.com/error"),
            responseCodeValue = 500,
            errorBody = oversizedError
        )
        val client = ClipApiClient(connectionFactory = { fakeConn })
        try {
            client.request(url = "http://example.com/error", method = "GET")
            fail("Expected IOException for oversized error body")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("size limit") == true)
            assertTrue(fakeConn.disconnected.get())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun ftpUrlRejected() {
        ClipApiClient().validateHttpUrl("ftp://example.com/path")
    }

    @Test(expected = IllegalArgumentException::class)
    fun noHostUrlRejected() {
        ClipApiClient().validateHttpUrl("http:///path")
    }

    @Test
    fun redirectHttp80ToHttps443FlowConvergesToHttpsAndWss() {
        val connectionsCreated = mutableListOf<FakeHttpURLConnection>()
        val client = ClipApiClient(connectionFactory = { url ->
            val conn = when (url.toString()) {
                "http://example.com/login" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 301,
                    headerFieldsMap = mapOf(
                        "Location" to listOf("https://example.com/login"),
                        "Set-Cookie" to listOf("initial_session=123; Path=/; Secure")
                    )
                )
                "https://example.com/login" -> {
                    FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = 200,
                        responseBody = "<input name=\"_csrf\" value=\"token-xyz\">".toByteArray(),
                        headerFieldsMap = mapOf(
                            "Set-Cookie" to listOf("auth_session=abc; Path=/; Secure")
                        )
                    )
                }
                "https://example.com/server-mode" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"mode\":\"P2S\"}".toByteArray()
                )
                "https://example.com/max-size" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"maxsize\":1048576}".toByteArray()
                )
                "https://example.com/csrf-token" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"token\":\"new-csrf\"}".toByteArray()
                )
                else -> error("Unexpected URL requested: $url")
            }
            connectionsCreated.add(conn)
            conn
        })

        val result = client.login(
            serverUrl = "http://example.com",
            username = "admin",
            passwordSha3 = "sha3pwd",
            hashedPasswordBase64 = "base64pwd"
        )

        assertEquals("https://example.com", result.normalizedServerUrl)
        assertEquals("wss://example.com/clipsocket", result.websocketUrl)
        assertEquals("new-csrf", result.csrfToken)
        assertEquals(1048576L, result.maxSizeBytes)
        assertTrue(result.cookieHeader.contains("auth_session=abc"))

        val requestedUrls = connectionsCreated.map { it.requestedUrl.toString() }
        assertTrue(requestedUrls.all { it.startsWith("https://") || it == "http://example.com/login" })
        assertTrue(connectionsCreated.drop(1).all { it.requestHeaders["Cookie"]?.contains("auth_session=abc") == true || it.requestHeaders["Cookie"]?.contains("initial_session=123") == true })
        assertEquals("https://example.com/login", requestedUrls[1])
        assertTrue(requestedUrls.drop(2).all { it.startsWith("https://example.com/") })

        // All connections should be disconnected
        assertTrue(connectionsCreated.all { it.disconnected.get() })
    }

    @Test
    fun invalidRedirectLocationIsWrappedAndDoesNotCreateTargetConnection() {
        val original = FakeHttpURLConnection(
            url = URL("http://example.com/login"),
            responseCodeValue = 302,
            headerFieldsMap = mapOf("Location" to listOf("http://[invalid"))
        )
        var factoryCalls = 0
        val client = ClipApiClient(connectionFactory = { url ->
            factoryCalls++
            if (factoryCalls > 1) fail("Invalid redirect must not create target connection: $url")
            original
        })

        try {
            client.request("http://example.com/login", "GET")
            fail("Expected invalid Location failure")
        } catch (error: LoginRequestFailedException) {
            assertTrue(error.message?.contains("Invalid redirect Location") == true)
            assertEquals(1, factoryCalls)
            assertTrue(original.disconnected.get())
        }
    }

    @Test
    fun httpsRequestSetsExpectedTlsPolicyAndHttpRequestDoesNot() {
        val secure = FakeHttpsURLConnection(URL("https://example.com/test"))
        val secureClient = ClipApiClient(trustAllCerts = false, connectionFactory = { secure })
        secureClient.request("https://example.com/test", "GET")
        assertEquals(TlsFactory.sslSocketFactory(false), secure.sslFactorySet)
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier(), secure.hostnameVerifierSet)

        val trustAll = FakeHttpsURLConnection(URL("https://example.com/test"))
        ClipApiClient(trustAllCerts = true, connectionFactory = { trustAll })
            .request("https://example.com/test", "GET")
        assertEquals(TlsFactory.sslSocketFactory(true), trustAll.sslFactorySet)
        assertNull(trustAll.hostnameVerifierSet)

        val http = FakeHttpURLConnection(URL("http://example.com/test"))
        ClipApiClient(connectionFactory = { http }).request("http://example.com/test", "GET")
        assertTrue(http.requestHeaders["Cookie"].isNullOrEmpty())
    }

    @Test
    fun redirectHttpsToHttpSecureCookieNotSentOnDowngrade() {
        val jar = HttpCookieJar()
        val httpsUri = URI("https://example.com/app")
        jar.store(httpsUri, mapOf("Set-Cookie" to listOf(
            "sec=secret123; Path=/; Secure",
            "insec=plain456; Path=/"
        )))

        val httpsHeader = jar.header(httpsUri)
        assertTrue(httpsHeader.contains("sec=secret123"))
        assertTrue(httpsHeader.contains("insec=plain456"))

        val httpUri = URI("http://example.com/app")
        val httpHeader = jar.header(httpUri)
        assertFalse(httpHeader.contains("sec=secret123"))
        assertTrue(httpHeader.contains("insec=plain456"))
    }

    @Test
    fun crossHostRedirectRejectedAndCookiesNotSent() {
        val jar = HttpCookieJar()
        jar.store(URI("http://example.com/login"), mapOf("Set-Cookie" to listOf("secret=1; Path=/")))
        val conn1 = FakeHttpURLConnection(
            url = URL("http://example.com/login"),
            responseCodeValue = 302,
            headerFieldsMap = mapOf("Location" to listOf("http://attacker.com/steal"))
        )
        val client = ClipApiClient(connectionFactory = { url ->
            if (url.host == "attacker.com") {
                fail("Attacker host should never be requested")
            }
            conn1
        })

        try {
            client.request(url = "http://example.com/login", method = "GET", cookieJar = jar)
            fail("Expected LoginRequestFailedException for cross-origin redirect")
        } catch (e: LoginRequestFailedException) {
            assertTrue(e.message?.contains("Cross-origin") == true)
            assertTrue(conn1.disconnected.get())
        }
    }

    @Test
    fun redirectLoopExceeds5ThrowsExceptionAndDisconnectsAll() {
        val connections = mutableListOf<FakeHttpURLConnection>()
        val client = ClipApiClient(connectionFactory = { url ->
            val conn = FakeHttpURLConnection(
                url = url,
                responseCodeValue = 302,
                headerFieldsMap = mapOf("Location" to listOf("http://example.com/loop"))
            )
            connections.add(conn)
            conn
        })

        try {
            client.request(url = "http://example.com/loop", method = "GET")
            fail("Expected too many redirects exception")
        } catch (e: LoginRequestFailedException) {
            assertTrue(e.message?.contains("Too many redirects") == true)
            assertEquals(6, connections.size) // initial + 5 redirects = 6
            assertTrue(connections.all { it.disconnected.get() })
        }
    }

    @Test
    fun postRedirect301302303FollowsSameOriginTargetAsGetWithoutBody() {
        for (code in listOf(301, 302, 303)) {
            val connections = mutableListOf<FakeHttpURLConnection>()
            val client = ClipApiClient(connectionFactory = { url ->
                val conn = when (url.toString()) {
                    "http://example.com/login" -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = code,
                        headerFieldsMap = mapOf("Location" to listOf("http://example.com/login2"))
                    )
                    "http://example.com/login2" -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = 200,
                        responseBody = "OK".toByteArray()
                    )
                    else -> error("Unexpected URL requested: $url")
                }
                connections.add(conn)
                conn
            })

            val result = client.request(
                url = "http://example.com/login",
                method = "POST",
                body = "username=admin",
                contentType = "application/x-www-form-urlencoded"
            )

            assertEquals(200, result.statusCode)
            assertEquals("OK", result.body)
            assertEquals(2, connections.size)
            val redirected = connections[1]
            assertEquals("GET", redirected.requestMethod)
            assertEquals("", redirected.outputStreamBytes.toString(Charsets.UTF_8))
            assertTrue(redirected.requestHeaders["Content-Type"].isNullOrEmpty())
            assertTrue(connections.all { it.disconnected.get() })
        }
    }

    @Test
    fun postRedirect307And308PreservesMethodAndBody() {
        for (code in listOf(307, 308)) {
            val connections = mutableListOf<FakeHttpURLConnection>()
            val client = ClipApiClient(connectionFactory = { url ->
                val conn = when (url.toString()) {
                    "http://example.com/login" -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = code,
                        headerFieldsMap = mapOf("Location" to listOf("http://example.com/login_target"))
                    )
                    "http://example.com/login_target" -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = 200,
                        responseBody = "OK".toByteArray()
                    )
                    else -> error("Unexpected url $url")
                }
                connections.add(conn)
                conn
            })

            val result = client.request(
                url = "http://example.com/login",
                method = "POST",
                body = "post_payload=123",
                contentType = "application/x-www-form-urlencoded"
            )
            assertEquals(200, result.statusCode)
            assertEquals("OK", result.body)
            assertEquals(2, connections.size)
            assertEquals("POST", connections[1].requestMethod)
            assertEquals("post_payload=123", connections[1].outputStreamBytes.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun clipCascadeFormLoginSuccess302BuildsAuthenticatedSessionResult() {
        val connections = mutableListOf<FakeHttpURLConnection>()
        var callCount = 0
        val client = ClipApiClient(connectionFactory = { url ->
            val index = callCount++
            val conn = when (url.toString()) {
                "http://example.com/login" -> when (index) {
                    0 -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = 200,
                        responseBody = "<input name=\"_csrf\" value=\"login-csrf\">".toByteArray(),
                        headerFieldsMap = mapOf("Set-Cookie" to listOf("LOGIN_SESSION=pre-auth; Path=/"))
                    )
                    1 -> FakeHttpURLConnection(
                        url = url,
                        responseCodeValue = 302,
                        headerFieldsMap = mapOf(
                            "Location" to listOf("/"),
                            "Set-Cookie" to listOf("AUTH_SESSION=authenticated; Path=/")
                        )
                    )
                    else -> error("Unexpected duplicate login request: $url")
                }
                "http://example.com/" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "<html>home</html>".toByteArray()
                )
                "http://example.com/server-mode" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"mode\":\"P2S\"}".toByteArray()
                )
                "http://example.com/max-size" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"maxsize\":524288}".toByteArray()
                )
                "http://example.com/csrf-token" -> FakeHttpURLConnection(
                    url = url,
                    responseCodeValue = 200,
                    responseBody = "{\"token\":\"session-csrf\"}".toByteArray()
                )
                else -> error("Unexpected URL requested: $url")
            }
            connections.add(conn)
            conn
        })

        val result = client.login(
            serverUrl = "http://example.com",
            username = "user",
            passwordSha3 = "sha3",
            hashedPasswordBase64 = "key"
        )

        assertEquals("http://example.com", result.normalizedServerUrl)
        assertEquals("ws://example.com/clipsocket", result.websocketUrl)
        assertEquals("session-csrf", result.csrfToken)
        assertEquals(524288L, result.maxSizeBytes)
        assertTrue(result.cookieHeader.contains("AUTH_SESSION=authenticated"))
        assertEquals(6, connections.size)

        val loginPostBody = connections[1].outputStreamBytes.toString(Charsets.UTF_8)
        assertTrue(loginPostBody.contains("username=user"))
        assertTrue(loginPostBody.contains("password=sha3"))
        assertTrue(loginPostBody.contains("_csrf=login-csrf"))

        val homeGet = connections[2]
        assertEquals("GET", homeGet.requestMethod)
        assertEquals("", homeGet.outputStreamBytes.toString(Charsets.UTF_8))
        assertTrue(homeGet.requestHeaders["Content-Type"].isNullOrEmpty())

        for (index in 2..5) {
            assertTrue(
                connections[index].requestHeaders["Cookie"]?.contains("AUTH_SESSION=authenticated") == true
            )
        }
        assertTrue(connections.all { it.disconnected.get() })
    }
    @Test
    fun tlsFactoryTrustAllVsDefaultBehavior() {
        val defaultFactory = TlsFactory.sslSocketFactory(trustAllCerts = false)
        val trustAllFactory = TlsFactory.sslSocketFactory(trustAllCerts = true)
        assertNotNull(defaultFactory)
        assertNotNull(trustAllFactory)

        val defaultVerifier = TlsFactory.hostnameVerifier(trustAllCerts = false)
        assertNotNull(defaultVerifier)
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier(), defaultVerifier)

        val trustAllVerifier = TlsFactory.hostnameVerifier(trustAllCerts = true)
        assertNull(trustAllVerifier)
    }

    private class FakeSSLSocket(
        private val sessionMock: SSLSession
    ) : SSLSocket() {
        val closed = AtomicBoolean(false)
        val output = ByteArrayOutputStream()
        override fun getSession(): SSLSession = sessionMock
        override fun close() { closed.set(true) }
        override fun isClosed(): Boolean = closed.get()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = output

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
        override fun startHandshake() {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
    }

    private fun createFakeSession(): SSLSession {
        return Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                String::class.java -> "test-session"
                else -> null
            }
        } as SSLSession
    }

    @Test
    fun wssHostnameVerificationFailsClosesSocketAndThrowsError() {
        val sessionMock = createFakeSession()
        val fakeSocket = FakeSSLSocket(sessionMock)
        val verifierMock = HostnameVerifier { _, _ -> false }

        val latch = CountDownLatch(1)
        val receivedError = AtomicBoolean(false)
        val ws = RawWebSocketClient(
            url = "wss://example.com/ws",
            cookieHeader = "c=1",
            listener = object : RawWebSocketClient.Listener {
                override fun onOpen() {}
                override fun onText(text: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) {
                    if (error is SSLPeerUnverifiedException) {
                        receivedError.set(true)
                    }
                    latch.countDown()
                }
            },
            trustAllCerts = false,
            socketFactory = { _, _, _, _ -> fakeSocket },
            hostnameVerifierFactory = { verifierMock }
        )

        ws.connect()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(receivedError.get())
        assertTrue(fakeSocket.closed.get())
        val handshake = fakeSocket.output.toString(Charsets.US_ASCII.name())
        assertTrue(handshake.isEmpty())
        assertFalse(handshake.contains("Cookie:"))
        assertFalse(handshake.contains("GET /ws"))
        assertFalse(handshake.contains("Sec-WebSocket-Key"))
    }

    @Test
    fun wssHostnameVerificationPassesBeforeUpgradeAndCookieIsSent() {
        val sessionMock = createFakeSession()
        val fakeSocket = FakeSSLSocket(sessionMock)
        val verifierCalled = AtomicBoolean(false)
        val verifier = HostnameVerifier { host, _ ->
            verifierCalled.set(true)
            host == "example.com"
        }
        val errorLatch = CountDownLatch(1)
        val errorRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val ws = RawWebSocketClient(
            url = "wss://example.com/ws?x=1",
            cookieHeader = "c=1",
            listener = object : RawWebSocketClient.Listener {
                override fun onOpen() {}
                override fun onText(text: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) {
                    errorRef.set(error)
                    errorLatch.countDown()
                }
            },
            trustAllCerts = false,
            socketFactory = { _, _, _, _ -> fakeSocket },
            hostnameVerifierFactory = { verifier }
        )

        ws.connect()
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertTrue(verifierCalled.get())
        assertFalse(errorRef.get() is SSLPeerUnverifiedException)
        val handshake = fakeSocket.output.toString(Charsets.US_ASCII.name())
        assertTrue(handshake.contains("GET /ws?x=1 HTTP/1.1"))
        assertTrue(handshake.contains("Cookie: c=1"))
        assertTrue(handshake.contains("Sec-WebSocket-Key:"))
    }

    @Test
    fun wssTrustAllSkipsHostnameVerification() {
        val sessionMock = createFakeSession()
        val fakeSocket = FakeSSLSocket(sessionMock)
        val verifierCalled = AtomicBoolean(false)
        val verifierMock = HostnameVerifier { _, _ ->
            verifierCalled.set(true)
            false
        }

        val latch = CountDownLatch(1)
        val ws = RawWebSocketClient(
            url = "wss://example.com/ws",
            cookieHeader = "c=1",
            listener = object : RawWebSocketClient.Listener {
                override fun onOpen() {}
                override fun onText(text: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: Throwable) {
                    latch.countDown()
                }
            },
            trustAllCerts = true,
            socketFactory = { _, _, _, _ -> fakeSocket },
            hostnameVerifierFactory = { verifierMock }
        )

        ws.connect()
        latch.await(1, TimeUnit.SECONDS)
        assertFalse(verifierCalled.get())
        val handshake = fakeSocket.output.toString(Charsets.US_ASCII.name())
        assertTrue(handshake.contains("GET /ws HTTP/1.1"))
        assertTrue(handshake.contains("Cookie: c=1"))
    }
}
