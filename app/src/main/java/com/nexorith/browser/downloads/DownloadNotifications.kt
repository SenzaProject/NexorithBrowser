package com.nexorith.browser.downloads

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nexorith.browser.MainActivity
import com.nexorith.browser.R

object DownloadNotifications {

    private const val CHANNEL_PROGRESS = "downloads_progress"
    private const val CHANNEL_DONE = "downloads_done"
    const val ONGOING_ID = 7001
    const val ACTION_PAUSE_ALL = "com.nexorith.browser.action.PAUSE_ALL_DOWNLOADS"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.notif_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.notif_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** The ongoing notification of the foreground service. */
    fun ongoing(context: Context, active: List<DownloadItem>): Notification {
        val running = active.filter { it.status == DownloadStatus.RUNNING }
        val title = when {
            active.size == 1 -> displayName(context, active[0])
            active.isEmpty() -> context.getString(R.string.notif_preparing)
            else -> context.getString(R.string.notif_multiple, active.size)
        }
        val speed = running.sumOf { it.speedBps }
        val done = active.sumOf { it.downloadedBytes }
        val totalKnown = active.isNotEmpty() && active.all { it.totalBytes > 0 }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val text = when {
            running.isEmpty() -> context.getString(R.string.download_waiting)
            totalKnown -> context.getString(
                R.string.notif_progress,
                Formatters.bytes(done),
                Formatters.bytes(total),
                Formatters.speed(speed),
            )
            else -> context.getString(R.string.notif_progress_unknown, Formatters.bytes(done), Formatters.speed(speed))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openDownloadsIntent(context))
            .addAction(0, context.getString(R.string.notif_pause_all), pauseAllIntent(context))
        if (totalKnown && total > 0) {
            builder.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun updateOngoing(context: Context, active: List<DownloadItem>) {
        notify(context, ONGOING_ID, ongoing(context, active))
    }

    /** Posts a "complete" or "failed" notification. Private downloads never show their file name. */
    fun notifyFinished(context: Context, item: DownloadItem) {
        val failed = item.status == DownloadStatus.FAILED
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(displayName(context, item))
            .setContentText(context.getString(if (failed) R.string.notif_failed else R.string.notif_complete))
            .setAutoCancel(true)
            .setContentIntent(openDownloadsIntent(context))
            .build()
        notify(context, 10_000 + (item.id.hashCode() and 0x7fffffff) % 90_000, notification)
    }

    private fun displayName(context: Context, item: DownloadItem): String =
        if (item.isPrivate) context.getString(R.string.download_private_name) else item.fileName

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openDownloadsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun pauseAllIntent(context: Context): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE_ALL)
        return PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
