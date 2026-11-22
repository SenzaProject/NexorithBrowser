package com.nexorith.browser.downloads

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Runs the multi-connection downloader against a real HTTP server on localhost. */
class AcceleratedDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private val delays: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val chunk = 20_000L // 15 chunks

    @Before
    fun setUp() {
        file = File(tmp.root, "1.part")
        delays.clear()
    }

    private fun downloader(maxFailures: Int = 5, minSegmented: Long = 1_000): AcceleratedDownloader {
        val transport = UrlConnectionTransport(allowCleartext = true, readTimeoutMs = 10_000)
        return AcceleratedDownloader(
            transport = transport,
            single = DownloadEngine(transport, backoff = { millis -> delays += millis }, progressIntervalMs = 0),
            backoff = { millis -> delays += millis },
            maxConsecutiveFailures = maxFailures,
            progressIntervalMs = 10,
            bufferSize = 16 * 1024,
            minSegmentedBytes = minSegmented,
            chunkSizeFor = { chunk },
            rampHoldMs = 0,
        )
    }

    private fun spec(url: String, connections: Int = 4, state: String? = null, validator: String? = null) = DownloadSpec(
        url = url, userAgent = "TestAgent", referer = null, cookies = null, validator = validator,
        activeMillisBefore = 0, maxConnections = connections, segmentState = state,
    )

    private fun fetch(
        url: String,
        connections: Int = 4,
        state: String? = null,
        validator: String? = null,
        using: AcceleratedDownloader = downloader(),
        progress: (DownloadProgress) -> Unit = {},
    ) = runBlocking { using.download(spec(url, connections, state, validator), file, progress) }

    private fun rangeOf(request: TestServer.Request): Pair<Long, Long>? {
        val text = request.headers["range"]?.removePrefix("bytes=") ?: return null
        val from = text.substringBefore('-').toLongOrNull() ?: return null
        val to = text.substringAfter('-').toLongOrNull() ?: return null
        return from to to
    }

    private fun sha256Header(bytes: ByteArray) =
        "sha-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun fetchesChunksOverSeveralConnectionsAndAssemblesTheFile() {
        val concurrent = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        TestServer { exchange ->
            val now = concurrent.incrementAndGet()
            mostAtOnce.updateAndGet { maxOf(it, now) }
            try {
                Thread.sleep(25)
                TestServer.serve(exchange, data)
            } finally {
                concurrent.decrementAndGet()
            }
        }.use { server ->
            val seen = mutableListOf<DownloadProgress>()
            val result = fetch(server.baseUrl + "/f.bin", progress = { seen += it })

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertTrue("connections used at once: ${mostAtOnce.get()}", mostAtOnce.get() >= 2)

            val ranges = server.requests.map { it.headers["range"] }
            assertEquals("bytes=0-0", ranges.first()) // the probe
            assertTrue(ranges.drop(1).all { it != null && it.matches(Regex("bytes=\\d+-\\d+")) })
            // Every chunk was requested once; together they cover the file exactly.
            val covered = server.requests.drop(1).mapNotNull { rangeOf(it) }.sumOf { (a, b) -> b - a + 1 }
            assertEquals(data.size.toLong(), covered)

            val last = seen.last()
            assertTrue(last.segmented)
            assertEquals(true, last.acceptsRanges)
            assertEquals(data.size.toLong(), last.downloadedBytes)
            assertNotNull(last.segmentState)
            assertEquals(seen.map { it.downloadedBytes }.sorted(), seen.map { it.downloadedBytes })
        }
    }

    @Test
    fun aSettingOfOneConnectionUsesTheSingleConnectionEngine() {
        TestServer { TestServer.serve(it, data) }.use { server ->
            val seen = mutableListOf<DownloadProgress>()
            val result = fetch(server.baseUrl + "/f.bin", connections = 1, progress = { seen += it })

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertEquals(2, server.requests.size) // the probe and one plain GET
            assertEquals(null, server.requests[1].headers["range"])
            assertTrue(seen.none { it.segmented })
            assertEquals(true, seen.last().acceptsRanges)
        }
    }

    @Test
    fun smallFilesUseOneConnection() {
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", using = downloader(minSegmented = 1_000_000))

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun aServerWithoutRangeSupportIsReportedAndUsedWithOneConnection() {
        TestServer { TestServer.serve(it, data, ranges = false) }.use { server ->
            val seen = mutableListOf<DownloadProgress>()
            val result = fetch(server.baseUrl + "/f.bin", progress = { seen += it })

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertTrue(seen.isNotEmpty())
            assertTrue("every report says ranges are not supported", seen.all { it.acceptsRanges == false })
            assertTrue(seen.none { it.segmented })
        }
    }

    @Test
    fun resumesFromTheSavedChunkMapAndRequestsOnlyWhatIsMissing() {
        // First run: slow answers, stopped part-way.
        var savedState: String? = null
        TestServer { exchange ->
            Thread.sleep(60)
            TestServer.serve(exchange, data)
        }.use { server ->
            runBlocking {
                val latest = java.util.concurrent.atomic.AtomicReference<DownloadProgress?>()
                val job = launch { downloader().download(spec(server.baseUrl + "/f.bin"), file) { latest.set(it) } }
                var waited = 0
                while ((latest.get()?.downloadedBytes ?: 0L) < 40_000L && waited < 8_000) {
                    delay(20)
                    waited += 20
                }
                assertTrue("download did not start", (latest.get()?.downloadedBytes ?: 0L) >= 40_000L)
                job.cancelAndJoin()
                savedState = latest.get()?.segmentState // the report sent when the transfer was stopped
            }
        }
        val state = savedState
        assertNotNull(state)
        val map = SegmentMap.decode(state!!)!!
        val already = map.totalReceived()
        assertTrue(already in 1 until data.size.toLong())

        // Second run: continues from the saved map.
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", state = state)

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            val requested = server.requests.drop(1).mapNotNull { rangeOf(it) }.sumOf { (a, b) -> b - a + 1 }
            assertEquals(data.size.toLong() - already, requested)
        }
    }

    @Test
    fun anAnswerThatContradictsTheRangeMakesTheDownloadFallBackToOneConnection() {
        TestServer { exchange ->
            val range = exchange.requestHeaders["range"]
            if (range != null && range != "bytes=0-0") {
                // Pretend the file changed: a full answer to a ranged request.
                exchange.sendHeaders(200, data.size.toLong())
                exchange.write(data)
            } else {
                TestServer.serve(exchange, data)
            }
        }.use { server ->
            val seen = mutableListOf<DownloadProgress>()
            val result = fetch(server.baseUrl + "/f.bin", progress = { seen += it })

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertFalse(seen.last().segmented)
        }
    }

    @Test
    fun waitsForRetryAfterWhenTheServerThrottlesChunks() {
        val chunkRequests = AtomicInteger()
        TestServer { exchange ->
            val range = exchange.requestHeaders["range"]
            if (range != null && range != "bytes=0-0" && chunkRequests.incrementAndGet() <= 2) {
                exchange.header("Retry-After", "1")
                exchange.sendHeaders(429, -1)
            } else {
                TestServer.serve(exchange, data)
            }
        }.use {
            val result = fetch(it.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            assertTrue("waits: $delays", delays.count { d -> d == 1_000L } >= 1)
        }
    }

    @Test
    fun failsWhenTheServerAsksForAVeryLongWait() {
        TestServer { exchange ->
            val range = exchange.requestHeaders["range"]
            if (range != null && range != "bytes=0-0") {
                exchange.header("Retry-After", "3600")
                exchange.sendHeaders(429, -1)
            } else {
                TestServer.serve(exchange, data)
            }
        }.use {
            val result = fetch(it.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.RATE_LIMITED, 429)), result)
        }
    }

    @Test
    fun aMatchingChecksumIsAccepted() {
        TestServer { TestServer.serve(it, data, extraHeaders = mapOf("Digest" to sha256Header(data))) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun aChecksumMismatchFailsTheDownloadAndRemovesTheFile() {
        val wrong = sha256Header("something else".toByteArray())
        TestServer { TestServer.serve(it, data, extraHeaders = mapOf("Digest" to wrong)) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin")

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.CHECKSUM)), result)
            assertFalse(file.exists())
        }
    }

    @Test
    fun aChecksumIsAlsoCheckedForSingleConnectionDownloads() {
        val wrong = sha256Header("something else".toByteArray())
        TestServer { TestServer.serve(it, data, extraHeaders = mapOf("Digest" to wrong)) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", connections = 1)

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.CHECKSUM)), result)
        }
    }

    @Test
    fun aChangedFileOnTheServerDiscardsOldPartialData() {
        file.writeBytes(ByteArray(5_000) { 9 }) // leftovers of an older version
        TestServer { TestServer.serve(it, data, etag = "\"v2\"") }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", validator = "\"v1\"")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun continuesASingleConnectionPartialFileWithSeveralConnections() {
        file.writeBytes(data.copyOfRange(0, 50_000)) // started earlier with one connection
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", validator = "\"v1\"")

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
            val requested = server.requests.drop(1).mapNotNull { rangeOf(it) }.sumOf { (a, b) -> b - a + 1 }
            assertEquals(data.size.toLong() - 50_000, requested)
        }
    }

    @Test
    fun aSavedChunkMapThatDoesNotFitTheFileIsNotTrusted() {
        // The map claims chunks are done, but the file does not even have the right size.
        file.writeBytes(ByteArray(10))
        val bogus = "1|${data.size}|$chunk|0-13"
        TestServer { TestServer.serve(it, data) }.use { server ->
            val result = fetch(server.baseUrl + "/f.bin", state = bogus)

            assertEquals(DownloadResult.Completed(data.size.toLong()), result)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun serverErrorsOnChunksAreRetriedThenTheDownloadFails() {
        TestServer { exchange ->
            val range = exchange.requestHeaders["range"]
            if (range != null && range != "bytes=0-0") exchange.sendHeaders(500, -1) else TestServer.serve(exchange, data)
        }.use {
            val result = fetch(it.baseUrl + "/f.bin", using = downloader(maxFailures = 1))

            assertEquals(DownloadResult.Failed(DownloadFailure(FailureKind.HTTP, 500)), result)
        }
    }

    @Test
    fun cancellingStopsStalledConnectionsAndKeepsTheChunkMap() {
        TestServer { exchange ->
            val range = exchange.requestHeaders["range"]
            if (range == "bytes=0-0") {
                TestServer.serve(exchange, data)
            } else {
                exchange.header("Content-Range", "bytes ${range!!.removePrefix("bytes=").substringBefore('-')}-${data.size - 1}/${data.size}")
                exchange.sendHeaders(206, chunk)
                exchange.write(data, 0, 8_000)
                exchange.flush()
                try {
                    Thread.sleep(15_000) // stalled: only closing the connection can end the read
                } catch (e: InterruptedException) {
                    // the test is over
                }
            }
        }.use { server ->
            val latest = java.util.concurrent.atomic.AtomicReference<DownloadProgress?>()
            val started = System.nanoTime()
            runBlocking {
                val job = launch { downloader().download(spec(server.baseUrl + "/f.bin"), file) { latest.set(it) } }
                var waited = 0
                while ((latest.get()?.downloadedBytes ?: 0L) == 0L && waited < 6_000) {
                    delay(20)
                    waited += 20
                }
                assertTrue("no bytes arrived", (latest.get()?.downloadedBytes ?: 0L) > 0L)
                job.cancelAndJoin()
            }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took ${tookMs}ms", tookMs < 9_000)
            assertNotNull(latest.get()?.segmentState)
        }
    }
}
