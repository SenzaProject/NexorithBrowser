package com.nexorith.browser

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
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

    private val viewModel: BrowserViewModel by viewModels {
        val container = (application as NexorithApp).container
        BrowserViewModel.factory(
            container.appInfoRepository,
            container.bookmarkRepository,
            container.historyRepository,
        )
    }

    private var pendingPermission by mutableStateOf<SitePermissionRequest?>(null)
    private lateinit var registry: WebViewRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remote debugging only in debug builds.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        registry = WebViewRegistry(this, viewModel, ::onSitePermission)

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
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) registry.destroyAll()
        super.onDestroy()
    }
}
