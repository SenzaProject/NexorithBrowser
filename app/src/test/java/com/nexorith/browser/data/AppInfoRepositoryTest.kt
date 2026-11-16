package com.nexorith.browser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoRepositoryTest {

    @Test
    fun returnsConfiguredAppInfo() {
        val info = AppInfo(name = "Nexorith Browser", developer = "NexorithLab", versionName = "0.1.0")
        val repository: AppInfoRepository = BuildAppInfoRepository(info)

        assertEquals(info, repository.getAppInfo())
        assertEquals("NexorithLab", repository.getAppInfo().developer)
    }
}
