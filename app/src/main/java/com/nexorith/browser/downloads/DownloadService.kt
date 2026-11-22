package com.nexorith.browser.downloads

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nexorith.browser.NexorithApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
    private var wakeLock: PowerManager.WakeLock? = null

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
            observer = scope.launch {
                center.items.combine(center.loaded) { items, loaded -> items to loaded }
                    .collect { (items, loaded) -> render(items, loaded) }
            }
        }
        // Sticky: if the system kills the process, it brings the service back, and the download
        // center then resumes the interrupted downloads.
        return START_STICKY
    }

    /** Swiping the app away from the recent apps list must not stop the downloads. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /** Android 15 limits data-sync services; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun render(items: List<DownloadItem>, loaded: Boolean) {
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
            // Right after a restart the list is still empty while it is being read.
            if (!loaded) return
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            acquireWakeLock()
            DownloadNotifications.updateOngoing(this, active)
        }
    }

    /** Keeps the CPU awake while the screen is off; always released with a timeout as a backstop. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexorith:downloads")
            ?.also { wakeLock = it }
            ?: return
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(DownloadNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotifications.ONGOING_ID, notification)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
