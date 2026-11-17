package com.nexorith.browser.ui.browser

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexorith.browser.R
import com.nexorith.browser.browser.BrowserViewModel
import com.nexorith.browser.browser.ErrorKind
import com.nexorith.browser.browser.PageError
import com.nexorith.browser.browser.Screen
import com.nexorith.browser.browser.TabState
import com.nexorith.browser.browser.UrlResolver
import com.nexorith.browser.browser.WebViewRegistry
import com.nexorith.browser.ui.home.HomeContent
import com.nexorith.browser.ui.theme.NexorithNeonPurple

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, registry: WebViewRegistry) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    val recent by viewModel.recentHistory.collectAsStateWithLifecycle()
    val tab = state.activeTab
    val isBookmarked = tab.url != null && bookmarks.any { it.url == tab.url }

    BackHandler(enabled = tab.canGoBack || state.tabs.size > 1) {
        val webView = registry.peek(tab.id)
        if (webView != null && webView.canGoBack()) webView.goBack() else viewModel.closeTab(tab.id)
    }

    // Perform a pending load once, then clear it so switching tabs never reloads.
    val request = tab.loadRequest
    LaunchedEffect(tab.id, request?.nonce) {
        if (request != null) {
            registry.get(tab.id).loadUrl(request.url)
            viewModel.onLoadRequestConsumed(tab.id, request.nonce)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        AddressBar(
            tab = tab,
            isBookmarked = isBookmarked,
            onSubmit = viewModel::submitInput,
            onToggleBookmark = viewModel::toggleBookmark,
        )
        if (tab.isLoading) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (tab.url != null) {
                key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            val webView = registry.get(tab.id)
                            // A WebView can only have one parent; detach it from a previous holder.
                            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                            webView
                        },
                    )
                }
            }
            if (tab.isHome) {
                HomeContent(
                    shortcuts = shortcuts,
                    recent = recent,
                    versionName = viewModel.appInfo.versionName,
                    onOpen = viewModel::openUrl,
                )
            }
            tab.error?.let { error ->
                ErrorPage(
                    error = error,
                    onRetry = viewModel::retry,
                    onHome = viewModel::dismissError,
                )
            }
        }

        BottomBar(
            tab = tab,
            tabCount = state.tabs.size,
            isBookmarked = isBookmarked,
            onBack = { registry.peek(tab.id)?.goBack() },
            onForward = { registry.peek(tab.id)?.goForward() },
            onReloadOrStop = {
                registry.peek(tab.id)?.let { if (tab.isLoading) it.stopLoading() else it.reload() }
            },
            onTabs = { viewModel.showScreen(Screen.TABS) },
            onNewTab = { viewModel.newTab() },
            onToggleBookmark = viewModel::toggleBookmark,
            onBookmarks = { viewModel.showScreen(Screen.BOOKMARKS) },
            onHistory = { viewModel.showScreen(Screen.HISTORY) },
        )
    }
}

@Composable
private fun AddressBar(
    tab: TabState,
    isBookmarked: Boolean,
    onSubmit: (String) -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var field by remember(tab.id) { mutableStateOf(TextFieldValue(tab.url.orEmpty())) }
    LaunchedEffect(tab.url) {
        if (!focused) field = TextFieldValue(tab.url.orEmpty())
    }
    val shown = if (focused) field else TextFieldValue(UrlResolver.displayUrl(tab.url))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (UrlResolver.isHttps(tab.url)) Icons.Filled.Lock else Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = shown,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            onSubmit(field.text)
                            focusManager.clearFocus()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (state.isFocused && !focused) {
                                val text = tab.url.orEmpty()
                                field = TextFieldValue(text, TextRange(0, text.length))
                            }
                            focused = state.isFocused
                        },
                    decorationBox = { inner ->
                        if (shown.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.address_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
            if (UrlResolver.isWebUrl(tab.url)) {
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(
                            if (isBookmarked) R.string.action_bookmark_remove else R.string.action_bookmark_add,
                        ),
                        tint = if (isBookmarked) {
                            NexorithNeonPurple
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    tab: TabState,
    tabCount: Int,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = tab.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            IconButton(onClick = onForward, enabled = tab.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.action_forward))
            }
            IconButton(onClick = onReloadOrStop, enabled = tab.url != null) {
                if (tab.isLoading) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_stop))
                } else {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.action_reload))
                }
            }
            IconButton(onClick = onTabs) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.action_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_tab)) },
                        onClick = { menuOpen = false; onNewTab() },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (isBookmarked) R.string.action_bookmark_remove else R.string.action_bookmark_add,
                                ),
                            )
                        },
                        enabled = UrlResolver.isWebUrl(tab.url),
                        onClick = { menuOpen = false; onToggleBookmark() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_bookmarks)) },
                        onClick = { menuOpen = false; onBookmarks() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_history)) },
                        onClick = { menuOpen = false; onHistory() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_share)) },
                        enabled = UrlResolver.isWebUrl(tab.url),
                        onClick = {
                            menuOpen = false
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, tab.url)
                                putExtra(Intent.EXTRA_SUBJECT, tab.title)
                            }
                            context.startActivity(
                                Intent.createChooser(send, context.getString(R.string.share_chooser)),
                            )
                        },
                    )
                    HorizontalDivider()
                    listOf(
                        R.string.menu_downloads,
                        R.string.menu_incognito,
                        R.string.menu_adblock,
                        R.string.menu_vpn,
                    ).forEach { label ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            trailingIcon = {
                                Text(
                                    text = stringResource(R.string.label_upcoming),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = dim,
                                )
                            },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPage(error: PageError, onRetry: () -> Unit, onHome: () -> Unit) {
    val (title, message) = when (error.kind) {
        ErrorKind.NETWORK -> R.string.error_title_network to R.string.error_message_network
        ErrorKind.SSL -> R.string.error_title_ssl to R.string.error_message_ssl
        ErrorKind.CLEARTEXT -> R.string.error_title_cleartext to R.string.error_message_cleartext
        ErrorKind.BLOCKED -> R.string.error_title_blocked to R.string.error_message_blocked
        ErrorKind.GENERIC -> R.string.error_title_generic to R.string.error_message_generic
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error.detail.isNotBlank() && error.kind != ErrorKind.BLOCKED) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        if ((error.kind == ErrorKind.NETWORK || error.kind == ErrorKind.SSL || error.kind == ErrorKind.GENERIC) && error.url != null) {
            Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onHome) { Text(stringResource(R.string.error_home)) }
    }
}
