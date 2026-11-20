package com.nexorith.browser.downloads

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DownloadEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private val delays = mutableListOf<Long>()
    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Before
    fun setUp() {
        file = File(tmp.root, "1.part")
        delays.clear()
    }

    private fun engine(maxFailures: Int = 5) = DownloadEngine(
        transport = UrlConnectionTransport(allowCleartext = true, readTimeoutMs = 10_000),
        backoff = { millis -> delays += millis },
        maxConsecutiveFailures = maxFailures,
        progressIntervalMs = 0,
    )

    private fun spec(url: String, validator: String? = null, cookies: String? = null) = DownloadSpec(
        url = url, userAgent = "TestAgent", referer = "https://example.com/", cookies = cookies,
        validator = validator, activeMillisBefore = 0,
    )

    private fun fetch(url: String, validator: String? = null, engine: DownloadEngine = engine(), progress: (DownloadProgress) -> Unit = {}) =
        runBlocking { engine.download(spec(url, validator), file, progress) }

    @Test
    fun downloadsTheWholeFileAndReportsRealProgress() {
        TestServer { TestServer.serve(it, data) }.use { server ->
            val seen = mutableListOf<DownloadProgress>()
            val result = fetch(server.baseUrl + "/f.bin", progress = { seen += it })

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertTrue(seen.isNotEmpty())
            assertEquals(data.size.toLong(), seen.last().downloadedBytes)
            assertEquals(data.size.toLong(), seen.last().totalBytes)
            assertEquals(true, seen.last().acceptsRanges)
            assertEquals("\"v1\"", seen.last().validator)
            // Progress never goes backwards.
            assertEquals(seen.map { it.downloadedBytes }.sorted(), seen.map { it.downloadedBytes })
            // The request asked for the raw bytes.
            assertEquals("identity", server.requests.first().headers["accept-encoding"])
            assertEquals("TestAgent", server.requests.first().headers["user-agent"])
        }
    }

    @Test
    fun resumesFromThePartialFileWithARangeRequest() {
        file.writeBytes(data.copyOfRange(0, 100_000))
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", validator = "\"v1\"")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            val request = server.requests.single()
            assertEquals("bytes=100000-", request.headers["range"])
            assertEquals("\"v1\"", request.headers["if-range"])
        }
    }

    @Test
    fun restartsFromZeroWhenTheServerIgnoresRanges() {
        file.writeBytes(ByteArray(50) { 9 }) // stale partial data
        TestServer { TestServer.serve(it, data, ranges = false) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun restartsWhenTheFileChangedOnTheServer() {
        file.writeBytes(data.copyOfRange(0, 1_000))
        val newData = ByteArray(200_000) { (it % 97).toByte() }
        TestServer { TestServer.serve(it, newData, etag = "\"v2\"") }.use { server ->
            // We hold a validator of the old file: If-Range does not match, so the server sends everything.
            val result = fetch(server.baseUrl + "/f.bin", validator = "\"v1\"")

            assertEquals(DownloadResult.Completed(newData.size.toLong()), result)
            assertArrayEquals(newData, file.readBytes())
        }
    }

    @Test
    fun resumesAfterTheConnectionDropsMidTransfer() {
        val requestNumber = AtomicInteger()
        TestServer { exchange ->
            if (requestNumber.incrementAndGet() == 1) {
                // Announce the full size, send half, hang up.
                exchange.sendHeaders(200, data.size.toLong())
                exchange.write(data, 0, 120_000)
                exchange.flush()
                exchange.close()
            } else {
                TestServer.serve(exchange, data)
            }
        }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertEquals(2, server.requests.size)
            assertTrue(server.requests[1].headers["range"]!!.startsWith("bytes="))
            // Bytes arrived before the drop, so the retry did not count against the budget.
            assertEquals(listOf(1_000L), delays)
        }
    }

    @Test
    fun waitsForRetryAfterOn429() {
        val requestNumber = AtomicInteger()
        TestServer { exchange ->
            if (requestNumber.incrementAndGet() == 1) {
                exchange.header("Retry-After", "2")
                exchange.sendHeaders(429, -1)
            } else {
                TestServer.serve(exchange, data)
            }
        }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertEquals(listOf(2_000L), delays)
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun failsInsteadOfWaitingForAVeryLongRetryAfter() {
        TestServer { exchange ->
            exchange.header("Retry-After", "3600")
            exchange.sendHeaders(429, -1)
        }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.RATE_LIMITED, 429)), result)
            assertTrue(delays.isEmpty())
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun clientErrorsAreNotRetried() {
        TestServer { it.sendHeaders(404, -1) }.use { server ->
            val result = fetch(server.baseUrl + "/missing")

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.HTTP, 404)), result)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun serverErrorsAreRetriedWithExponentialBackoffThenGiveUp() {
        TestServer { it.sendHeaders(500, -1) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", engine = engine(maxFailures = 2))

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.HTTP, 500)), result)
            assertEquals(3, server.requests.size)
            assertEquals(listOf(1_000L, 2_000L), delays)
        }
    }

    @Test
    fun aCompleteFileAnswered416IsAccepted() {
        file.writeBytes(data)
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun cookiesAreDroppedWhenARedirectLeavesTheHost() {
        TestServer { TestServer.serve(it, data) }.use { target ->
            TestServer { exchange ->
                exchange.header("Location", "http://localhost:${target.baseUrl.substringAfterLast(':')}/f.bin")
                exchange.sendHeaders(302, -1)
            }.use { origin ->
                val result = runBlocking {
                    engine().download(spec(origin.baseUrl + "/start", cookies = "session=secret"), file) {}
                }

                assertEquals(DownloadResult.Completed(data.size.toLong()), result)
                assertEquals("session=secret", origin.requests.single().headers["cookie"])
                assertNull(target.requests.single().headers["cookie"])
            }
        }
    }

    @Test
    fun cleartextIsRefusedByDefault() {
        assertThrows(IOException::class.java) {
            UrlConnectionTransport().open("http://127.0.0.1:1/file", emptyMap())
        }
    }

    @Test
    fun cancellingStopsTheTransferAndKeepsThePartialFile() {
        TestServer { exchange ->
            exchange.sendHeaders(200, data.size.toLong())
            exchange.write(data, 0, 65_536)
            exchange.flush()
            try {
                Thread.sleep(15_000) // stall: only closing the connection can end the client's read
            } catch (e: InterruptedException) {
                // the test finished and closed the server
            }
        }.use { server ->
            val started = System.nanoTime()
            runBlocking {
                val job = launch { engine().download(spec(server.baseUrl + "/f.bin"), file) {} }
                var waited = 0
                while (file.length() == 0L && waited < 5_000) {
                    delay(25)
                    waited += 25
                }
                assertTrue("no bytes arrived", file.length() > 0)
                job.cancelAndJoin()
            }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took ${tookMs}ms", tookMs < 8_000)
            assertTrue(file.length() in 1 until data.size.toLong())
        }
    }
}
