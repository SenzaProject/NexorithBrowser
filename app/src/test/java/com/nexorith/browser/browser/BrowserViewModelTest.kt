package com.nexorith.browser.browser

import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.BuildAppInfoRepository
import com.nexorith.browser.fakes.FakeBookmarkRepository
import com.nexorith.browser.fakes.FakeHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {

    private lateinit var bookmarks: FakeBookmarkRepository
    private lateinit var history: FakeHistoryRepository
    private lateinit var vm: BrowserViewModel
    private var now = 1_000L
    private var privateSupported = true

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bookmarks = FakeBookmarkRepository()
        history = FakeHistoryRepository()
        vm = BrowserViewModel(
            BuildAppInfoRepository(AppInfo("Nexorith Browser", "NexorithLab", "0.0.0")),
            bookmarks,
            history,
            privateBrowsingSupported = { privateSupported },
            clock = { now++ },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val state get() = vm.uiState.value

    @Test
    fun startsWithOneHomeTab() {
        assertEquals(1, state.tabs.size)
        assertTrue(state.activeTab.isHome)
        assertEquals(Screen.BROWSER, state.screen)
    }

    @Test
    fun submitInputLoadsUrlInActiveTab() {
        vm.submitInput("example.com")
        val tab = state.activeTab
        assertEquals("https://example.com", tab.url)
        assertEquals("https://example.com", tab.loadRequest?.url)
        assertTrue(tab.isLoading)
    }

    @Test
    fun blockedInputShowsErrorAndDoesNotLoad() {
        vm.submitInput("javascript:alert(1)")
        val tab = state.activeTab
        assertEquals(ErrorKind.BLOCKED, tab.error?.kind)
        assertNull(tab.loadRequest)
        vm.dismissError()
        assertTrue(state.activeTab.isHome)
    }

    @Test
    fun loadRequestIsConsumedOnce() {
        vm.submitInput("example.com")
        val request = state.activeTab.loadRequest!!
        vm.onLoadRequestConsumed(state.activeTabId, request.nonce)
        assertNull(state.activeTab.loadRequest)
    }

    @Test
    fun newTabIsActivatedAndCloseSelectsNeighbour() {
        val first = state.activeTabId
        vm.newTab()
        val second = state.activeTabId
        vm.newTab("https://example.com")
        val third = state.activeTabId
        assertEquals(3, state.tabs.size)
        assertEquals("https://example.com", state.activeTab.url)

        vm.closeTab(third)
        assertEquals(second, state.activeTabId)
        vm.selectTab(first)
        vm.closeTab(second)
        assertEquals(first, state.activeTabId)
        assertEquals(1, state.tabs.size)
    }

    @Test
    fun closingLastTabLeavesFreshHomeTab() {
        val only = state.activeTabId
        vm.submitInput("example.com")
        vm.closeTab(only)
        assertEquals(1, state.tabs.size)
        assertTrue(state.activeTabId != only)
        assertTrue(state.activeTab.isHome)
    }

    @Test
    fun finishedPageIsRecordedInHistory() {
        val id = state.activeTabId
        vm.onPageStarted(id, "https://example.com/")
        vm.onPageFinished(id, "https://example.com/", "Example")
        assertEquals(1, vm.history.value.size)
        assertEquals("Example", vm.history.value[0].title)
        assertEquals("Example", vm.recentHistory.value[0].title)
        assertEquals(false, state.activeTab.isLoading)
    }

    @Test
    fun failedPageIsNotRecordedInHistory() {
        val id = state.activeTabId
        vm.onPageStarted(id, "https://bad.example/")
        vm.onError(id, ErrorKind.SSL, "https://bad.example/", "expired")
        vm.onPageFinished(id, "https://bad.example/", "")
        assertTrue(vm.history.value.isEmpty())
        assertEquals(ErrorKind.SSL, state.activeTab.error?.kind)
    }

    @Test
    fun newNavigationClearsError() {
        val id = state.activeTabId
        vm.onError(id, ErrorKind.NETWORK, "https://a.com", "offline")
        assertNotNull(state.activeTab.error)
        vm.onPageStarted(id, "https://a.com")
        assertNull(state.activeTab.error)
    }

    @Test
    fun toggleBookmarkAddsAndRemoves() {
        val id = state.activeTabId
        vm.toggleBookmark() // home tab: nothing to bookmark
        assertTrue(vm.bookmarks.value.isEmpty())

        vm.onPageStarted(id, "https://example.com/")
        vm.onTitle(id, "Example")
        vm.toggleBookmark()
        assertEquals(1, vm.bookmarks.value.size)
        assertEquals("Example", vm.bookmarks.value[0].title)
        assertEquals("Example", vm.shortcuts.value[0].title)

        vm.toggleBookmark()
        assertTrue(vm.bookmarks.value.isEmpty())
        assertEquals(BrowserViewModel.DEFAULT_SHORTCUTS, vm.shortcuts.value)
    }

    @Test
    fun historyEntriesCanBeDeletedAndCleared() {
        val id = state.activeTabId
        vm.onPageFinished(id, "https://a.com/", "A")
        vm.onPageFinished(id, "https://b.com/", "B")
        assertEquals(2, vm.history.value.size)
        vm.deleteHistoryEntry(vm.history.value[0].id)
        assertEquals(1, vm.history.value.size)
        vm.clearHistory()
        assertTrue(vm.history.value.isEmpty())
    }

    @Test
    fun openUrlIgnoresNonWebUrls() {
        vm.openUrl("javascript:alert(1)")
        assertTrue(state.activeTab.isHome)
        vm.showScreen(Screen.BOOKMARKS)
        vm.openUrl("https://example.com")
        assertEquals(Screen.BROWSER, state.screen)
        assertEquals("https://example.com", state.activeTab.url)
    }

    // ---- Incognito ----

    @Test
    fun incognitoTabIsPrivateAndActive() {
        vm.newTab(isPrivate = true)
        assertTrue(state.activeTab.isPrivate)
        assertEquals(2, state.tabs.size)
        assertTrue(state.hasPrivateTabs)
        assertEquals(Screen.BROWSER, state.screen)
    }

    @Test
    fun incognitoTabIsNotCreatedWhenUnsupported() {
        privateSupported = false
        assertEquals(false, vm.canUsePrivateTabs)
        vm.newTab(isPrivate = true)
        assertEquals(1, state.tabs.size)
        assertEquals(false, state.hasPrivateTabs)
    }

    @Test
    fun incognitoPagesAreNotRecordedInHistory() {
        vm.newTab(isPrivate = true)
        val id = state.activeTabId
        vm.onPageStarted(id, "https://secret.example/")
        vm.onPageFinished(id, "https://secret.example/", "Secret")
        assertTrue(vm.history.value.isEmpty())
        assertTrue(vm.recentHistory.value.isEmpty())
        assertEquals(false, state.activeTab.isLoading)
    }

    @Test
    fun normalTabsStillRecordHistoryWhileIncognitoIsOpen() {
        val normal = state.activeTabId
        vm.newTab(isPrivate = true)
        vm.onPageFinished(normal, "https://example.com/", "Example")
        assertEquals(1, vm.history.value.size)
    }

    @Test
    fun closeAllPrivateTabsKeepsNormalTabsAndSelectsOne() {
        val normal = state.activeTabId
        vm.newTab(isPrivate = true)
        vm.newTab(isPrivate = true)
        assertEquals(3, state.tabs.size)

        vm.closePrivateTabs()
        assertEquals(listOf(normal), state.tabs.map { it.id })
        assertEquals(normal, state.activeTabId)
        assertEquals(false, state.hasPrivateTabs)
    }

    @Test
    fun closeAllPrivateTabsKeepsActiveNormalTab() {
        vm.newTab(isPrivate = true)
        val normal = state.tabs.first { !it.isPrivate }.id
        vm.selectTab(normal)
        vm.closePrivateTabs()
        assertEquals(normal, state.activeTabId)
    }

    @Test
    fun closingLastTabWhenOnlyIncognitoRemainsLeavesFreshNormalTab() {
        val normal = state.activeTabId
        vm.newTab(isPrivate = true)
        vm.closeTab(normal)
        assertEquals(1, state.tabs.size)
        assertTrue(state.activeTab.isPrivate)

        vm.closeTab(state.activeTabId)
        assertEquals(1, state.tabs.size)
        assertEquals(false, state.activeTab.isPrivate)
        assertTrue(state.activeTab.isHome)
    }

    @Test
    fun closingActiveIncognitoTabPrefersAnotherIncognitoTab() {
        vm.newTab(isPrivate = true)
        val firstPrivate = state.activeTabId
        vm.newTab(isPrivate = true)
        val secondPrivate = state.activeTabId
        vm.closeTab(secondPrivate)
        assertEquals(firstPrivate, state.activeTabId)

        vm.closeTab(firstPrivate)
        assertEquals(false, state.activeTab.isPrivate)
    }

    @Test
    fun closeAllPrivateTabsWithNoneOpenChangesNothing() {
        val before = state
        vm.closePrivateTabs()
        assertEquals(before, state)
    }

    @Test
    fun incognitoPagesCanStillBeBookmarkedExplicitly() {
        vm.newTab(isPrivate = true)
        val id = state.activeTabId
        vm.onPageStarted(id, "https://example.com/")
        vm.onTitle(id, "Example")
        vm.toggleBookmark()
        assertEquals(1, vm.bookmarks.value.size)
    }

    @Test
    fun defaultShortcutsIncludeGoogle() {
        assertEquals("https://www.google.com", BrowserViewModel.DEFAULT_SHORTCUTS.first().url)
        assertTrue(BrowserViewModel.DEFAULT_SHORTCUTS.none { it.url.contains("duckduckgo") })
    }
}
