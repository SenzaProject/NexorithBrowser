package com.nexorith.browser.downloads

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nexorith.browser.NexorithApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while downloads run, and reports them in a
 * notification. It stops itself when nothing is queued or running. The transfers themselves
 * are owned by [DownloadCenter]; the service only observes it.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val lastStatus = HashMap<Long, DownloadStatus>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val center = (application as NexorithApp).container.downloadCenter
        if (intent?.action == DownloadNotifications.ACTION_PAUSE_ALL) center.pauseAll()

        enterForeground(DownloadNotifications.ongoing(this, center.items.value.filter { it.isActive }))

        if (observer == null) {
            lastStatus.putAll(center.items.value.associate { it.id to it.status })
            observer = scope.launch { center.items.collect { render(it) } }
        }
        return START_NOT_STICKY
    }

    private fun render(items: List<DownloadItem>) {
        for (item in items) {
            val previous = lastStatus.put(item.id, item.status)
            if (previous != null && previous != item.status &&
                (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED)
            ) {
                DownloadNotifications.notifyFinished(this, item)
            }
        }
        lastStatus.keys.retainAll(items.map { it.id }.toSet())

        val active = items.filter { it.isActive }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            DownloadNotifications.updateOngoing(this, active)
        }
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(DownloadNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotifications.ONGOING_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
