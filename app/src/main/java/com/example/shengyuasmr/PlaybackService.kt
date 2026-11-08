package com.example.shengyuasmr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** Owns the audio engine, queue, media session and notification independently from MainActivity. */
class PlaybackService : Service() {
    interface PlaybackListener {
        fun onTrackChanged(index: Int, playing: Boolean)
        fun onPlaybackStateChanged(playing: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    var player: MediaPlayer? = null
        private set
    var isForegroundStarted: Boolean = false
        private set
    var playbackListener: PlaybackListener? = null

    var queue: List<LocalTrack> = emptyList()
        private set
    var workFolder: Uri? = null
        private set
    var workName: String = ""
        private set
    var artworks: List<Uri> = emptyList()
        private set
    var currentIndex: Int = -1
        private set

    private var currentArtIndex: Int = 0
    private var lastPosition: Int = 0
    private val channelId = "shengyu_media_playback"
    private val notificationId = 7301
    private val actionPrevious = "com.example.shengyuasmr.SERVICE_PREVIOUS"
    private val actionToggle = "com.example.shengyuasmr.SERVICE_TOGGLE"
    private val actionNext = "com.example.shengyuasmr.SERVICE_NEXT"
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            actionPrevious -> skipToPrevious()
            actionToggle -> if (isPlaying()) pausePlayback() else resumePlayback()
            actionNext -> skipToNext()
        }
        return START_NOT_STICKY
    }

    fun setPlaybackQueue(
        tracks: List<LocalTrack>,
        folder: Uri?,
        title: String,
        artUris: List<Uri>,
        index: Int,
        artIndex: Int
    ) {
        queue = tracks.toList()
        workFolder = folder
        workName = title
        artworks = artUris.toList()
        currentIndex = index
        currentArtIndex = artIndex.coerceAtLeast(0)
    }

    fun playAt(index: Int, startAt: Int = 0): MediaPlayer? {
        if (index !in queue.indices) return null
        ensureForeground()
        releasePlayerOnly()
        currentIndex = index
        lastPosition = startAt.coerceAtLeast(0)
        val created = MediaPlayer()
        player = created
        try {
            created.setDataSource(this, queue[index].uri)
            created.setOnCompletionListener {
                if (currentIndex < queue.lastIndex) skipToNext()
                else {
                    lastPosition = 0
                    updatePlaybackState(false, PlaybackStateCompat.STATE_STOPPED)
                    updateNotification(currentArtIndex)
                    playbackListener?.onPlaybackStateChanged(false)
                }
            }
            created.prepare()
            if (startAt > 0) created.seekTo(startAt.coerceAtMost(created.duration))
            created.start()
            updateMediaMetadata(currentArtIndex)
            updatePlaybackState(true)
            updateNotification(currentArtIndex)
            playbackListener?.onTrackChanged(currentIndex, true)
            return created
        } catch (error: Exception) {
            releasePlayerOnly()
            updatePlaybackState(false)
            updateNotification(currentArtIndex)
            throw error
        }
    }

    fun startPlayback(uri: Uri, startAt: Int = 0): MediaPlayer {
        setPlaybackQueue(listOf(LocalTrack(uri.lastPathSegment ?: "音轨", uri)), null, "声屿 ASMR", emptyList(), 0, 0)
        return playAt(0, startAt) ?: throw IllegalStateException("无法启动播放器")
    }

    fun pausePlayback() {
        try {
            player?.pause()
            lastPosition = player?.currentPosition ?: lastPosition
        } catch (_: Exception) { }
        updatePlaybackState(false)
        updateNotification(currentArtIndex)
        playbackListener?.onPlaybackStateChanged(false)
    }

    fun resumePlayback() {
        try {
            if (player == null && currentIndex in queue.indices) {
                playAt(currentIndex, lastPosition)
                return
            }
            player?.start()
        } catch (_: Exception) { }
        updatePlaybackState(isPlaying())
        updateNotification(currentArtIndex)
        playbackListener?.onPlaybackStateChanged(isPlaying())
    }

    fun seekTo(position: Int) {
        try {
            val duration = player?.duration ?: 0
            val target = position.coerceIn(0, duration)
            player?.seekTo(target)
            lastPosition = target
        } catch (_: Exception) { }
        updatePlaybackState(isPlaying())
    }

    fun skipToNext() {
        if (currentIndex < queue.lastIndex) playAt(currentIndex + 1, 0)
    }

    fun skipToPrevious() {
        if (currentIndex > 0) playAt(currentIndex - 1, 0)
    }

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }

    fun updateMediaMetadata(artIndex: Int = currentArtIndex) {
        currentArtIndex = artIndex.coerceAtLeast(0)
        val track = queue.getOrNull(currentIndex) ?: return
        val bitmap = try {
            artworks.getOrNull(currentArtIndex)?.let { contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) } }
        } catch (_: Exception) { null }
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, workName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.name.substringBeforeLast('.'))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, workName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, workName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.name.substringBeforeLast('.'))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, try { player?.duration?.toLong() ?: 0L } catch (_: Exception) { 0L })
        if (bitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
        }
        mediaSession?.setMetadata(builder.build())
    }

    fun updatePlaybackState(playing: Boolean, stateOverride: Int? = null) {
        val position = try { player?.currentPosition?.toLong() ?: lastPosition.toLong() } catch (_: Exception) { lastPosition.toLong() }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(stateOverride ?: if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, position, if (playing) 1f else 0f)
            .build())
    }

    fun updateNotification(artIndex: Int = currentArtIndex) {
        currentArtIndex = artIndex.coerceAtLeast(0)
        val track = queue.getOrNull(currentIndex) ?: return
        val playing = isPlaying()
        updateMediaMetadata(currentArtIndex)
        updatePlaybackState(playing)
        val openApp = PendingIntent.getActivity(
            this, 7302, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(workName)
            .setContentText(track.name.substringBeforeLast('.'))
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setLargeIcon(try { artworks.getOrNull(currentArtIndex)?.let { contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) } } } catch (_: Exception) { null })
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "上一首", serviceAction(actionPrevious, 7303))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "暂停" else "播放", serviceAction(actionToggle, 7304))
            .addAction(android.R.drawable.ic_media_next, "下一首", serviceAction(actionNext, 7305))
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
        try {
            getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
        } catch (_: SecurityException) { }
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ShengyuASMR").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipToNext()
                override fun onSkipToPrevious() = skipToPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            })
            isActive = true
        }
        updatePlaybackState(false)
    }

    private fun ensureForeground() {
        if (isForegroundStarted) return
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
        isForegroundStarted = true
    }

    fun releasePlayback() {
        releasePlayerOnly()
        queue = emptyList()
        currentIndex = -1
        workFolder = null
        workName = ""
        artworks = emptyList()
        mediaSession?.setMetadata(null)
        updatePlaybackState(false, PlaybackStateCompat.STATE_STOPPED)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        isForegroundStarted = false
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
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
