package com.nexorith.browser.downloads

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

enum class FailureKind { NETWORK, HTTP, RATE_LIMITED, STORAGE, UNSUPPORTED_URL }

data class DownloadFailure(val kind: FailureKind, val httpCode: Int = 0)

/** What the browser hands over when a page starts a download. */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    /** -1 or 0 when unknown. */
    val contentLength: Long,
    val referer: String?,
    /** Cookies for [url]. Kept in memory only, never written to disk. */
    val cookies: String?,
    val isPrivate: Boolean,
)

data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** -1 when the server did not say. */
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    /** Measured from bytes actually received; 0 when not running. */
    val speedBps: Long = 0,
    val etaSeconds: Long? = null,
    val failure: DownloadFailure? = null,
    /** null = not known yet, false = the server does not support resuming. */
    val acceptsRanges: Boolean? = null,
    /** ETag or Last-Modified of the file, sent as If-Range when resuming. */
    val validator: String? = null,
    val savedUri: String? = null,
    /** Total time spent transferring, across pauses and retries. */
    val activeMillis: Long = 0,
    val createdAt: Long = 0,
    val referer: String? = null,
    val userAgent: String? = null,
    /** Private downloads live in memory only: no database row, and their partial file is removed at the next start. */
    val isPrivate: Boolean = false,
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED

    /** Progress between 0 and 1, or null when the total size is unknown. */
    val fraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    /** Downloaded bytes divided by the time actually spent transferring. */
    val averageSpeedBps: Long
        get() = if (activeMillis > 0) downloadedBytes * 1000 / activeMillis else 0
}

/** Everything the downloader needs for one (re)start of a download. */
data class DownloadSpec(
    val url: String,
    val userAgent: String?,
    val referer: String?,
    val cookies: String?,
    val validator: String?,
    val activeMillisBefore: Long,
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val etaSeconds: Long?,
    val acceptsRanges: Boolean?,
    val validator: String?,
    /** File name from the server's Content-Disposition header, if any. */
    val suggestedName: String?,
    val mimeType: String?,
    val activeMillis: Long,
)

sealed interface DownloadResult {
    data class Completed(val totalBytes: Long) : DownloadResult
    data class Failed(val failure: DownloadFailure) : DownloadResult
}

interface Downloader {
    /**
     * Downloads [spec] into [file], resuming from the file's current length when the server allows
     * it. Cancelling the calling coroutine stops the transfer and keeps the partial file.
     */
    suspend fun download(spec: DownloadSpec, file: java.io.File, onProgress: (DownloadProgress) -> Unit): DownloadResult
}
