package com.nexorith.browser.downloads

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface HttpResponse : Closeable {
    val code: Int
    val body: InputStream
    fun header(name: String): String?
}

interface HttpTransport {
    /** Sends a GET with [headers]. The caller must close the response. */
    @Throws(IOException::class)
    fun open(url: String, headers: Map<String, String>): HttpResponse
}

/**
 * HttpURLConnection transport. Redirects are followed by hand so that credentials
 * (Cookie, Authorization) are dropped when a redirect leaves the original host, and so that
 * a redirect from HTTPS to HTTP is refused.
 */
class UrlConnectionTransport(
    private val allowCleartext: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override fun open(url: String, headers: Map<String, String>): HttpResponse {
        var current = URL(url)
        var requestHeaders = headers
        for (hop in 0..MAX_REDIRECTS) {
            val scheme = current.protocol.lowercase()
            if (scheme != "https" && !(allowCleartext && scheme == "http")) {
                throw IOException("Blocked non-HTTPS URL")
            }
            val connection = current.openConnection() as HttpURLConnection
            var keep = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                for ((name, value) in requestHeaders) connection.setRequestProperty(name, value)

                val code = connection.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    val next = URL(current, location)
                    if (!next.host.equals(current.host, ignoreCase = true)) {
                        requestHeaders = requestHeaders.filterKeys {
                            !it.equals("Cookie", ignoreCase = true) && !it.equals("Authorization", ignoreCase = true)
                        }
                    }
                    current = next
                    continue
                }
                keep = true
                return ConnectionResponse(connection, code)
            } finally {
                if (!keep) connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private class ConnectionResponse(
        private val connection: HttpURLConnection,
        override val code: Int,
    ) : HttpResponse {
        override val body: InputStream
            get() = if (code >= 400) {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            } else {
                connection.inputStream
            }

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun close() = connection.disconnect()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
