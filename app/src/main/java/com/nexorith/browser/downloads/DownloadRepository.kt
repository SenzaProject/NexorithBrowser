package com.nexorith.browser.downloads

import android.content.ContentValues
import android.database.Cursor
import com.nexorith.browser.data.NexorithDatabase

/** Persistence for download records. Private downloads are never stored. Blocking calls: use off the main thread. */
interface DownloadRepository {
    /** Stores [item] and returns the generated id. */
    fun insert(item: DownloadItem): Long
    fun update(item: DownloadItem)
    fun delete(id: Long)
    /** Newest first. */
    fun all(): List<DownloadItem>
}

class SqliteDownloadRepository(private val database: NexorithDatabase) : DownloadRepository {

    override fun insert(item: DownloadItem): Long =
        database.writableDatabase.insertOrThrow("downloads", null, values(item))

    override fun update(item: DownloadItem) {
        database.writableDatabase.update("downloads", values(item), "id = ?", arrayOf(item.id.toString()))
    }

    override fun delete(id: Long) {
        database.writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString()))
    }

    override fun all(): List<DownloadItem> {
        val result = ArrayList<DownloadItem>()
        database.readableDatabase.query("downloads", null, null, null, null, null, "created_at DESC, id DESC")
            .use { c -> while (c.moveToNext()) result += read(c) }
        return result
    }

    private fun values(item: DownloadItem) = ContentValues().apply {
        put("url", item.url)
        put("file_name", item.fileName)
        put("mime_type", item.mimeType)
        put("status", item.status.name)
        put("total_bytes", item.totalBytes)
        put("downloaded_bytes", item.downloadedBytes)
        if (item.acceptsRanges == null) putNull("accepts_ranges") else put("accepts_ranges", if (item.acceptsRanges) 1 else 0)
        put("validator", item.validator)
        put("saved_uri", item.savedUri)
        put("active_ms", item.activeMillis)
        put("created_at", item.createdAt)
        put("referer", item.referer)
        put("user_agent", item.userAgent)
        put("failure_kind", item.failure?.kind?.name)
        put("failure_code", item.failure?.httpCode ?: 0)
    }

    private fun read(c: Cursor): DownloadItem {
        fun string(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        fun long(name: String): Long = c.getLong(c.getColumnIndexOrThrow(name))
        val kind = string("failure_kind")?.let { name -> FailureKind.values().firstOrNull { it.name == name } }
        val rangesIndex = c.getColumnIndexOrThrow("accepts_ranges")
        return DownloadItem(
            id = long("id"),
            url = string("url").orEmpty(),
            fileName = string("file_name").orEmpty(),
            mimeType = string("mime_type"),
            status = string("status")?.let { name -> DownloadStatus.values().firstOrNull { it.name == name } }
                ?: DownloadStatus.FAILED,
            totalBytes = long("total_bytes"),
            downloadedBytes = long("downloaded_bytes"),
            failure = kind?.let { DownloadFailure(it, long("failure_code").toInt()) },
            acceptsRanges = if (c.isNull(rangesIndex)) null else c.getInt(rangesIndex) == 1,
            validator = string("validator"),
            savedUri = string("saved_uri"),
            activeMillis = long("active_ms"),
            createdAt = long("created_at"),
            referer = string("referer"),
            userAgent = string("user_agent"),
        )
    }
}
