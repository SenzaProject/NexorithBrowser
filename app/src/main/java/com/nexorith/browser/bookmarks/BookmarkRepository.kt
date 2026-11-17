package com.nexorith.browser.bookmarks

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nexorith.browser.data.NexorithDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
)

interface BookmarkRepository {
    /** Newest first. */
    fun observeAll(): Flow<List<Bookmark>>
    suspend fun add(url: String, title: String, timeMillis: Long)
    suspend fun remove(url: String)
}

class SqliteBookmarkRepository(
    private val database: NexorithDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookmarkRepository {

    private val items = MutableStateFlow<List<Bookmark>>(emptyList())

    init {
        CoroutineScope(SupervisorJob() + dispatcher).launch { items.value = query() }
    }

    override fun observeAll(): Flow<List<Bookmark>> = items.asStateFlow()

    override suspend fun add(url: String, title: String, timeMillis: Long) {
        withContext(dispatcher) {
            val values = ContentValues().apply {
                put("url", url)
                put("title", title)
                put("created_at", timeMillis)
            }
            database.writableDatabase.insertWithOnConflict(
                "bookmarks", null, values, SQLiteDatabase.CONFLICT_REPLACE,
            )
            items.value = query()
        }
    }

    override suspend fun remove(url: String) {
        withContext(dispatcher) {
            database.writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
            items.value = query()
        }
    }

    private fun query(): List<Bookmark> {
        val result = ArrayList<Bookmark>()
        database.readableDatabase.query(
            "bookmarks", null, null, null, null, null, "created_at DESC",
        ).use { c ->
            val id = c.getColumnIndexOrThrow("id")
            val url = c.getColumnIndexOrThrow("url")
            val title = c.getColumnIndexOrThrow("title")
            val created = c.getColumnIndexOrThrow("created_at")
            while (c.moveToNext()) {
                result += Bookmark(c.getLong(id), c.getString(url), c.getString(title), c.getLong(created))
            }
        }
        return result
    }
}
