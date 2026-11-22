package com.nexorith.browser.downloads

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * A small real HTTP server on localhost, built on plain sockets (the JDK's own HttpServer is not
 * visible to Android unit tests). Every request gets its own connection, closed after the reply.
 * The engine is therefore tested over actual sockets, not mocks.
 */
class TestServer(private val handler: (Exchange) -> Unit) : Closeable {

    class Request(val path: String, val headers: Map<String, String>)

    class Exchange(val request: Request, private val socket: Socket) {
        private val out = socket.getOutputStream()
        private val responseHeaders = LinkedHashMap<String, String>()

        val path: String get() = request.path
        val requestHeaders: Map<String, String> get() = request.headers

        fun header(name: String, value: String) {
            responseHeaders[name] = value
        }

        /** Sends the status line and headers. [length] is the body size; -1 means no body. */
        fun sendHeaders(code: Int, length: Long) {
            val builder = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
            for ((name, value) in responseHeaders) builder.append(name).append(": ").append(value).append("\r\n")
            builder.append("Content-Length: ").append(if (length < 0) 0 else length).append("\r\n")
            builder.append("Connection: close\r\n\r\n")
            out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            out.write(bytes, offset, length)
        }

        fun flush() = out.flush()

        fun close() {
            runCatching { out.flush() }
            runCatching { socket.close() }
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            302 -> "Found"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Status"
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool(ThreadFactory { task ->
        Thread(task).apply { isDaemon = true }
    })
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf<Request>())

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        workers.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    return@execute // closed
                }
                workers.execute { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        var exchange: Exchange? = null
        try {
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val path = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
            val request = Request(path, headers)
            requests += request
            exchange = Exchange(request, client)
            handler(exchange)
        } catch (e: IOException) {
            // The client may hang up on purpose.
        } finally {
            exchange?.close() ?: runCatching { client.close() }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdownNow()
    }

    companion object {
        /** Serves [data], honouring "Range: bytes=N-M" or "bytes=N-" (and If-Range) when [ranges] is true. */
        fun serve(
            exchange: Exchange,
            data: ByteArray,
            ranges: Boolean = true,
            etag: String = "\"v1\"",
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            exchange.header("ETag", etag)
            extraHeaders.forEach { (name, value) -> exchange.header(name, value) }
            if (ranges) exchange.header("Accept-Ranges", "bytes")
            val range = exchange.requestHeaders["range"]
            val ifRange = exchange.requestHeaders["if-range"]
            if (ranges && range != null && (ifRange == null || ifRange == etag)) {
                val spec = range.removePrefix("bytes=")
                val start = spec.substringBefore('-').toLong()
                val last = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
                if (start >= data.size) {
                    exchange.header("Content-Range", "bytes */${data.size}")
                    exchange.sendHeaders(416, -1)
                    return
                }
                val body = data.copyOfRange(start.toInt(), last.toInt() + 1)
                exchange.header("Content-Range", "bytes $start-$last/${data.size}")
                exchange.sendHeaders(206, body.size.toLong())
                exchange.write(body)
            } else {
                exchange.sendHeaders(200, data.size.toLong())
                exchange.write(data)
            }
        }
    }
}
