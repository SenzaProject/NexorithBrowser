package com.nexorith.browser.browser

data class LoadRequest(val url: String, val nonce: Long)

enum class ErrorKind { NETWORK, SSL, CLEARTEXT, BLOCKED, GENERIC }

data class PageError(val kind: ErrorKind, val url: String?, val detail: String = "")

data class TabState(
    val id: Long,
    /** Current page address; null means a new tab showing the Home page. */
    val url: String? = null,
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loadRequest: LoadRequest? = null,
    val error: PageError? = null,
) {
    val isHome: Boolean get() = url == null && error == null
}

enum class Screen { BROWSER, TABS, BOOKMARKS, HISTORY }

data class BrowserUiState(
    val tabs: List<TabState>,
    val activeTabId: Long,
    val screen: Screen = Screen.BROWSER,
) {
    val activeTab: TabState get() = tabs.first { it.id == activeTabId }
}

data class Shortcut(val title: String, val url: String)
