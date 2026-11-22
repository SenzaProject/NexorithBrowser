package com.nexorith.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nexorith.browser.browser.BrowserViewModel
import com.nexorith.browser.browser.SitePermissionRequest
import com.nexorith.browser.browser.Screen
import com.nexorith.browser.browser.WebViewRegistry
import com.nexorith.browser.downloads.DownloadRequest
import com.nexorith.browser.downloads.DownloadsViewModel
import com.nexorith.browser.media.BackgroundMedia
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

    private val downloadsViewModel: DownloadsViewModel by viewModels {
        DownloadsViewModel.factory(container.downloadCenter, container.downloadSettings)
    }

    private var pendingPermission by mutableStateOf<SitePermissionRequest?>(null)
    private lateinit var registry: WebViewRegistry

    private var notificationPermissionAsked = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Downloads work without it; go on to the battery question.
            askBatteryOptimizationOnce()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only: force light status/navigation bar icons whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Remote debugging only in debug builds.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        registry = WebViewRegistry(
            this,
            viewModel,
            container.privateSession,
            ::onSitePermission,
            ::onDownloadRequested,
        )

        // Remove private data left behind by a crash or force stop, after the first frame.
        Handler(Looper.getMainLooper()).post { container.privateSession.purgeLeftovers() }

        if (savedInstanceState == null) openDownloadsIfRequested(intent)

        setContent {
            NexorithTheme {
                NexorithRoot(
                    viewModel = viewModel,
                    downloadsViewModel = downloadsViewModel,
                    registry = registry,
                    pendingPermission = pendingPermission,
                    onPermissionHandled = { pendingPermission = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDownloadsIfRequested(intent)
    }

    private fun openDownloadsIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            viewModel.showScreen(Screen.DOWNLOADS)
        }
    }

    private fun onDownloadRequested(request: DownloadRequest) {
        val accepted = container.downloadCenter.enqueue(request)
        Toast.makeText(
            this,
            if (accepted) R.string.download_started else R.string.download_unsupported,
            Toast.LENGTH_SHORT,
        ).show()
        if (accepted) askNotificationPermissionOnce()
    }

    /** Android 13+: ask once, at the first download, so progress can be shown in the notification shade. */
    private fun askNotificationPermissionOnce() {
        if (notificationPermissionAsked) return
        notificationPermissionAsked = true
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOptimizationOnce()
        }
    }

    /**
     * Many phones stop apps that battery saving puts to sleep, even with a foreground service.
     * Asks once, with the system's own dialog, to let downloads keep running in the background.
     */
    @SuppressLint("BatteryLife")
    private fun askBatteryOptimizationOnce() {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("background", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun onSitePermission(request: SitePermissionRequest) {
        // A newer request replaces an unanswered one, which is denied.
        pendingPermission?.resolve(emptySet())
        pendingPermission = request
    }

    override fun onResume() {
        super.onResume()
        BackgroundMedia.stop(this)
        registry.setMediaKeepAlive(false)
        registry.resumeAll()
    }

    override fun onPause() {
        if (BackgroundMedia.isAudioPlaying(this)) {
            // Audio is playing: leave the pages running and keep the app alive in the background.
            registry.setMediaKeepAlive(true)
            BackgroundMedia.start(this)
        } else {
            registry.pauseAll()
        }
        container.privateSession.retryPending()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            BackgroundMedia.stop(this)
            registry.destroyAll()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "com.nexorith.browser.extra.OPEN_DOWNLOADS"
    }
}
