package com.nexorith.browser.downloads

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** A real HTTP server on localhost (JDK built-in), so the engine is tested over actual sockets. */
class TestServer(handler: (HttpExchange) -> Unit) : Closeable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())

    class Request(val path: String, val headers: Map<String, String>)

    init {
        server.createContext("/") { exchange ->
            requests += Request(
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
            )
            try {
                handler(exchange)
            } catch (e: java.io.IOException) {
                // The client may hang up on purpose.
            } finally {
                runCatching { exchange.close() }
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

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
