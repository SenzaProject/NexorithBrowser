package com.nexorith.browser.di

import com.nexorith.browser.BuildConfig
import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.AppInfoRepository
import com.nexorith.browser.data.BuildAppInfoRepository

/**
 * Manual dependency container. Repositories are registered here as features are added.
 * Kept dependency-free on purpose (no annotation processing) to keep CI builds simple and fast.
 */
class AppContainer {

    val appInfoRepository: AppInfoRepository = BuildAppInfoRepository(
        AppInfo(
            name = "Nexorith Browser",
            developer = "NexorithLab",
            versionName = BuildConfig.VERSION_NAME,
        ),
    )
}
