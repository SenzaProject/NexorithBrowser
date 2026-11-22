package com.nexorith.browser.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexorith.browser.bookmarks.Bookmark
import com.nexorith.browser.bookmarks.BookmarkRepository
import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.AppInfoRepository
import com.nexorith.browser.history.HistoryEntry
import com.nexorith.browser.history.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(
    appInfoRepository: AppInfoRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val historyRepository: HistoryRepository,
    /** Asked when an incognito tab is requested; false when private data cannot be isolated. */
    private val privateBrowsingSupported: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel(), WebEvents {

    val appInfo: AppInfo = appInfoRepository.getAppInfo()

    private var nextTabId = 1L
    private var nextNonce = 1L

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<HistoryEntry>> = historyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentHistory: StateFlow<List<HistoryEntry>> = history
        .map { it.take(RECENT_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val shortcuts: StateFlow<List<Shortcut>> = bookmarks
        .map { list ->
            if (list.isEmpty()) DEFAULT_SHORTCUTS
            else list.take(SHORTCUT_LIMIT).map { Shortcut(it.title.ifBlank { it.url }, it.url) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_SHORTCUTS)

    val canUsePrivateTabs: Boolean get() = privateBrowsingSupported()

    private fun initialState(): BrowserUiState {
        val tab = TabState(id = nextTabId++)
        return BrowserUiState(tabs = listOf(tab), activeTabId = tab.id)
    }

    // ---- Navigation ----

    /** Address-bar submit: resolves text to a URL, search query, or a blocked error. */
    fun submitInput(input: String) {
        when (val resolved = UrlResolver.resolve(input)) {
            is ResolvedInput.Navigate -> loadInActiveTab(resolved.url)
            is ResolvedInput.Blocked -> {
                updateTab(_uiState.value.activeTabId) {
                    it.copy(
                        error = PageError(ErrorKind.BLOCKED, null, resolved.scheme),
                        isLoading = false,
                    )
                }
            }
            ResolvedInput.Empty -> Unit
        }
    }

    /** Opens an already-trusted URL (bookmark, history, shortcut) in the active tab. */
    fun openUrl(url: String) {
        if (!UrlResolver.isWebUrl(url)) return
        _uiState.update { it.copy(screen = Screen.BROWSER) }
        loadInActiveTab(url)
    }

    fun retry() {
        val tab = _uiState.value.activeTab
        val target = tab.error?.url ?: tab.url ?: return
        loadInActiveTab(target)
    }

    /** Clears an error that has no page to retry (e.g. blocked address) and returns to Home. */
    fun dismissError() {
        updateTab(_uiState.value.activeTabId) {
            it.copy(error = null, url = null, title = "", isLoading = false, progress = 0)
        }
    }

    fun toggleDesktopMode() {
        updateTab(_uiState.value.activeTabId) { it.copy(isDesktop = !it.isDesktop) }
    }

    fun onLoadRequestConsumed(tabId: Long, nonce: Long) {
        updateTab(tabId) { if (it.loadRequest?.nonce == nonce) it.copy(loadRequest = null) else it }
    }

    private fun loadInActiveTab(url: String) {
        updateTab(_uiState.value.activeTabId) {
            it.copy(
                url = url,
                title = "",
                isLoading = true,
                progress = 0,
                error = null,
                loadRequest = LoadRequest(url, nextNonce++),
            )
        }
    }

    // ---- Tabs ----

    fun newTab(url: String? = null, isPrivate: Boolean = false) {
        if (isPrivate && !privateBrowsingSupported()) return
        val tab = TabState(id = nextTabId++, isPrivate = isPrivate)
        _uiState.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id, screen = Screen.BROWSER) }
        if (url != null) loadInActiveTab(url)
    }

    fun selectTab(id: Long) {
        _uiState.update { s ->
            if (s.tabs.any { it.id == id }) s.copy(activeTabId = id, screen = Screen.BROWSER) else s
        }
    }

    fun closeTab(id: Long) {
        _uiState.update { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            if (index < 0) return@update s
            val closed = s.tabs[index]
            val remaining = s.tabs.filterNot { it.id == id }
            if (remaining.isEmpty()) {
                val fresh = TabState(id = nextTabId++)
                s.copy(tabs = listOf(fresh), activeTabId = fresh.id)
            } else {
                val active = if (s.activeTabId == id) {
                    // Prefer a neighbour of the same kind so closing a tab does not switch mode.
                    val sameKind = remaining.filter { it.isPrivate == closed.isPrivate }
                    val candidates = sameKind.ifEmpty { remaining }
                    val position = s.tabs.take(index).count { it.isPrivate == closed.isPrivate }
                    candidates[minOf(position, candidates.lastIndex)].id
                } else {
                    s.activeTabId
                }
                s.copy(tabs = remaining, activeTabId = active)
            }
        }
    }

    /** Closes every incognito tab. Their WebViews are destroyed and the private profile deleted. */
    fun closePrivateTabs() {
        _uiState.update { s ->
            if (!s.hasPrivateTabs) return@update s
            val remaining = s.tabs.filterNot { it.isPrivate }
            if (remaining.isEmpty()) {
                val fresh = TabState(id = nextTabId++)
                s.copy(tabs = listOf(fresh), activeTabId = fresh.id)
            } else {
                val active = if (remaining.any { it.id == s.activeTabId }) s.activeTabId else remaining.last().id
                s.copy(tabs = remaining, activeTabId = active)
            }
        }
    }

    fun showScreen(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }

    // ---- Bookmarks & history ----

    fun toggleBookmark() {
        val tab = _uiState.value.activeTab
        val url = tab.url?.takeIf { UrlResolver.isWebUrl(it) } ?: return
        viewModelScope.launch {
            if (bookmarks.value.any { it.url == url }) {
                bookmarkRepository.remove(url)
            } else {
                bookmarkRepository.add(url, tab.title.ifBlank { url }, clock())
            }
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch { bookmarkRepository.remove(url) }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch { historyRepository.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clear() }
    }

    // ---- WebEvents ----

    override fun onPageStarted(tabId: Long, url: String) {
        updateTab(tabId) {
            it.copy(
                url = if (UrlResolver.isWebUrl(url)) url else it.url,
                isLoading = true,
                progress = 0,
                error = null,
            )
        }
    }

    override fun onPageFinished(tabId: Long, url: String, title: String) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val finalTitle = title.ifBlank { tab.title }
        updateTab(tabId) { it.copy(isLoading = false, progress = 100, title = finalTitle) }
        // Incognito tabs never write to history.
        if (tab.error == null && !tab.isPrivate && UrlResolver.isWebUrl(url)) {
            viewModelScope.launch {
                historyRepository.record(url, finalTitle.ifBlank { url }, clock())
            }
        }
    }

    override fun onProgress(tabId: Long, progress: Int) {
        updateTab(tabId) { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    override fun onTitle(tabId: Long, title: String) {
        if (title.isBlank()) return
        updateTab(tabId) { it.copy(title = title) }
    }

    override fun onNavState(tabId: Long, url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) {
            it.copy(
                url = if (UrlResolver.isWebUrl(url)) url else it.url,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    override fun onError(tabId: Long, kind: ErrorKind, url: String?, detail: String) {
        updateTab(tabId) {
            it.copy(error = PageError(kind, url ?: it.url, detail), isLoading = false)
        }
    }

    private fun updateTab(id: Long, transform: (TabState) -> TabState) {
        _uiState.update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) transform(it) else it }) }
    }

    companion object {
        private const val RECENT_LIMIT = 5
        private const val SHORTCUT_LIMIT = 8

        val DEFAULT_SHORTCUTS = listOf(
            Shortcut("Google", "https://www.google.com"),
            Shortcut("Wikipedia", "https://www.wikipedia.org"),
            Shortcut("GitHub", "https://github.com"),
            Shortcut("YouTube", "https://www.youtube.com"),
        )

        fun factory(
            appInfoRepository: AppInfoRepository,
            bookmarkRepository: BookmarkRepository,
            historyRepository: HistoryRepository,
            privateBrowsingSupported: () -> Boolean,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BrowserViewModel(
                    appInfoRepository,
                    bookmarkRepository,
                    historyRepository,
                    privateBrowsingSupported,
                )
            }
        }
    }
}
