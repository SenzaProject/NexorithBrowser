package com.nexorith.browser.fakes

import com.nexorith.browser.bookmarks.Bookmark
import com.nexorith.browser.bookmarks.BookmarkRepository
import com.nexorith.browser.history.HistoryEntry
import com.nexorith.browser.history.HistoryRepository
import com.nexorith.browser.incognito.PrivateProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBookmarkRepository : BookmarkRepository {
    val items = MutableStateFlow<List<Bookmark>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<Bookmark>> = items

    override suspend fun add(url: String, title: String, timeMillis: Long) {
        items.value = listOf(Bookmark(nextId++, url, title, timeMillis)) +
            items.value.filterNot { it.url == url }
    }

    override suspend fun remove(url: String) {
        items.value = items.value.filterNot { it.url == url }
    }
}

class FakeHistoryRepository : HistoryRepository {
    val items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<HistoryEntry>> = items

    override suspend fun record(url: String, title: String, timeMillis: Long) {
        val existing = items.value.firstOrNull { it.url == url }
        val entry = HistoryEntry(
            id = existing?.id ?: nextId++,
            url = url,
            title = title,
            lastVisit = timeMillis,
            visitCount = (existing?.visitCount ?: 0) + 1,
        )
        items.value = listOf(entry) + items.value.filterNot { it.url == url }
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        items.value = emptyList()
    }
}

class FakePrivateProfiles(override var isSupported: Boolean = true) : PrivateProfiles {
    val existing = linkedSetOf<String>()
    val deleted = mutableListOf<String>()
    var failDeletes = false

    override fun create(name: String) {
        check(isSupported)
        existing += name
    }

    override fun delete(name: String): Boolean {
        if (failDeletes) return false
        existing -= name
        deleted += name
        return true
    }

    override fun listNames(): List<String> = existing.toList()
}
