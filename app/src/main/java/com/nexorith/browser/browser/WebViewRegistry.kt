package com.nexorith.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.content.Intent
import android.net.Uri
import com.nexorith.browser.downloads.DownloadRequest
import com.nexorith.browser.incognito.PrivateSession
import com.nexorith.browser.incognito.bindWebViewToProfile

/**
 * Owns one WebView per tab. The ViewModel never touches WebViews; it only receives [WebEvents].
 * Private tabs get a WebView bound to the private profile of [privateSession]; that profile is
 * deleted when the last private WebView is destroyed.
 * Must be used from the main thread.
 */
class WebViewRegistry(
    private val context: Context,
    private val events: WebEvents,
    private val privateSession: PrivateSession,
    private val onSitePermission: (SitePermissionRequest) -> Unit,
    private val onDownload: (DownloadRequest) -> Unit,
) {
    private val views = LinkedHashMap<Long, WebView>()
    private val privateTabIds = HashSet<Long>()

    fun peek(tabId: Long): WebView? = views[tabId]

    fun get(tabId: Long, isPrivate: Boolean): WebView =
        views[tabId] ?: create(tabId, isPrivate).also { views[tabId] = it }

    /** Destroys WebViews of tabs that no longer exist. */
    fun retain(tabIds: Set<Long>) {
        val stale = views.keys.filter { it !in tabIds }
        stale.forEach { id -> views.remove(id)?.let { destroy(id, it) } }
    }

    fun pauseAll() = views.values.forEach { it.onPause() }

    fun resumeAll() = views.values.forEach { it.onResume() }

    fun destroyAll() {
        views.entries.toList().forEach { (id, view) -> destroy(id, view) }
        views.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(tabId: Long, isPrivate: Boolean): WebView {
        val webView = WebView(context)
        if (isPrivate) bindPrivate(tabId, webView)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.setBackgroundColor(0xFF080812.toInt())

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Least privilege: no local file/content access, no mixed content, no popups.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = NexorithWebViewClient(tabId, events)
        webView.webChromeClient = NexorithChromeClient(tabId, events, onSitePermission)

        // Files the page asks to download are handed to the download manager. Private tabs send no
        // cookies: the cookie jar of the private profile is not reachable through CookieManager.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownload(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    referer = webView.url,
                    cookies = if (isPrivate) null else CookieManager.getInstance().getCookie(url),
                    isPrivate = isPrivate,
                ),
            )
        }
        return webView
    }

    /** Binds the WebView to the private profile before it loads anything; never falls back. */
    private fun bindPrivate(tabId: Long, webView: WebView) {
        val profile = privateSession.acquire()
        try {
            bindWebViewToProfile(webView, profile)
        } catch (t: Throwable) {
            privateSession.release()
            webView.destroy()
            throw t
        }
        privateTabIds += tabId
        // Keep autofill services from saving what is typed into private pages.
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun destroy(tabId: Long, webView: WebView) {
        val isPrivate = privateTabIds.remove(tabId)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        if (isPrivate) {
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
        }
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        if (isPrivate) privateSession.release()
    }
}

private class NexorithWebViewClient(
    private val tabId: Long,
    private val events: WebEvents,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> false
            "mailto", "tel" -> {
                if (request.hasGesture()) openExternally(view, uri)
                true
            }
            // javascript:, file:, content:, intent:, data: and anything else is never loaded.
            else -> true
        }
    }

    private fun openExternally(view: WebView?, uri: Uri) {
        val context = view?.context ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) events.onPageStarted(tabId, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url != null) events.onPageFinished(tabId, url, view?.title.orEmpty())
        if (view != null) events.onNavState(tabId, url, view.canGoBack(), view.canGoForward())
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        if (view != null) events.onNavState(tabId, url, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request == null || !request.isForMainFrame) return
        val description = error?.description?.toString().orEmpty()
        val kind = when {
            description.contains("CLEARTEXT", ignoreCase = true) -> ErrorKind.CLEARTEXT
            error?.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> ErrorKind.SSL
            error?.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                error?.errorCode == WebViewClient.ERROR_CONNECT ||
                error?.errorCode == WebViewClient.ERROR_TIMEOUT ||
                error?.errorCode == WebViewClient.ERROR_IO -> ErrorKind.NETWORK
            else -> ErrorKind.GENERIC
        }
        events.onError(tabId, kind, request.url?.toString(), description)
    }

    /** Certificate errors are never bypassed: the load is cancelled and an error page is shown. */
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
        val detail = when (error?.primaryError) {
            SslError.SSL_EXPIRED -> "certificate expired"
            SslError.SSL_NOTYETVALID -> "certificate not yet valid"
            SslError.SSL_IDMISMATCH -> "certificate name mismatch"
            SslError.SSL_UNTRUSTED -> "certificate not trusted"
            SslError.SSL_DATE_INVALID -> "certificate date invalid"
            else -> "certificate invalid"
        }
        events.onError(tabId, ErrorKind.SSL, error?.url, detail)
    }
}

private class NexorithChromeClient(
    private val tabId: Long,
    private val events: WebEvents,
    private val onSitePermission: (SitePermissionRequest) -> Unit,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        events.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        events.onTitle(tabId, title.orEmpty())
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val features = request.resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SiteFeature.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SiteFeature.MICROPHONE
                else -> null
            }
        }.toSet()
        if (features.isEmpty()) {
            request.deny()
            return
        }
        onSitePermission(
            SitePermissionRequest(request.origin.toString(), features) { granted ->
                val resources = request.resources.filter {
                    (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && SiteFeature.CAMERA in granted) ||
                        (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && SiteFeature.MICROPHONE in granted)
                }
                if (resources.isEmpty()) request.deny() else request.grant(resources.toTypedArray())
            },
        )
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        if (origin == null || callback == null) return
        onSitePermission(
            SitePermissionRequest(origin, setOf(SiteFeature.LOCATION)) { granted ->
                // retain = false: the user is asked every time.
                callback.invoke(origin, SiteFeature.LOCATION in granted, false)
            },
        )
    }
}
