package com.nexorith.browser.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns every download: the list the UI shows, the queue, and the running transfers.
 *
 * Pause and cancel stop the transfer's coroutine; the partial file stays on disk so that
 * resume continues from the last byte. At most [maxParallel] downloads run at once; the rest
 * wait as QUEUED. Private downloads are kept in memory only.
 *
 * Downloads that were running when the app was closed come back as PAUSED: they are not
 * resumed automatically.
 */
class DownloadCenter(
    private val repository: DownloadRepository,
    private val downloader: Downloader,
    private val storage: DownloadStorage,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxParallel: Int = 3,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Connections per download the user allows (1 to 8). Read each time a download starts. */
    private val connections: () -> Int = { 1 },
) {
    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val cookies = HashMap<Long, String>()
    private val nameLocked = HashSet<Long>()
    private val lastPersist = HashMap<Long, Long>()
    private val privateIds = AtomicLong(-1)

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())

    /** Newest first. */
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    /** Reads saved downloads. Call once at start. */
    suspend fun load() = withContext(ioDispatcher) {
        val saved = repository.all().map { item ->
            if (item.isActive) {
                val paused = item.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSeconds = null)
                repository.update(paused)
                paused
            } else {
                item
            }
        }
        _items.update { current ->
            val known = current.map { it.id }.toSet()
            (current + saved.filter { it.id !in known }).sortedByDescending { it.createdAt }
        }
        storage.retainParts(_items.value.map { it.id }.toSet())
    }

    /** Adds a download and starts it when a slot is free. Returns false for links that are not http(s). */
    fun enqueue(request: DownloadRequest): Boolean {
        if (!isHttp(request.url)) return false
        scope.launch(ioDispatcher) {
            val draft = DownloadItem(
                id = 0,
                url = request.url,
                fileName = FileNames.resolve(request.url, request.contentDisposition, request.mimeType),
                mimeType = request.mimeType?.takeIf { it.isNotBlank() },
                totalBytes = if (request.contentLength > 0) request.contentLength else -1,
                createdAt = clock(),
                referer = request.referer,
                userAgent = request.userAgent,
                isPrivate = request.isPrivate,
            )
            val id = if (request.isPrivate) privateIds.getAndDecrement() else repository.insert(draft)
            synchronized(lock) {
                request.cookies?.takeIf { it.isNotBlank() }?.let { cookies[id] = it }
                if (FileNames.fromContentDisposition(request.contentDisposition) != null) nameLocked += id
            }
            _items.update { listOf(draft.copy(id = id)) + it }
            schedule()
        }
        return true
    }

    fun pause(id: Long) {
        var job: Job? = null
        synchronized(lock) {
            val item = find(id) ?: return
            if (!item.isActive) return
            update(id) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSeconds = null, connections = 0) }
            job = jobs[id]
        }
        if (job != null) job?.cancel() else persistAsync(id)
        schedule()
    }

    fun pauseAll() {
        _items.value.filter { it.isActive }.forEach { pause(it.id) }
    }

    fun cancel(id: Long) {
        var job: Job? = null
        synchronized(lock) {
            val item = find(id) ?: return
            if (item.isFinished) return
            update(id) {
                it.copy(
                    status = DownloadStatus.CANCELED, speedBps = 0, etaSeconds = null,
                    downloadedBytes = 0, segmentState = null, connections = 0, segmented = false,
                )
            }
            job = jobs[id]
        }
        if (job != null) {
            job?.cancel()
        } else {
            scope.launch(ioDispatcher) {
                storage.partFile(id).delete()
                persist(id)
            }
        }
        schedule()
    }

    /** Continues a paused download, or retries a failed or canceled one (a failed one keeps its partial file). */
    fun resume(id: Long) {
        synchronized(lock) {
            val item = find(id) ?: return
            if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.FAILED &&
                item.status != DownloadStatus.CANCELED
            ) return
            update(id) { it.copy(status = DownloadStatus.QUEUED, failure = null) }
        }
        persistAsync(id)
        schedule()
    }

    fun retry(id: Long) = resume(id)

    /** Removes the record. The saved file is deleted too when [deleteFile] is true. */
    fun remove(id: Long, deleteFile: Boolean = false) {
        var item: DownloadItem? = null
        var job: Job? = null
        synchronized(lock) {
            item = find(id)
            job = jobs[id]
            cookies.remove(id)
            nameLocked.remove(id)
            lastPersist.remove(id)
            _items.update { list -> list.filterNot { it.id == id } }
        }
        val removed = item ?: return
        job?.cancel()
        scope.launch(ioDispatcher) {
            if (job == null) storage.partFile(id).delete()
            if (!removed.isPrivate) repository.delete(id)
            if (deleteFile) removed.savedUri?.let { storage.deletePublished(it) }
        }
        schedule()
    }

    fun clearFinished() {
        _items.value.filter { it.isFinished }.forEach { remove(it.id) }
    }

    fun find(id: Long): DownloadItem? = _items.value.firstOrNull { it.id == id }

    // ---- internals ----

    private fun isHttp(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private fun update(id: Long, change: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun schedule() {
        synchronized(lock) {
            val free = maxParallel - _items.value.count { it.status == DownloadStatus.RUNNING }
            if (free <= 0) return
            val waiting = _items.value
                .filter { it.status == DownloadStatus.QUEUED && jobs[it.id] == null }
                .reversed() // oldest first
                .take(free)
            for (item in waiting) start(item)
        }
    }

    private fun start(item: DownloadItem) {
        update(item.id) { it.copy(status = DownloadStatus.RUNNING, failure = null, speedBps = 0, etaSeconds = null, connections = 0) }
        val job = scope.launch(ioDispatcher) { execute(item.id) }
        jobs[item.id] = job
        job.invokeOnCompletion {
            synchronized(lock) { if (jobs[item.id] === job) jobs.remove(item.id) }
            schedule()
        }
    }

    private suspend fun execute(id: Long) {
        val item = find(id) ?: return
        persist(id)
        val part = storage.partFile(id)
        val spec = DownloadSpec(
            url = item.url,
            userAgent = item.userAgent,
            referer = item.referer,
            cookies = synchronized(lock) { cookies[id] },
            validator = item.validator,
            activeMillisBefore = item.activeMillis,
            maxConnections = connections().coerceIn(1, 8),
            segmentState = item.segmentState,
        )
        try {
            when (val result = downloader.download(spec, part) { progress -> onProgress(id, progress) }) {
                is DownloadResult.Completed -> finish(id, part)
                is DownloadResult.Failed -> fail(id, result.failure, part)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onStopped(id, part) }
            throw e
        }
    }

    private fun onProgress(id: Long, p: DownloadProgress) {
        val rename = p.suggestedName?.let { name ->
            synchronized(lock) { if (nameLocked.add(id)) FileNames.fromServerName(name, p.mimeType) else null }
        }
        update(id) { i ->
            when (i.status) {
                DownloadStatus.RUNNING -> i.copy(
                    downloadedBytes = p.downloadedBytes,
                    totalBytes = if (p.totalBytes >= 0) p.totalBytes else i.totalBytes,
                    speedBps = p.speedBps,
                    etaSeconds = p.etaSeconds,
                    acceptsRanges = p.acceptsRanges ?: i.acceptsRanges,
                    validator = p.validator ?: i.validator,
                    activeMillis = p.activeMillis,
                    fileName = rename ?: i.fileName,
                    mimeType = if (i.mimeType.isNullOrBlank() || i.mimeType == OCTET_STREAM) p.mimeType ?: i.mimeType else i.mimeType,
                    segmentState = p.segmentState,
                    connections = p.connections,
                    segmented = p.segmented,
                )
                // A paused download still receives the last progress of the transfer that was
                // stopped: it carries the chunk map that resuming needs. Speed stays zero.
                DownloadStatus.PAUSED -> if (p.segmented) {
                    i.copy(downloadedBytes = p.downloadedBytes, segmentState = p.segmentState, activeMillis = p.activeMillis)
                } else {
                    i
                }
                else -> i
            }
        }
        val now = clock()
        val due = synchronized(lock) {
            val last = lastPersist[id] ?: 0L
            if (now - last >= PERSIST_INTERVAL_MS) {
                lastPersist[id] = now
                true
            } else {
                false
            }
        }
        if (due) persist(id)
    }

    private suspend fun finish(id: Long, part: File) {
        val item = find(id) ?: run { part.delete(); return }
        if (item.status == DownloadStatus.CANCELED) {
            part.delete()
            return
        }
        val size = part.length()
        val published = try {
            storage.publish(part, item.fileName, item.mimeType)
        } catch (e: IOException) {
            fail(id, DownloadFailure(FailureKind.STORAGE), part)
            return
        }
        part.delete()
        update(id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                fileName = published.name,
                savedUri = published.uri,
                totalBytes = size,
                downloadedBytes = size,
                speedBps = 0,
                etaSeconds = null,
                failure = null,
                segmentState = null,
                connections = 0,
                segmented = false,
            )
        }
        persist(id)
    }

    private fun fail(id: Long, failure: DownloadFailure, part: File) {
        update(id) {
            // A pause or cancel that arrived meanwhile wins over the failure.
            if (it.status != DownloadStatus.RUNNING) {
                it
            } else {
                val corrupt = failure.kind == FailureKind.CHECKSUM
                it.copy(
                    status = DownloadStatus.FAILED,
                    failure = failure,
                    // A multi-connection file is preallocated: its length is not the progress.
                    downloadedBytes = when {
                        corrupt || !part.exists() -> 0
                        it.segmentState != null -> it.downloadedBytes
                        else -> part.length()
                    },
                    segmentState = if (corrupt) null else it.segmentState,
                    validator = if (corrupt) null else it.validator,
                    speedBps = 0,
                    etaSeconds = null,
                    connections = 0,
                    segmented = false,
                )
            }
        }
        persist(id)
    }

    /** Runs after a transfer was stopped by pause, cancel or remove; the new status is already set. */
    private fun onStopped(id: Long, part: File) {
        val item = find(id)
        when {
            item == null -> part.delete() // removed
            item.status == DownloadStatus.CANCELED -> {
                part.delete()
                persist(id)
            }
            else -> {
                update(id) {
                    // A multi-connection file is preallocated: keep the progress reported by the transfer.
                    it.copy(downloadedBytes = if (it.segmentState != null) it.downloadedBytes else if (part.exists()) part.length() else 0)
                }
                persist(id)
            }
        }
    }

    private fun persist(id: Long) {
        val item = find(id) ?: return
        if (!item.isPrivate) repository.update(item)
    }

    private fun persistAsync(id: Long) {
        scope.launch(ioDispatcher) { persist(id) }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 2_000L
        const val OCTET_STREAM = "application/octet-stream"
    }
}
