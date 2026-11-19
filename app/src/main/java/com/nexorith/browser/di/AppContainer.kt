package com.nexorith.browser.di

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nexorith.browser.BuildConfig
import com.nexorith.browser.bookmarks.BookmarkRepository
import com.nexorith.browser.bookmarks.SqliteBookmarkRepository
import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.AppInfoRepository
import com.nexorith.browser.data.BuildAppInfoRepository
import com.nexorith.browser.data.NexorithDatabase
import com.nexorith.browser.history.HistoryRepository
import com.nexorith.browser.history.SqliteHistoryRepository
import com.nexorith.browser.downloads.AndroidDownloadStorage
import com.nexorith.browser.downloads.DownloadCenter
import com.nexorith.browser.downloads.DownloadEngine
import com.nexorith.browser.downloads.DownloadService
import com.nexorith.browser.downloads.SqliteDownloadRepository
import com.nexorith.browser.downloads.UrlConnectionTransport
import com.nexorith.browser.incognito.PrivateSession
import com.nexorith.browser.incognito.WebKitPrivateProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Repositories are registered here as features are added.
 * Kept free of annotation processing on purpose to keep CI builds simple and fast.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = NexorithDatabase(appContext)

    val appInfoRepository: AppInfoRepository = BuildAppInfoRepository(
        AppInfo(
            name = "Nexorith Browser",
            developer = "NexorithLab",
            versionName = BuildConfig.VERSION_NAME,
        ),
    )

    val bookmarkRepository: BookmarkRepository = SqliteBookmarkRepository(database)
    val historyRepository: HistoryRepository = SqliteHistoryRepository(database)

    /** Lifetime of private browsing data (separate WebView profile, deleted when private tabs close). */
    val privateSession = PrivateSession(WebKitPrivateProfiles())

    val downloadCenter = DownloadCenter(
        repository = SqliteDownloadRepository(database),
        downloader = DownloadEngine(UrlConnectionTransport()),
        storage = AndroidDownloadStorage(appContext),
        scope = appScope,
    )

    init {
        appScope.launch { downloadCenter.load() }
        // Keep a foreground service running while any download is queued or running.
        appScope.launch(Dispatchers.Main) {
            downloadCenter.items
                .map { items -> items.any { it.isActive } }
                .distinctUntilChanged()
                .collect { active -> if (active) startDownloadService() }
        }
    }

    private fun startDownloadService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed while the app is in the background; the downloads still run while the process lives.
        }
    }
}
