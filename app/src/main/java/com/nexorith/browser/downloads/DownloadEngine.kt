package com.nexorith.browser.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Single-connection downloader with resume.
 *
 * - Resumes from the partial file's length with an HTTP Range request, validating the
 *   response (206 and a matching Content-Range). If the server ignores the range, or the file
 *   changed (If-Range), the download restarts from zero instead of corrupting the file.
 * - Transient failures (dropped connection, 5xx, 429/503) are retried with exponential backoff.
 *   Retry-After is honoured. The retry budget is refilled whenever bytes arrive.
 * - Speed and ETA come only from bytes received and the time that really passed.
 *
 * Multi-connection (segmented) download is not part of this class.
 */
class DownloadEngine(
    private val transport: HttpTransport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val backoff: suspend (millis: Long) -> Unit = { delay(it) },
    private val maxConsecutiveFailures: Int = 5,
    private val bufferSize: Int = 64 * 1024,
    private val progressIntervalMs: Long = 250,
) : Downloader {

    override suspend fun download(
        spec: DownloadSpec,
        file: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val state = AttemptState(spec.validator, spec.activeMillisBefore)
        var failures = 0
        var result: DownloadResult? = null
        while (result == null) {
            coroutineContext.ensureActive()
            when (val outcome = attempt(spec, file, state, onProgress)) {
                is Attempt.Done -> result = DownloadResult.Completed(outcome.total)
                is Attempt.Fatal -> result = DownloadResult.Failed(outcome.failure)
                is Attempt.Retry -> {
                    failures = if (outcome.madeProgress) 0 else failures + 1
                    if (failures > maxConsecutiveFailures) {
                        result = DownloadResult.Failed(outcome.failure)
                    } else {
                        backoff(outcome.retryAfterMs ?: backoffMillis(failures))
                    }
                }
            }
        }
        checkNotNull(result)
    }

    private fun backoffMillis(failures: Int): Long =
        minOf(1_000L shl (failures - 1).coerceIn(0, 10), MAX_BACKOFF_MS)

    private class AttemptState(var validator: String?, var activeMs: Long)

    private sealed interface Attempt {
        data class Done(val total: Long) : Attempt
        data class Fatal(val failure: DownloadFailure) : Attempt
        data class Retry(
            val failure: DownloadFailure,
            val madeProgress: Boolean,
            val retryAfterMs: Long? = null,
        ) : Attempt
    }

    private data class ContentRange(val start: Long, val total: Long)

    private suspend fun attempt(
        spec: DownloadSpec,
        file: File,
        state: AttemptState,
        onProgress: (DownloadProgress) -> Unit,
    ): Attempt {
        val existing = if (file.exists()) file.length() else 0L

        val headers = LinkedHashMap<String, String>()
        headers["Accept-Encoding"] = "identity" // keep byte counts and ranges exact
        spec.userAgent?.let { headers["User-Agent"] = it }
        spec.referer?.let { headers["Referer"] = it }
        spec.cookies?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        if (existing > 0) {
            headers["Range"] = "bytes=$existing-"
            state.validator?.let { headers["If-Range"] = it }
        }

        val response = try {
            transport.open(spec.url, headers)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            return Attempt.Retry(DownloadFailure(FailureKind.NETWORK), madeProgress = false)
        }

        return coroutineScope {
            // A blocked read cannot be interrupted by cancellation. This child is cancelled with its
            // parent and then closes the connection, which makes the read fail at once.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { response.close() }
                }
            }
            try {
                handle(response, spec, file, existing, state, onProgress)
            } finally {
                watcher.cancel()
                runCatching { response.close() }
            }
        }
    }

    private suspend fun handle(
        response: HttpResponse,
        spec: DownloadSpec,
        file: File,
        existing: Long,
        state: AttemptState,
        onProgress: (DownloadProgress) -> Unit,
    ): Attempt {
        val code = response.code
        when {
            code == 200 || code == 206 -> Unit
            code == 416 -> {
                if (existing > 0) {
                    val total = parseContentRange(response.header("Content-Range"))?.total
                    if (total != null && total == existing) return Attempt.Done(existing)
                    truncate(file)
                    return Attempt.Retry(DownloadFailure(FailureKind.HTTP, 416), madeProgress = true, retryAfterMs = 0)
                }
                return Attempt.Fatal(DownloadFailure(FailureKind.HTTP, 416))
            }
            code == 429 || code == 503 -> {
                val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                val failure = DownloadFailure(
                    if (code == 429) FailureKind.RATE_LIMITED else FailureKind.HTTP,
                    code,
                )
                if (wait != null && wait > MAX_RETRY_AFTER_MS) return Attempt.Fatal(failure)
                return Attempt.Retry(failure, madeProgress = false, retryAfterMs = wait)
            }
            code in 500..599 -> return Attempt.Retry(DownloadFailure(FailureKind.HTTP, code), madeProgress = false)
            else -> return Attempt.Fatal(DownloadFailure(FailureKind.HTTP, code))
        }

        val contentLength = response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        var start = existing
        val total: Long
        if (code == 206) {
            val range = parseContentRange(response.header("Content-Range"))
            if (range == null || range.start != existing) {
                // The server answered a different range than the one we asked for: start over.
                truncate(file)
                return Attempt.Retry(DownloadFailure(FailureKind.HTTP, 206), madeProgress = true, retryAfterMs = 0)
            }
            total = if (range.total >= 0) range.total else if (contentLength >= 0) existing + contentLength else -1L
        } else {
            if (existing > 0) {
                // 200 to a ranged request: the server ignored the range or the file changed.
                truncate(file)
                start = 0
            }
            total = contentLength
            state.validator = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
                ?: response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
        }

        val free = file.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (total >= 0 && free < (total - start) + SPACE_MARGIN) {
            return Attempt.Fatal(DownloadFailure(FailureKind.STORAGE))
        }

        val acceptsRanges = code == 206 || response.header("Accept-Ranges")?.trim().equals("bytes", ignoreCase = true)
        val suggestedName = FileNames.fromContentDisposition(response.header("Content-Disposition"))
        val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val meter = SpeedMeter()
        val sessionBase = start
        var downloaded = start
        val began = clock()
        val activeBefore = state.activeMs
        var lastEmit = began
        var receivedAny = false

        fun emit(now: Long) {
            val speed = meter.bytesPerSecond()
            onProgress(
                DownloadProgress(
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    speedBps = speed,
                    etaSeconds = if (total >= 0) SpeedMeter.etaSeconds(total - downloaded, speed) else null,
                    acceptsRanges = acceptsRanges,
                    validator = state.validator,
                    suggestedName = suggestedName,
                    mimeType = mime,
                    activeMillis = activeBefore + (now - began),
                ),
            )
        }

        emit(began)
        meter.record(began, 0)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(bufferSize)
                val input = response.body
                while (true) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        coroutineContext.ensureActive()
                        return Attempt.Retry(DownloadFailure(FailureKind.NETWORK), receivedAny)
                    }
                    if (n < 0) break
                    coroutineContext.ensureActive()
                    try {
                        raf.write(buffer, 0, n)
                    } catch (e: IOException) {
                        return Attempt.Fatal(DownloadFailure(FailureKind.STORAGE))
                    }
                    downloaded += n
                    receivedAny = true
                    val now = clock()
                    meter.record(now, downloaded - sessionBase)
                    if (now - lastEmit >= progressIntervalMs) {
                        lastEmit = now
                        emit(now)
                    }
                }
            }
        } catch (e: IOException) {
            return Attempt.Fatal(DownloadFailure(FailureKind.STORAGE))
        } finally {
            state.activeMs = activeBefore + (clock() - began)
        }

        emit(clock())
        if (total >= 0 && downloaded != total) {
            // Connection closed early, or the file has a different size than announced.
            return Attempt.Retry(DownloadFailure(FailureKind.NETWORK), receivedAny)
        }
        return Attempt.Done(downloaded)
    }

    private fun truncate(file: File) {
        runCatching { RandomAccessFile(file, "rw").use { it.setLength(0) } }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value == null) return null
        // "bytes 100-999/1000", "bytes 100-999/*", "bytes */1000"
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: -1L
        val total = match.groupValues[2].toLongOrNull() ?: -1L
        return ContentRange(start, total)
    }

    private companion object {
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_RETRY_AFTER_MS = 120_000L
        const val SPACE_MARGIN = 8L * 1024 * 1024
        val CONTENT_RANGE = Regex("bytes\\s+(?:(\\d+)-\\d+|\\*)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
