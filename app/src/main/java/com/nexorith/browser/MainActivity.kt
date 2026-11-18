package com.nexorith.browser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nexorith.browser.browser.BrowserViewModel
import com.nexorith.browser.browser.SitePermissionRequest
import com.nexorith.browser.browser.WebViewRegistry
import com.nexorith.browser.ui.NexorithRoot
import com.nexorith.browser.ui.theme.NexorithTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as NexorithApp).container

    private val viewModel: BrowserViewModel by viewModels {
        BrowserViewModel.factory(
            container.appInfoRepository,
            container.bookmarkRepository,
            container.historyRepository,
            privateBrowsingSupported = { container.privateSession.isSupported },
        )
    }

    private var pendingPermission by mutableStateOf<SitePermissionRequest?>(null)
    private lateinit var registry: WebViewRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only: force light status/navigation bar icons whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Remote debugging only in debug builds.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        registry = WebViewRegistry(this, viewModel, container.privateSession, ::onSitePermission)

        // Remove private data left behind by a crash or force stop, after the first frame.
        Handler(Looper.getMainLooper()).post { container.privateSession.purgeLeftovers() }

        setContent {
            NexorithTheme {
                NexorithRoot(
                    viewModel = viewModel,
                    registry = registry,
                    pendingPermission = pendingPermission,
                    onPermissionHandled = { pendingPermission = null },
                )
            }
        }
    }

    private fun onSitePermission(request: SitePermissionRequest) {
        // A newer request replaces an unanswered one, which is denied.
        pendingPermission?.resolve(emptySet())
        pendingPermission = request
    }

    override fun onResume() {
        super.onResume()
        registry.resumeAll()
    }

    override fun onPause() {
        registry.pauseAll()
        container.privateSession.retryPending()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) registry.destroyAll()
        super.onDestroy()
    }
}
