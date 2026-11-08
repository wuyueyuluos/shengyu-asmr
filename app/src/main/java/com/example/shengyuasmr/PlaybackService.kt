package com.example.shengyuasmr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Owns the audio engine independently from MainActivity. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    var player: MediaPlayer? = null
        private set

    private val channelId = "shengyu_media_playback"
    private val notificationId = 7301

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val openApp = PendingIntent.getActivity(
            this, 7302, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("声屿 ASMR")
            .setContentText("正在准备播放")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
        return START_NOT_STICKY
    }

    fun startPlayback(uri: Uri, startAt: Int = 0): MediaPlayer {
        releasePlayerOnly()
        val created = MediaPlayer()
        player = created
        try {
            created.setDataSource(this, uri)
            created.prepare()
            if (startAt > 0) created.seekTo(startAt.coerceAtMost(created.duration))
            created.start()
            return created
        } catch (error: Exception) {
            releasePlayerOnly()
            throw error
        }
    }

    fun releasePlayback() {
        releasePlayerOnly()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releasePlayerOnly() {
        try { player?.setOnCompletionListener(null); player?.release() } catch (_: Exception) { }
        player = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "声屿播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示当前播放作品和音轨，并提供播放控制"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() {
        releasePlayerOnly()
        super.onDestroy()
    }
}
