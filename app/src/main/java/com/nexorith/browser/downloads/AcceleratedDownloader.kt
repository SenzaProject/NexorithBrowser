package com.nexorith.browser.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Download with several connections when the server allows it.
 *
 * It first asks for one byte (`Range: bytes=0-0`). A `206` answer with the total size proves
 * that the server supports range requests; only then is the file split into chunks that
 * parallel connections fetch with their own Range request, and every answer is validated
 * (status 206, Content-Range matching the request). If the server ignores ranges, the work is
 * handed to [single] and the app reports that multi-connection mode is not available.
 *
 * The number of connections grows only while measured throughput grows ([ConcurrencyController]),
 * is capped by [DownloadSpec.maxConnections], and shrinks when the server answers 429/503.
 * Retry-After is honoured. When the server announces a checksum for the whole file, the
 * finished file is verified against it.
 *
 * Servers, accounts and IP addresses may limit speed or connections; this class does not try to
 * get around such limits.
 */
class AcceleratedDownloader(
    private val transport: HttpTransport,
    private val single: Downloader,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val backoff: suspend (millis: Long) -> Unit = { delay(it) },
    private val maxConsecutiveFailures: Int = 5,
    private val progressIntervalMs: Long = 250,
    private val bufferSize: Int = 128 * 1024,
    private val minSegmentedBytes: Long = 4L * 1024 * 1024,
    private val chunkSizeFor: (Long) -> Long = ::defaultChunkSize,
    private val rampHoldMs: Long = 3_000,
) : Downloader {

    override suspend fun download(
        spec: DownloadSpec,
        file: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val info = when (val found = probe(spec)) {
            is ProbeStep.Ok -> found.probe
            is ProbeStep.Fatal -> return@withContext DownloadResult.Failed(found.failure)
            is ProbeStep.Retry -> return@withContext DownloadResult.Failed(found.failure)
        }
        val result = transfer(spec, file, info, onProgress)
        val checksum = info.checksum
        if (result is DownloadResult.Completed && checksum != null) verify(file, checksum) ?: result else result
    }

    // ---- probing ----

    private class Probe(
        val rangesOk: Boolean,
        /** -1 when unknown. */
        val total: Long,
        val validator: String?,
        val name: String?,
        val mime: String?,
        val checksum: Checksum?,
    )

    private sealed interface ProbeStep {
        class Ok(val probe: Probe) : ProbeStep
        class Fatal(val failure: DownloadFailure) : ProbeStep
        class Retry(val failure: DownloadFailure, val afterMs: Long?) : ProbeStep
    }

    private suspend fun probe(spec: DownloadSpec): ProbeStep {
        var failures = 0
        var result: ProbeStep? = null
        while (result == null) {
            coroutineContext.ensureActive()
            when (val step = probeOnce(spec)) {
                is ProbeStep.Retry -> {
                    failures++
                    if (failures > maxConsecutiveFailures) {
                        result = step
                    } else {
                        backoff(step.afterMs ?: backoffMillis(failures))
                    }
                }
                else -> result = step
            }
        }
        return checkNotNull(result)
    }

    private suspend fun probeOnce(spec: DownloadSpec): ProbeStep {
        val headers = requestHeaders(spec)
        headers["Range"] = "bytes=0-0"
        val response = try {
            transport.open(spec.url, headers)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            return ProbeStep.Retry(DownloadFailure(FailureKind.NETWORK), null)
        }
        return guarded<ProbeStep>(response) {
            when (val code = response.code) {
                206 -> {
                    val total = parseContentRange(response.header("Content-Range"))?.total ?: -1L
                    val probe = probeOf(response, rangesOk = true, total = total)
                    drain(response)
                    ProbeStep.Ok(probe)
                }
                200 -> {
                    // The server ignored the range and is sending the whole file: not usable for chunks.
                    val length = response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    val probe = probeOf(response, rangesOk = false, total = length)
                    response.abort() // do not download the body just to find this out
                    ProbeStep.Ok(probe)
                }
                416 -> {
                    // Asking for the first byte failed: only an empty file does that.
                    val total = parseContentRange(response.header("Content-Range"))?.total ?: -1L
                    if (total == 0L) {
                        ProbeStep.Ok(probeOf(response, rangesOk = true, total = 0L))
                    } else {
                        ProbeStep.Fatal(DownloadFailure(FailureKind.HTTP, code))
                    }
                }
                429, 503 -> {
                    val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                    val failure = DownloadFailure(if (code == 429) FailureKind.RATE_LIMITED else FailureKind.HTTP, code)
                    if (wait != null && wait > MAX_RETRY_AFTER_MS) ProbeStep.Fatal(failure) else ProbeStep.Retry(failure, wait)
                }
                in 500..599 -> ProbeStep.Retry(DownloadFailure(FailureKind.HTTP, code), null)
                else -> ProbeStep.Fatal(DownloadFailure(FailureKind.HTTP, code))
            }
        }
    }

    private fun probeOf(response: HttpResponse, rangesOk: Boolean, total: Long): Probe = Probe(
        rangesOk = rangesOk,
        total = total,
        validator = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            ?: response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() },
        name = FileNames.fromContentDisposition(response.header("Content-Disposition")),
        mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        checksum = Checksums.fromHeaders(response.header("Digest"), response.header("Repr-Digest")),
    )

    /** Reads a small body to its end so that the connection can be reused. */
    private fun drain(response: HttpResponse) {
        runCatching {
            val buffer = ByteArray(8 * 1024)
            var left = 64 * 1024
            while (left > 0) {
                val n = response.body.read(buffer)
                if (n < 0) return
                left -= n
            }
        }
    }

    /** Aborts [response] if the calling coroutine is cancelled while [block] is blocked on it. */
    private suspend fun <T> guarded(response: HttpResponse, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching { response.abort() }
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
            runCatching { response.close() }
        }
    }

    private fun requestHeaders(spec: DownloadSpec): MutableMap<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept-Encoding"] = "identity" // keep byte counts and ranges exact
        spec.userAgent?.let { headers["User-Agent"] = it }
        spec.referer?.let { headers["Referer"] = it }
        spec.cookies?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return headers
    }

    // ---- choosing a mode ----

    private suspend fun transfer(
        spec: DownloadSpec,
        file: File,
        probe: Probe,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        // The file on the server is not the one we started: begin again.
        val changed = spec.validator != null && probe.validator != null && spec.validator != probe.validator
        if (changed) file.delete()

        val restored = if (changed) {
            null
        } else {
            spec.segmentState?.let { SegmentMap.decode(it) }
                ?.takeIf { probe.rangesOk && it.total == probe.total && file.exists() && file.length() == probe.total }
        }
        // A saved chunk map that does not fit the file cannot be trusted, and the file is
        // preallocated: its length says nothing about how much of it was received.
        if (spec.segmentState != null && restored == null) file.delete()

        val existing = if (file.exists()) file.length() else 0L
        val useSegments = probe.rangesOk && probe.total >= 0 &&
            (restored != null || (spec.maxConnections > 1 && probe.total >= minSegmentedBytes))
        if (!useSegments) return runSingle(spec, file, probe, onProgress)

        val initial = restored
            ?: if (existing in 1 until probe.total) {
                SegmentMap.withPrefix(probe.total, chunkSizeFor(probe.total), existing)
            } else {
                null
            }
        return when (val result = segmented(spec, file, probe, initial, onProgress)) {
            is SegResult.Done -> DownloadResult.Completed(probe.total)
            is SegResult.Failed -> DownloadResult.Failed(result.failure)
            is SegResult.Fallback -> {
                // The server contradicted itself (changed file, ignored range): use one plain connection.
                file.delete()
                runSingle(spec.copy(segmentState = null), file, probe, onProgress)
            }
        }
    }

    private suspend fun runSingle(
        spec: DownloadSpec,
        file: File,
        probe: Probe,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult =
        single.download(spec.copy(validator = probe.validator ?: spec.validator, segmentState = null), file) { p ->
            onProgress(
                p.copy(
                    acceptsRanges = probe.rangesOk,
                    suggestedName = p.suggestedName ?: probe.name,
                    mimeType = p.mimeType ?: probe.mime,
                    segmentState = null,
                    segmented = false,
                    connections = 1,
                ),
            )
        }

    /** Returns null when the file matches, or the failure to report. */
    private suspend fun verify(file: File, checksum: Checksum): DownloadResult? {
        val context = coroutineContext
        val matches = try {
            Checksums.matches(file, checksum) { context.ensureActive() }
        } catch (e: IOException) {
            return DownloadResult.Failed(DownloadFailure(FailureKind.STORAGE))
        }
        if (matches) return null
        file.delete()
        return DownloadResult.Failed(DownloadFailure(FailureKind.CHECKSUM))
    }

    // ---- several connections ----

    private sealed interface SegResult {
        object Done : SegResult
        class Failed(val failure: DownloadFailure) : SegResult
        object Fallback : SegResult
    }

    private class SegmentFailure(val failure: DownloadFailure) : Exception()

    private class SegmentMismatch(message: String) : Exception(message)

    private class Context(
        val spec: DownloadSpec,
        val map: SegmentMap,
        val controller: ConcurrencyController,
        val channel: FileChannel,
        val headers: Map<String, String>,
        val validator: String?,
    ) {
        val received = AtomicLong(0)
        val active = AtomicInteger(0)
        private val responses = HashSet<HttpResponse>()

        fun track(response: HttpResponse) {
            synchronized(responses) { responses += response }
        }

        fun untrack(response: HttpResponse) {
            synchronized(responses) { responses -= response }
        }

        fun abortAll() {
            val copy = synchronized(responses) { responses.toList() }
            copy.forEach { runCatching { it.abort() } }
        }

        fun writeAt(position: Long, buffer: ByteArray, length: Int) {
            val bytes = ByteBuffer.wrap(buffer, 0, length)
            var at = position
            while (bytes.hasRemaining()) at += channel.write(bytes, at)
        }
    }

    private suspend fun segmented(
        spec: DownloadSpec,
        file: File,
        probe: Probe,
        initial: SegmentMap?,
        onProgress: (DownloadProgress) -> Unit,
    ): SegResult {
        val total = probe.total
        val map = initial ?: SegmentMap(total, chunkSizeFor(total))
        val free = file.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (free < (total - map.totalReceived()) + SPACE_MARGIN_BYTES) {
            return SegResult.Failed(DownloadFailure(FailureKind.STORAGE))
        }
        val raf = try {
            RandomAccessFile(file, "rw").also { if (it.length() != total) it.setLength(total) }
        } catch (e: IOException) {
            return SegResult.Failed(DownloadFailure(FailureKind.STORAGE))
        }
        return raf.use { runSegments(spec, probe, map, it.channel, onProgress) }
    }

    private suspend fun runSegments(
        spec: DownloadSpec,
        probe: Probe,
        map: SegmentMap,
        channel: FileChannel,
        onProgress: (DownloadProgress) -> Unit,
    ): SegResult {
        val maxConnections = spec.maxConnections.coerceIn(1, MAX_CONNECTIONS)
        val began = clock()
        val ctx = Context(
            spec = spec,
            map = map,
            controller = ConcurrencyController(maxConnections, minOf(2, maxConnections), began, rampHoldMs),
            channel = channel,
            headers = requestHeaders(spec),
            validator = probe.validator ?: spec.validator,
        )
        val meter = SpeedMeter()

        fun snapshot(now: Long): DownloadProgress {
            val speed = meter.bytesPerSecond()
            val done = map.totalReceived()
            return DownloadProgress(
                downloadedBytes = done,
                totalBytes = map.total,
                speedBps = speed,
                etaSeconds = SpeedMeter.etaSeconds(map.total - done, speed),
                acceptsRanges = true,
                validator = ctx.validator,
                suggestedName = probe.name,
                mimeType = probe.mime,
                activeMillis = spec.activeMillisBefore + (now - began),
                segmentState = map.encode(),
                connections = maxOf(1, ctx.active.get()),
                segmented = true,
            )
        }

        meter.record(began, 0)
        onProgress(snapshot(began))
        try {
            coroutineScope {
                val finished = AtomicBoolean(false)
                // A blocked read cannot be interrupted by cancellation: closing the connections does.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) ctx.abortAll()
                    }
                }
                val ticker = launch {
                    while (isActive) {
                        delay(progressIntervalMs)
                        val now = clock()
                        meter.record(now, ctx.received.get())
                        ctx.controller.evaluate(now, meter.bytesPerSecond())
                        onProgress(snapshot(now))
                    }
                }
                (0 until maxConnections).map { index -> launch { worker(index, ctx) } }.joinAll()
                finished.set(true)
                ticker.cancel()
                watcher.cancel()
            }
        } catch (e: CancellationException) {
            // Keep the chunk map so that the download can continue where it stopped.
            onProgress(snapshot(clock()))
            throw e
        } catch (e: SegmentFailure) {
            onProgress(snapshot(clock()))
            return SegResult.Failed(e.failure)
        } catch (e: SegmentMismatch) {
            return SegResult.Fallback
        }
        onProgress(snapshot(clock()))
        return if (map.isComplete()) SegResult.Done else SegResult.Failed(DownloadFailure(FailureKind.NETWORK))
    }

    private suspend fun worker(index: Int, ctx: Context) {
        while (true) {
            coroutineContext.ensureActive()
            if (!ctx.map.hasUnclaimed()) return
            if (index >= ctx.controller.target) {
                delay(IDLE_POLL_MS) // this connection is not needed at the moment
                continue
            }
            val chunk = ctx.map.claim() ?: return
            ctx.active.incrementAndGet()
            try {
                downloadChunk(chunk, ctx)
            } finally {
                ctx.active.decrementAndGet()
            }
        }
    }

    private sealed interface ChunkStep {
        object Finished : ChunkStep
        class Retry(
            val failure: DownloadFailure,
            val afterMs: Long?,
            val progressed: Boolean,
            val throttled: Boolean,
        ) : ChunkStep
    }

    private suspend fun downloadChunk(index: Int, ctx: Context) {
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            if (ctx.map.isDone(index)) return
            when (val step = fetchRange(index, ctx)) {
                is ChunkStep.Finished -> return
                is ChunkStep.Retry -> {
                    if (step.throttled) ctx.controller.onThrottled(clock())
                    failures = if (step.progressed) 1 else failures + 1
                    if (failures > maxConsecutiveFailures) throw SegmentFailure(step.failure)
                    backoff(step.afterMs ?: backoffMillis(failures))
                }
            }
        }
    }

    /** Fetches what is still missing of one chunk with a single Range request. */
    private suspend fun fetchRange(index: Int, ctx: Context): ChunkStep {
        val map = ctx.map
        val from = map.start(index) + map.receivedIn(index)
        val to = map.end(index)
        val headers = LinkedHashMap(ctx.headers)
        headers["Range"] = "bytes=$from-$to"
        ctx.validator?.let { headers["If-Range"] = it }

        val response = try {
            transport.open(ctx.spec.url, headers)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            return ChunkStep.Retry(DownloadFailure(FailureKind.NETWORK), null, progressed = false, throttled = false)
        }
        ctx.track(response)
        var progressed = false
        try {
            val code = response.code
            when {
                code == 206 -> Unit
                code == 429 || code == 503 -> {
                    val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                    val failure = DownloadFailure(if (code == 429) FailureKind.RATE_LIMITED else FailureKind.HTTP, code)
                    if (wait != null && wait > MAX_RETRY_AFTER_MS) throw SegmentFailure(failure)
                    return ChunkStep.Retry(failure, wait, progressed = false, throttled = true)
                }
                // The server ignored the range, or the file changed (If-Range): chunks no longer fit.
                code == 200 || code == 416 -> throw SegmentMismatch("HTTP $code to a range request")
                code in 500..599 ->
                    return ChunkStep.Retry(DownloadFailure(FailureKind.HTTP, code), null, progressed = false, throttled = false)
                else -> throw SegmentFailure(DownloadFailure(FailureKind.HTTP, code))
            }
            val range = parseContentRange(response.header("Content-Range"))
            if (range == null || range.start != from || (range.total >= 0 && range.total != map.total)) {
                throw SegmentMismatch("Content-Range does not match the request")
            }

            var remaining = to - from + 1
            var position = from
            val buffer = ByteArray(bufferSize)
            val input = response.body
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = try {
                    input.read(buffer, 0, want)
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    return ChunkStep.Retry(DownloadFailure(FailureKind.NETWORK), null, progressed, throttled = false)
                }
                if (n < 0) {
                    return ChunkStep.Retry(DownloadFailure(FailureKind.NETWORK), null, progressed, throttled = false)
                }
                try {
                    ctx.writeAt(position, buffer, n)
                } catch (e: IOException) {
                    throw SegmentFailure(DownloadFailure(FailureKind.STORAGE))
                }
                position += n
                remaining -= n
                map.add(index, n.toLong())
                ctx.received.addAndGet(n.toLong())
                progressed = true
                coroutineContext.ensureActive()
            }
            // Read the end of the body so that the connection goes back to the pool.
            runCatching { input.read() }
            return ChunkStep.Finished
        } finally {
            ctx.untrack(response)
            runCatching { response.close() }
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 8
        const val IDLE_POLL_MS = 50L
    }
}
