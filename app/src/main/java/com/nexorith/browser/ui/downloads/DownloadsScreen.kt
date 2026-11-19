package com.nexorith.browser.ui.downloads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexorith.browser.R
import com.nexorith.browser.downloads.DownloadFailure
import com.nexorith.browser.downloads.DownloadItem
import com.nexorith.browser.downloads.DownloadStatus
import com.nexorith.browser.downloads.DownloadsViewModel
import com.nexorith.browser.downloads.FailureKind
import com.nexorith.browser.downloads.Formatters
import com.nexorith.browser.ui.components.EmptyState
import com.nexorith.browser.ui.components.ScreenHeader

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit) {
    val list by viewModel.items.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.downloads_title),
            onBack = onBack,
            actions = {
                if (list.any { it.isFinished }) {
                    TextButton(onClick = viewModel::clearFinished) {
                        Text(stringResource(R.string.downloads_clear))
                    }
                }
            },
        )
        if (list.isEmpty()) {
            EmptyState(stringResource(R.string.downloads_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { item -> DownloadRow(item, viewModel) }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, viewModel: DownloadsViewModel) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.isPrivate) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = stringResource(R.string.incognito_title),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { viewModel.remove(item.id, false) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.downloads_remove))
                }
            }

            Column(modifier = Modifier.padding(end = 12.dp)) {
                val fraction = item.fraction
                when {
                    item.status == DownloadStatus.RUNNING && fraction != null ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    item.status == DownloadStatus.RUNNING ->
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    item.status == DownloadStatus.PAUSED && fraction != null ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = statusText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (item.acceptsRanges == false && !item.isFinished) {
                    Text(
                        text = stringResource(R.string.download_no_resume),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (item.status) {
                    DownloadStatus.QUEUED -> ActionButton(R.string.download_cancel) { viewModel.cancel(item.id) }
                    DownloadStatus.RUNNING -> {
                        ActionButton(R.string.download_pause) { viewModel.pause(item.id) }
                        ActionButton(R.string.download_cancel) { viewModel.cancel(item.id) }
                    }
                    DownloadStatus.PAUSED -> {
                        ActionButton(R.string.download_resume) { viewModel.resume(item.id) }
                        ActionButton(R.string.download_cancel) { viewModel.cancel(item.id) }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELED ->
                        ActionButton(R.string.download_retry) { viewModel.retry(item.id) }
                    DownloadStatus.COMPLETED -> {
                        ActionButton(R.string.download_open) { openFile(context, item) }
                        ActionButton(R.string.download_delete_file) { viewModel.remove(item.id, true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(labelRes)) }
}

@Composable
private fun statusText(item: DownloadItem): String {
    val done = Formatters.bytes(item.downloadedBytes)
    val total = if (item.totalBytes > 0) Formatters.bytes(item.totalBytes) else null
    val eta = item.etaSeconds
    return when (item.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.download_waiting)
        DownloadStatus.RUNNING -> when {
            item.downloadedBytes == 0L && item.speedBps == 0L -> stringResource(R.string.download_starting)
            total == null -> stringResource(R.string.download_running_unknown, done, Formatters.speed(item.speedBps))
            eta == null -> stringResource(
                R.string.download_running_noeta, done, total, Formatters.speed(item.speedBps),
            )
            else -> stringResource(
                R.string.download_running, done, total, Formatters.speed(item.speedBps), Formatters.duration(eta),
            )
        }
        DownloadStatus.PAUSED ->
            if (total != null) stringResource(R.string.download_paused, done, total)
            else stringResource(R.string.download_paused_unknown, done)
        DownloadStatus.COMPLETED -> {
            val average = item.averageSpeedBps
            if (average > 0) stringResource(R.string.download_done, done, Formatters.speed(average))
            else done
        }
        DownloadStatus.CANCELED -> stringResource(R.string.download_canceled)
        DownloadStatus.FAILED -> stringResource(R.string.download_failed, failureText(item.failure))
    }
}

@Composable
private fun failureText(failure: DownloadFailure?): String {
    val code = failure?.httpCode ?: 0
    return when (failure?.kind) {
    FailureKind.NETWORK, null -> stringResource(R.string.fail_network)
    FailureKind.HTTP -> stringResource(R.string.fail_http, code)
    FailureKind.RATE_LIMITED -> stringResource(R.string.fail_rate)
    FailureKind.STORAGE -> stringResource(R.string.fail_storage)
    FailureKind.UNSUPPORTED_URL -> stringResource(R.string.fail_unsupported)
    }
}

private fun openFile(context: Context, item: DownloadItem) {
    val saved = item.savedUri ?: return
    val uri = Uri.parse(saved)
    if (uri.scheme == "file") {
        // Android 8-9: the file is in the app's own folder, which other apps cannot open directly.
        Toast.makeText(context, context.getString(R.string.download_saved_in, uri.path.orEmpty()), Toast.LENGTH_LONG).show()
        return
    }
    val ext = item.fileName.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: item.mimeType ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
    }
}
