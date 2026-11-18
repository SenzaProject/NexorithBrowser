package com.nexorith.browser.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexorith.browser.R
import com.nexorith.browser.browser.BrowserViewModel
import com.nexorith.browser.browser.Screen
import com.nexorith.browser.browser.TabState
import com.nexorith.browser.browser.UrlResolver
import com.nexorith.browser.ui.components.EmptyState
import com.nexorith.browser.ui.components.IncognitoUnsupportedDialog
import com.nexorith.browser.ui.components.ScreenHeader
import com.nexorith.browser.ui.theme.NexorithIncognitoSurface

@Composable
fun TabsScreen(viewModel: BrowserViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPrivate by remember { mutableStateOf(state.activeTab.isPrivate) }
    var showUnsupported by remember { mutableStateOf(false) }

    val normalTabs = state.tabs.filterNot { it.isPrivate }
    val privateTabs = state.tabs.filter { it.isPrivate }
    val visibleTabs = if (showPrivate) privateTabs else normalTabs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.tabs_title),
            onBack = { viewModel.showScreen(Screen.BROWSER) },
            actions = {
                IconButton(onClick = {
                    when {
                        !showPrivate -> viewModel.newTab()
                        viewModel.canUsePrivateTabs -> viewModel.newTab(isPrivate = true)
                        else -> showUnsupported = true
                    }
                }) {
                    Icon(
                        Icons.Filled.Add,
                        stringResource(if (showPrivate) R.string.menu_new_incognito_tab else R.string.menu_new_tab),
                    )
                }
            },
        )

        TabRow(
            selectedTabIndex = if (showPrivate) 1 else 0,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Tab(
                selected = !showPrivate,
                onClick = { showPrivate = false },
                text = { Text(stringResource(R.string.tabs_normal) + " (${normalTabs.size})") },
            )
            Tab(
                selected = showPrivate,
                onClick = { showPrivate = true },
                text = { Text(stringResource(R.string.menu_incognito) + " (${privateTabs.size})") },
            )
        }

        if (showPrivate && privateTabs.isNotEmpty()) {
            TextButton(
                onClick = viewModel::closePrivateTabs,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp),
            ) {
                Text(stringResource(R.string.incognito_close_all))
            }
        }

        if (visibleTabs.isEmpty()) {
            EmptyState(
                stringResource(if (showPrivate) R.string.incognito_tabs_empty else R.string.tabs_empty),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleTabs, key = { it.id }) { tab ->
                    TabItem(
                        tab = tab,
                        active = tab.id == state.activeTabId,
                        onSelect = { viewModel.selectTab(tab.id) },
                        onClose = { viewModel.closeTab(tab.id) },
                    )
                }
            }
        }
    }

    if (showUnsupported) {
        IncognitoUnsupportedDialog(onDismiss = { showUnsupported = false })
    }
}

@Composable
private fun TabItem(tab: TabState, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (tab.isPrivate) NexorithIncognitoSurface else MaterialTheme.colorScheme.surface,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tab.title.ifBlank {
                        if (tab.url == null) stringResource(R.string.tab_untitled)
                        else UrlResolver.displayUrl(tab.url)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tab.url != null) {
                    Text(
                        text = UrlResolver.displayUrl(tab.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close))
            }
        }
    }
}
