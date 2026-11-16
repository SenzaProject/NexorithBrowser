package com.nexorith.browser.data

data class AppInfo(
    val name: String,
    val developer: String,
    val versionName: String,
)

interface AppInfoRepository {
    fun getAppInfo(): AppInfo
}

class BuildAppInfoRepository(private val info: AppInfo) : AppInfoRepository {
    override fun getAppInfo(): AppInfo = info
}
