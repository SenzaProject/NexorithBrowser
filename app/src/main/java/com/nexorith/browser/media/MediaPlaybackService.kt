package com.nexorith.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexorith.browser.MainActivity
import com.nexorith.browser.R

/**
 * Keeps the app process alive and in the foreground while a page plays audio and the browser is
 * in the background, so that Android does not freeze it. It stops itself shortly after the audio
 * ends, and the activity stops it when the browser comes back to the front.
 */
class MediaPlaybackService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var silentChecks = 0

    private val check = object : Runnable {
        override fun run() {
            if (BackgroundMedia.isAudioPlaying(this@MediaPlaybackService)) silentChecks = 0 else silentChecks++
            if (silentChecks >= SILENT_CHECKS_BEFORE_STOP) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_media),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        silentChecks = 0
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_INTERVAL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_media_title))
            .setContentText(getString(R.string.notif_media_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "media_playback"
        const val NOTIFICATION_ID = 7002
        const val CHECK_INTERVAL_MS = 5_000L
        const val SILENT_CHECKS_BEFORE_STOP = 3
    }
}

/** Helpers for the activity: is audio playing, start and stop the keep-alive service. */
object BackgroundMedia {

    fun isAudioPlaying(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed to start a foreground service right now; playback still continues while the process lives.
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MediaPlaybackService::class.java))
    }
}
