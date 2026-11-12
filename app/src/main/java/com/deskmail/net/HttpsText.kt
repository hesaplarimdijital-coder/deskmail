package com.deskmail.net

import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Reads a small text from the web, over https only: the provider database's answer about a domain. */
object HttpsText {
    private const val TIMEOUT_MS = 6_000
    private const val MAX_BYTES = 256 * 1024

    /**
     * The text at [url], or null when the server has none there (any answer but 200).
     *
     * @throws java.io.IOException when the server could not be reached.
     */
    fun get(url: String): String? {
        val connection = URI(url).toURL().openConnection() as? HttpsURLConnection ?: return null
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "DeskMail")
        connection.setRequestProperty("Accept", "application/xml, text/xml")
        try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            return connection.inputStream.use { String(it.readNBytes(MAX_BYTES), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
