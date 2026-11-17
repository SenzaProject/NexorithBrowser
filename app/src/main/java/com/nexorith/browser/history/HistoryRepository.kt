package com.nexorith.browser.history

import android.content.ContentValues
import android.database.DatabaseUtils
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

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val lastVisit: Long,
    val visitCount: Int,
)

interface HistoryRepository {
    /** Most recently visited first. */
    fun observeAll(): Flow<List<HistoryEntry>>
    suspend fun record(url: String, title: String, timeMillis: Long)
    suspend fun delete(id: Long)
    suspend fun clear()
}

class SqliteHistoryRepository(
    private val database: NexorithDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryRepository {

    private val items = MutableStateFlow<List<HistoryEntry>>(emptyList())

    init {
        CoroutineScope(SupervisorJob() + dispatcher).launch { items.value = query() }
    }

    override fun observeAll(): Flow<List<HistoryEntry>> = items.asStateFlow()

    override suspend fun record(url: String, title: String, timeMillis: Long) {
        withContext(dispatcher) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                // UPSERT is not available on API 26, so update-or-insert explicitly.
                val exists = DatabaseUtils.queryNumEntries(db, "history", "url = ?", arrayOf(url)) > 0
                if (exists) {
                    db.execSQL(
                        "UPDATE history SET title = ?, last_visit = ?, visit_count = visit_count + 1 WHERE url = ?",
                        arrayOf<Any?>(title, timeMillis, url),
                    )
                } else {
                    val values = ContentValues().apply {
                        put("url", url)
                        put("title", title)
                        put("last_visit", timeMillis)
                        put("visit_count", 1)
                    }
                    db.insert("history", null, values)
                }
                db.execSQL(
                    "DELETE FROM history WHERE id NOT IN " +
                        "(SELECT id FROM history ORDER BY last_visit DESC LIMIT $MAX_ENTRIES)",
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            items.value = query()
        }
    }

    override suspend fun delete(id: Long) {
        withContext(dispatcher) {
            database.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
            items.value = query()
        }
    }

    override suspend fun clear() {
        withContext(dispatcher) {
            database.writableDatabase.delete("history", null, null)
            items.value = query()
        }
    }

    private fun query(): List<HistoryEntry> {
        val result = ArrayList<HistoryEntry>()
        database.readableDatabase.query(
            "history", null, null, null, null, null, "last_visit DESC",
        ).use { c ->
            val id = c.getColumnIndexOrThrow("id")
            val url = c.getColumnIndexOrThrow("url")
            val title = c.getColumnIndexOrThrow("title")
            val last = c.getColumnIndexOrThrow("last_visit")
            val count = c.getColumnIndexOrThrow("visit_count")
            while (c.moveToNext()) {
                result += HistoryEntry(
                    c.getLong(id), c.getString(url), c.getString(title), c.getLong(last), c.getInt(count),
                )
            }
        }
        return result
    }

    private companion object {
        const val MAX_ENTRIES = 1000
    }
}
