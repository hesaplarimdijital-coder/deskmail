package com.deskmail.core.oauth

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** A form posted as OAuth's token endpoints take their requests (application/x-www-form-urlencoded). */
object HttpForm {

    /** What the server answered: its status, and the text that came with it. */
    class Answer(val status: Int, val body: String)

    /**
     * Posts [fields] to [url] and reads what comes back, whatever the status: a token endpoint's
     * no is a JSON text too. It blocks while the server is asked.
     *
     * @throws java.io.IOException when the server could not be reached, or did not answer in time.
     */
    fun post(url: String, fields: Map<String, String>): Answer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val body = encode(fields).toByteArray(Charsets.UTF_8)
        connection.requestMethod = "POST"
        connection.doOutput = true
        // an answer is read where it is given: a token endpoint that sends the app elsewhere is not followed
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "DeskMail")
        connection.setFixedLengthStreamingMode(body.size)
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= HTTP_ERRORS) connection.errorStream else connection.inputStream
            val text = stream?.use { String(it.readNBytes(MAX_BYTES), Charsets.UTF_8) }.orEmpty()
            return Answer(status, text)
        } finally {
            connection.disconnect()
        }
    }

    /** The fields as a form's body: names and values encoded, joined with "&". */
    fun encode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) -> URLEncoder.encode(name, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8) }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val HTTP_ERRORS = 400

    /** More than any token endpoint answers with; what is beyond it is not read. */
    private const val MAX_BYTES = 64 * 1024
}
