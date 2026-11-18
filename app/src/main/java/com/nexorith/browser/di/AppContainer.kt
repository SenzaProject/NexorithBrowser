package com.nexorith.browser.di

import android.content.Context
import com.nexorith.browser.BuildConfig
import com.nexorith.browser.bookmarks.BookmarkRepository
import com.nexorith.browser.bookmarks.SqliteBookmarkRepository
import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.AppInfoRepository
import com.nexorith.browser.data.BuildAppInfoRepository
import com.nexorith.browser.data.NexorithDatabase
import com.nexorith.browser.history.HistoryRepository
import com.nexorith.browser.history.SqliteHistoryRepository
import com.nexorith.browser.incognito.PrivateSession
import com.nexorith.browser.incognito.WebKitPrivateProfiles

/**
 * Manual dependency container. Repositories are registered here as features are added.
 * Kept free of annotation processing on purpose to keep CI builds simple and fast.
 */
class AppContainer(context: Context) {

    private val database = NexorithDatabase(context.applicationContext)

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
}
