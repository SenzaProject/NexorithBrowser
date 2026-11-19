package com.nexorith.browser.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexorith.browser.R
import com.nexorith.browser.browser.BrowserViewModel
import com.nexorith.browser.browser.Screen
import com.nexorith.browser.browser.SiteFeature
import com.nexorith.browser.browser.SitePermissionRequest
import com.nexorith.browser.browser.WebViewRegistry
import com.nexorith.browser.downloads.DownloadsViewModel
import com.nexorith.browser.ui.bookmarks.BookmarksScreen
import com.nexorith.browser.ui.browser.BrowserScreen
import com.nexorith.browser.ui.downloads.DownloadsScreen
import com.nexorith.browser.ui.history.HistoryScreen
import com.nexorith.browser.ui.tabs.TabsScreen

@Composable
fun NexorithRoot(
    viewModel: BrowserViewModel,
    downloadsViewModel: DownloadsViewModel,
    registry: WebViewRegistry,
    pendingPermission: SitePermissionRequest?,
    onPermissionHandled: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Free WebViews of closed tabs.
    LaunchedEffect(state.tabs) { registry.retain(state.tabs.map { it.id }.toSet()) }

    BackHandler(enabled = state.screen != Screen.BROWSER) {
        viewModel.showScreen(Screen.BROWSER)
    }

    // Root surface: without it, Text that sets no colour falls back to black on the dark background.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state.screen) {
            Screen.BROWSER -> BrowserScreen(viewModel, registry)
            Screen.TABS -> TabsScreen(viewModel)
            Screen.BOOKMARKS -> BookmarksScreen(viewModel)
            Screen.HISTORY -> HistoryScreen(viewModel)
            Screen.DOWNLOADS -> DownloadsScreen(downloadsViewModel) { viewModel.showScreen(Screen.BROWSER) }
        }
    }

    SitePermissionDialog(pendingPermission, onPermissionHandled)
}

private fun androidPermissions(feature: SiteFeature): List<String> = when (feature) {
    SiteFeature.CAMERA -> listOf(Manifest.permission.CAMERA)
    SiteFeature.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
    SiteFeature.LOCATION -> listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}

@Composable
private fun SitePermissionDialog(request: SitePermissionRequest?, onHandled: () -> Unit) {
    var inFlight by remember { mutableStateOf<SitePermissionRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        inFlight?.let { req ->
            val granted = req.features.filter { feature ->
                androidPermissions(feature).any { result[it] == true }
            }.toSet()
            req.resolve(granted)
        }
        inFlight = null
    }

    if (request == null) return

    val context = LocalContext.current
    val labels = request.features.joinToString(", ") { feature ->
        context.getString(
            when (feature) {
                SiteFeature.CAMERA -> R.string.perm_camera
                SiteFeature.MICROPHONE -> R.string.perm_microphone
                SiteFeature.LOCATION -> R.string.perm_location
            },
        )
    }

    AlertDialog(
        onDismissRequest = {
            request.resolve(emptySet())
            onHandled()
        },
        title = { Text(stringResource(R.string.perm_title)) },
        text = { Text(stringResource(R.string.perm_message, request.origin, labels)) },
        confirmButton = {
            TextButton(onClick = {
                inFlight = request
                onHandled()
                launcher.launch(
                    request.features.flatMap(::androidPermissions).distinct().toTypedArray(),
                )
            }) { Text(stringResource(R.string.perm_allow)) }
        },
        dismissButton = {
            TextButton(onClick = {
                request.resolve(emptySet())
                onHandled()
            }) { Text(stringResource(R.string.perm_deny)) }
        },
    )
}
