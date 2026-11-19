package com.nexorith.browser.fakes

import com.nexorith.browser.downloads.DownloadItem
import com.nexorith.browser.downloads.DownloadProgress
import com.nexorith.browser.downloads.DownloadRepository
import com.nexorith.browser.downloads.DownloadResult
import com.nexorith.browser.downloads.DownloadSpec
import com.nexorith.browser.downloads.DownloadStorage
import com.nexorith.browser.downloads.Downloader
import com.nexorith.browser.downloads.PublishedFile
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class FakeDownloadRepository : DownloadRepository {
    val rows = LinkedHashMap<Long, DownloadItem>()
    var inserts = 0
    private var nextId = 1L

    override fun insert(item: DownloadItem): Long {
        inserts++
        val id = nextId++
        rows[id] = item.copy(id = id)
        return id
    }

    override fun update(item: DownloadItem) {
        if (rows.containsKey(item.id)) rows[item.id] = item
    }

    override fun delete(id: Long) {
        rows.remove(id)
    }

    override fun all(): List<DownloadItem> = rows.values.sortedByDescending { it.createdAt }
}

class FakeDownloadStorage(private val dir: File) : DownloadStorage {
    val published = LinkedHashMap<String, ByteArray>()
    val deletedPublished = mutableListOf<String>()
    var retainedIds: Set<Long>? = null
    var publishFails = false

    override fun partFile(id: Long): File = File(dir, "$id.part")

    override fun retainParts(keep: Set<Long>) {
        retainedIds = keep
    }

    override fun publish(part: File, fileName: String, mimeType: String?): PublishedFile {
        if (publishFails) throw java.io.IOException("disk full")
        published[fileName] = part.readBytes()
        return PublishedFile("content://fake/$fileName", fileName)
    }

    override fun deletePublished(uri: String) {
        deletedPublished += uri
    }
}

/** A downloader that does nothing until the test completes a call's [Call.gate]. */
class FakeDownloader : Downloader {
    class Call(
        val spec: DownloadSpec,
        val file: File,
        val onProgress: (DownloadProgress) -> Unit,
        val gate: CompletableDeferred<DownloadResult> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun download(spec: DownloadSpec, file: File, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        val call = Call(spec, file, onProgress)
        calls += call
        return call.gate.await()
    }
}
