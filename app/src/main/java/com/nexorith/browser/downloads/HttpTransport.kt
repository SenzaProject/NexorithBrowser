package com.nexorith.browser.downloads

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

interface HttpResponse : Closeable {
    val code: Int
    val body: InputStream
    fun header(name: String): String?

    /** Stops the transfer at once, even while another thread is blocked reading [body]. */
    fun abort()

    /**
     * Releases the response. When the body was read to its end the connection goes back to the
     * pool and is reused by the next request; otherwise it is closed.
     */
    override fun close()
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
        @Volatile
        private var reachedEnd = false

        private val raw: InputStream by lazy {
            if (code >= 400) connection.errorStream ?: ByteArrayInputStream(ByteArray(0)) else connection.inputStream
        }
        private val tracked: InputStream by lazy { EndTrackingStream(raw) { reachedEnd = true } }
        private val abortable: AbortableStream by lazy { AbortableStream(tracked) }

        override val body: InputStream get() = abortable

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun abort() {
            // HttpURLConnection.disconnect() can wait for a read that is blocked on the same
            // stream, so the blocked reader is released first and the disconnect runs elsewhere.
            runCatching { abortable.abort() }
            disconnectInBackground()
        }

        override fun close() {
            // Closing a fully read stream keeps the connection reusable; disconnect() would not.
            if (reachedEnd) {
                runCatching { raw.close() }
            } else {
                runCatching { abortable.abort() }
                disconnectInBackground()
            }
        }

        private fun disconnectInBackground() {
            READERS.execute { runCatching { connection.disconnect() } }
        }
    }

    /**
     * Runs each read on a helper thread so that [abort] can release the caller at once, even when
     * the underlying socket read does not react to disconnect() (the JDK's implementation).
     */
    private class AbortableStream(private val delegate: InputStream) : InputStream() {
        @Volatile
        private var aborted = false

        @Volatile
        private var pending: CompletableFuture<Int>? = null

        fun abort() {
            aborted = true
            pending?.completeExceptionally(IOException("Aborted"))
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (aborted) throw IOException("Aborted")
            val future = CompletableFuture<Int>()
            pending = future
            READERS.execute {
                try {
                    future.complete(delegate.read(b, off, len))
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
            if (aborted) future.completeExceptionally(IOException("Aborted"))
            try {
                return future.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while reading")
            } finally {
                pending = null
            }
        }

        // The connection is closed through the response, which never blocks.
        override fun close() = Unit
    }

    private class EndTrackingStream(delegate: InputStream, private val onEnd: () -> Unit) : FilterInputStream(delegate) {
        override fun read(): Int {
            val b = super.read()
            if (b < 0) onEnd()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) onEnd()
            return n
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5

        val READERS = Executors.newCachedThreadPool(
            ThreadFactory { task -> Thread(task, "nexorith-http-read").apply { isDaemon = true } },
        )
    }
}
