package com.nexorith.browser.browser

/** Callbacks from WebView clients into the app state. All calls happen on the main thread. */
interface WebEvents {
    fun onPageStarted(tabId: Long, url: String)
    fun onPageFinished(tabId: Long, url: String, title: String)
    fun onProgress(tabId: Long, progress: Int)
    fun onTitle(tabId: Long, title: String)
    fun onNavState(tabId: Long, url: String?, canGoBack: Boolean, canGoForward: Boolean)
    fun onError(tabId: Long, kind: ErrorKind, url: String?, detail: String)
}
