package com.nexorith.browser.downloads

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** Case-insensitive, multi-valued HTTP header map (minimal stand-in for the JDK's com.sun Headers). */
class Headers {
    private val map = LinkedHashMap<String, MutableList<String>>()

    val entries: Set<Map.Entry<String, List<String>>>
        get() = synchronized(map) { map.entries.map { java.util.AbstractMap.SimpleEntry(it.key, it.value.toList()) }.toSet() }

    fun add(name: String, value: String) {
        synchronized(map) {
            val key = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
            map.getOrPut(key) { mutableListOf() }.add(value)
        }
    }

    fun getFirst(name: String): String? = synchronized(map) {
        map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }
}

/** One request/response pair on a [TestServer] connection. */
class HttpExchange internal constructor(
    private val socket: Socket,
    val requestURI: URI,
    val requestHeaders: Headers,
) {
    val responseHeaders = Headers()
    val responseBody: OutputStream get() = socket.getOutputStream()

    @Volatile private var closed = false

    /** [length] of -1 means "no body"; any other value is sent as Content-Length. */
    fun sendResponseHeaders(code: Int, length: Long) {
        val out = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        responseHeaders.entries.forEach { (name, values) ->
            values.forEach { head.append(name).append(": ").append(it).append("\r\n") }
        }
        head.append("Content-Length: ").append(if (length < 0) 0 else length).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.getOutputStream().flush() }
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

/** A real HTTP server on localhost (plain sockets), so the engine is tested over actual connections. */
class TestServer(private val handler: (HttpExchange) -> Unit) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }
    private val connections = CopyOnWriteArrayList<Socket>()
    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())

    class Request(val path: String, val headers: Map<String, String>)

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                connections += socket
                Thread({ handle(socket) }, "test-server-conn").apply { isDaemon = true }.start()
            }
        }, "test-server-accept").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        var exchange: HttpExchange? = null
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val target = requestLine.split(' ').getOrNull(1) ?: return
            val headers = Headers()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
            val uri = URI.create(target)
            exchange = HttpExchange(socket, uri, headers)
            requests += Request(
                uri.path,
                headers.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
            )
            handler(exchange)
        } catch (e: IOException) {
            // The client may hang up on purpose.
        } finally {
            exchange?.close() ?: runCatching { socket.close() }
            connections.remove(socket)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    override fun close() {
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
    }

    companion object {
        /** Serves [data], honouring a "Range: bytes=N-" header when [ranges] is true. */
        fun serve(exchange: HttpExchange, data: ByteArray, ranges: Boolean = true, etag: String = "\"v1\"") {
            exchange.responseHeaders.add("ETag", etag)
            if (ranges) exchange.responseHeaders.add("Accept-Ranges", "bytes")
            val range = exchange.requestHeaders.getFirst("Range")
            val ifRange = exchange.requestHeaders.getFirst("If-Range")
            val rangeApplies = ranges && range != null && (ifRange == null || ifRange == etag)
            if (rangeApplies) {
                val start = range!!.removePrefix("bytes=").substringBefore('-').toLong()
                if (start >= data.size) {
                    exchange.responseHeaders.add("Content-Range", "bytes */${data.size}")
                    exchange.sendResponseHeaders(416, -1)
                    return
                }
                val body = data.copyOfRange(start.toInt(), data.size)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
                exchange.responseBody.write(body)
            } else {
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.write(data)
            }
        }
    }
}
