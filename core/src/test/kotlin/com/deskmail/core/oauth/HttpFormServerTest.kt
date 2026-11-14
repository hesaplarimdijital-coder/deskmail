package com.deskmail.core.oauth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** The form as it travels, to an HTTP server of the test's own on this machine. */
class HttpFormServerTest {

    private lateinit var server: ServerSocket
    private lateinit var serving: Thread

    /** What the server was sent: the request line, the content type, the body. */
    private val received = LinkedBlockingQueue<Triple<String, String, String>>()

    /** What the server answers with. */
    @Volatile
    private var status = 200

    @Volatile
    private var answer = """{"access_token":"a"}"""

    @Before
    fun start() {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                socket.use {
                    val input = it.getInputStream()
                    val head = readHead(input)
                    val lines = head.split("\r\n")
                    val headers = lines.drop(1).filter { line -> ':' in line }.associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = String(input.readNBytes(length), Charsets.UTF_8)
                    received += Triple(lines.first(), headers["content-type"].orEmpty(), body)
                    val bytes = answer.toByteArray(Charsets.UTF_8)
                    val reply = "HTTP/1.1 $status Answer\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(reply.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    @After
    fun stop() {
        server.close()
        serving.join(5_000)
    }

    /** Up to the empty line after the headers. */
    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            val bytes = head.toByteArray()
            if (bytes.size >= 4 && String(bytes, bytes.size - 4, 4, Charsets.US_ASCII) == "\r\n\r\n") break
        }
        return String(head.toByteArray(), Charsets.US_ASCII).trimEnd()
    }

    private val url get() = "http://127.0.0.1:${server.localPort}/token"

    @Test
    fun theFieldsArriveEncoded() {
        val got = HttpForm.post(url, linkedMapOf("grant_type" to "refresh_token", "scope" to "a/b c+d", "refresh_token" to "M.C5&x=y"))

        assertEquals(200, got.status)
        assertEquals("""{"access_token":"a"}""", got.body)
        val (request, type, body) = received.single()
        assertEquals("POST /token HTTP/1.1", request)
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", type)
        val fields = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        assertEquals(mapOf("grant_type" to "refresh_token", "scope" to "a/b c+d", "refresh_token" to "M.C5&x=y"), fields)
    }

    @Test
    fun aRefusalIsReadToo() {
        status = 400
        answer = """{"error":"invalid_grant"}"""

        val got = HttpForm.post(url, mapOf("grant_type" to "refresh_token"))

        assertEquals(400, got.status)
        assertEquals("""{"error":"invalid_grant"}""", got.body)
    }

    @Test
    fun theWholeWayThroughTheTokenClient() {
        answer = """{"token_type":"Bearer","expires_in":3599,"access_token":"EwB-access","refresh_token":"M.C540-next"}"""
        val config = OAuthConfig("http://127.0.0.1/authorize", url, "client", "com.deskmail://oauth2redirect", listOf("offline_access"))

        val tokens = TokenClient(config).refresh("M.C540-old")

        assertEquals("EwB-access", tokens.accessToken)
        assertEquals("M.C540-next", tokens.refreshToken)
        assertEquals("refresh_token", received.single().third.split('&').first { it.startsWith("grant_type=") }.substringAfter('='))
    }
}
