package com.example.shengyuasmr

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import android.app.Dialog
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

data class LocalTrack(val name: String, val uri: Uri)
data class LocalWork(
    val title: String,
    val folder: Uri,
    val tracks: List<LocalTrack>,
    val arts: List<Uri>,
    val scripts: List<Uri>,
    val cover: Uri? = null,
    val cv: String = "",
    val circle: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val importedAt: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private val tracks = mutableListOf<LocalTrack>()
    private val artworks = mutableListOf<Uri>()
    private val scripts = mutableListOf<Uri>()
    private val works = mutableListOf<LocalWork>()
    private var scanningFolder: Uri? = null
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private val mediaNotificationId = 7301
    private val mediaChannelId = "shengyu_media_playback"
    private val ACTION_MEDIA_PREVIOUS = "com.example.shengyuasmr.MEDIA_PREVIOUS"
    private val ACTION_MEDIA_TOGGLE = "com.example.shengyuasmr.MEDIA_TOGGLE"
    private val ACTION_MEDIA_NEXT = "com.example.shengyuasmr.MEDIA_NEXT"
    private var currentTrack = -1
    private var currentArt = 0
    private var showScript = false
    private var currentFolderName = "还没有导入作品"
    private var currentScriptText = ""
    private var selectedScriptIndex = 0
    private var scriptScrollY = 0
    private var previewArtIndex = 0
    private var currentScreen = "library"
    private var lastBackPressedAt = 0L
    private var libraryFilter = "全部作品"
    private var appliedTags = linkedSetOf<String>()
    private var libraryScrollY = 0
    private var libraryScrollView: ScrollView? = null
    private val pdfRenderHandler = Handler(Looper.getMainLooper())
    private var pdfReaderView: PdfReaderView? = null

    private fun saveLibraryScrollPosition() {
        // Keep a direct reference: the library view may already be detached while the
        // rescan/import screen replaces the content hierarchy.
        val scroll = libraryScrollView
        if (scroll != null) libraryScrollY = scroll.scrollY
        getSharedPreferences("shengyu_playback", MODE_PRIVATE).edit()
            .putLong("library_scroll_y", libraryScrollY.toLong()).apply()
    }

    private fun decodeLibraryThumbnail(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 400) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    }
    private val progressHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var sleepTimerEndAt = 0L
    private var sleepTimerRunnable: Runnable? = null
    private var progressBar: SeekBar? = null
    private var elapsedLabel: TextView? = null
    private var durationLabel: TextView? = null
    private var resumePosition = 0
    private var folderUri: Uri? = null
    private var pickingLibraryRoot = false
    private val exportTagFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTagCatalog(uri)
    }
    private val importTagFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTagCatalog(uri)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            if (pickingLibraryRoot) {
                getSharedPreferences("shengyu_settings", MODE_PRIVATE).edit().putString("library_root_uri", uri.toString()).apply()
                pickingLibraryRoot = false
                showGeneralSettings()
            } else {
                folderUri = uri
                scanFolder(uri)
            }
        } else {
            pickingLibraryRoot = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        val libraryPrefs = getSharedPreferences("shengyu_library", MODE_PRIVATE)
        libraryFilter = libraryPrefs.getString("library_filter", "全部作品") ?: "全部作品"
        try {
            val savedTags = JSONArray(libraryPrefs.getString("applied_filter_tags", "[]") ?: "[]")
            for (i in 0 until savedTags.length()) savedTags.optString(i).takeIf { it.isNotBlank() }?.let { appliedTags.add(it) }
        } catch (_: Exception) { }
        setupMediaSession()
        loadWorks()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    "script" -> { showScript = false; renderHome() }
                    "tracklist" -> { if (showScript) loadScript() else renderHome() }
                    "home" -> renderLibrary()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedAt < 2000) finish()
                        else {
                            lastBackPressedAt = now
                            Toast.makeText(this@MainActivity, "再按一次返回键退出声屿", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
        renderLibrary()
    }

    private data class ThemeOption(val id: String, val name: String, val hue: Float)

    private val themeOptions = listOf(
        ThemeOption("lavender", "薰衣草紫", 270f),
        ThemeOption("sakura", "樱花粉", 340f),
        ThemeOption("sky", "天空蓝", 205f),
        ThemeOption("mint", "薄荷绿", 158f),
        ThemeOption("matcha", "抹茶绿", 105f),
        ThemeOption("apricot", "奶油杏", 30f)
    )

    private fun selectedThemeId(): String {
        val saved = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
            .getString("app_theme", "lavender") ?: "lavender"
        return if (themeOptions.any { it.id == saved }) saved else "lavender"
    }

    private fun themeColor(color: Int): Int = themeColorFor(color, selectedThemeId())

    private fun themeColorFor(color: Int, themeId: String): Int {
        if (themeId == "lavender") return color
        val option = themeOptions.firstOrNull { it.id == themeId } ?: return color
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        // Keep pure neutrals and the dark image-preview surfaces unchanged.
        if (hsv[1] < 0.008f || hsv[2] < 0.28f) return color
        if (hsv[0] < 220f || hsv[0] > 330f) return color
        val shift = ((option.hue - 270f + 540f) % 360f) - 180f
        hsv[0] = (hsv[0] + shift + 360f) % 360f
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
    }

    private fun refreshThemeScreen() {
        when (currentScreen) {
            "script" -> loadScript()
            "home", "tracklist" -> renderHome()
            "library" -> renderLibrary()
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(8))
            setBackgroundColor(themeColor(0xFFF8F5FA.toInt()))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }


    private fun setupMediaSession() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(mediaChannelId, "声屿播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示当前播放作品和音轨，并提供播放控制"
                setShowBadge(false)
            })
        }
        mediaSession = MediaSessionCompat(this, "ShengyuASMR").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (player == null && tracks.isNotEmpty()) playTrack(if (currentTrack >= 0) currentTrack else 0)
                    else if (player?.isPlaying == false) {
                        player?.start(); updateMediaPlaybackState(true); updateMediaNotification()
                        if (currentScreen == "home") renderHome()
                    }
                }
                override fun onPause() {
                    player?.pause(); updateMediaPlaybackState(false); updateMediaNotification()
                    if (currentScreen == "home") renderHome()
                }
                override fun onSkipToNext() { if (currentTrack < tracks.lastIndex) playTrack(currentTrack + 1) }
                override fun onSkipToPrevious() { if (currentTrack > 0) playTrack(currentTrack - 1) }
                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos.coerceIn(0, (player?.duration ?: 0).toLong()).toInt())
                    savePlaybackMemory(); updateMediaPlaybackState(player?.isPlaying == true)
                }
            })
            isActive = true
        }
        updateMediaPlaybackState(false)
    }

    private fun updateMediaMetadata() {
        val track = tracks.getOrNull(currentTrack) ?: return
        val bitmap = try {
            val uri = artworks.getOrNull(currentArt) ?: works.firstOrNull { it.folder == folderUri }?.cover
            uri?.let { contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) } }
        } catch (_: Exception) { null }
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentFolderName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.name.substringBeforeLast('.'))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentFolderName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentFolderName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.name.substringBeforeLast('.'))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, try { player?.duration?.toLong() ?: 0L } catch (_: Exception) { 0L })
        if (bitmap != null) { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap); builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap); builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap) }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updateMediaPlaybackState(playing: Boolean) {
        val position = try { player?.currentPosition?.toLong() ?: resumePosition.toLong() } catch (_: Exception) { resumePosition.toLong() }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, position, if (playing) 1f else 0f)
            .build())
    }

    private fun mediaButtonPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_MEDIA_PREVIOUS -> if (currentTrack > 0) playTrack(currentTrack - 1)
            ACTION_MEDIA_TOGGLE -> {
                if (player?.isPlaying == true) { player?.pause(); updateMediaPlaybackState(false); updateMediaNotification() }
                else if (currentTrack >= 0) { player?.start(); updateMediaPlaybackState(true); updateMediaNotification() }
                else if (tracks.isNotEmpty()) playTrack(0)
                if (currentScreen == "home") renderHome()
            }
            ACTION_MEDIA_NEXT -> if (currentTrack < tracks.lastIndex) playTrack(currentTrack + 1)
        }
    }

    private fun updateMediaNotification() {
        val track = tracks.getOrNull(currentTrack) ?: return
        val playing = try { player?.isPlaying == true } catch (_: Exception) { false }
        updateMediaMetadata()
        updateMediaPlaybackState(playing)
        val openApp = PendingIntent.getActivity(this, 7302, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, mediaChannelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentFolderName)
            .setContentText(track.name.substringBeforeLast('.'))
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setLargeIcon(try { val uri = artworks.getOrNull(currentArt) ?: works.firstOrNull { it.folder == folderUri }?.cover; uri?.let { contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) } } } catch (_: Exception) { null })
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "上一首", mediaButtonPendingIntent(ACTION_MEDIA_PREVIOUS, 7303))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "暂停" else "播放", mediaButtonPendingIntent(ACTION_MEDIA_TOGGLE, 7304))
            .addAction(android.R.drawable.ic_media_next, "下一首", mediaButtonPendingIntent(ACTION_MEDIA_NEXT, 7305))
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
        try {
            if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this).notify(mediaNotificationId, builder.build())
            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7306)
            }
        } catch (_: SecurityException) { }
    }

    private fun renderLibrary() {
        saveLibraryScrollPosition()
        root.setBackgroundColor(themeColor(0xFFF8F5FA.toInt()))
        currentScreen = "library"
        content.removeAllViews()
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(label("声屿", 27, themeColor(0xFF51435F.toInt()), true))
        brandText.addView(label("SHENGYU  ·  ASMR LIBRARY", 9, themeColor(0xFFA095AA.toInt()), true))
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        fun addHeaderIcon(glyph: String, description: String, action: () -> Unit, longAction: (() -> Unit)? = null) {
            val icon = TextView(this).apply {
                text = glyph
                textSize = 23f
                gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(themeColor(0xFF756681.toInt()))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(themeColor(0x00FFFFFF.toInt()))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener { action() }
                if (longAction != null) setOnLongClickListener { longAction(); true }
            }
            brand.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        }
        addHeaderIcon("◷", "定时停止", { showSleepTimer() }) { showSleepTimerPicker() }
        addHeaderIcon("☰", "设置", { showSettings() })
        content.addView(brand)
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFEDE5F2.toInt())); cornerRadius = dp(14).toFloat() }
            setOnClickListener { showLibrarySections() }
        }
        heading.addView(label(libraryFilter + "  ▾", 16, themeColor(0xFF51435F.toInt()), true))
        heading.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        heading.addView(label(filteredWorks().size.toString() + " 部作品", 12, themeColor(0xFF91869B.toInt())))
        content.addView(heading, LinearLayout.LayoutParams(-1, dp(48)))
        if (works.isEmpty()) {
            val empty = TextView(this).apply {
                text = "♫\n\n声屿还在等你的第一份声音。"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(themeColor(0xFF8A8192.toInt()))
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this).apply {
                clipToPadding = false
                setPadding(0, dp(8), 0, dp(8))
                libraryScrollView = this
                setOnScrollChangeListener { _, _, y, _, _ ->
                    libraryScrollY = y
                    getSharedPreferences("shengyu_playback", MODE_PRIVATE).edit()
                        .putLong("library_scroll_y", y.toLong()).apply()
                }
            }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(1), 0, dp(1), dp(8)) }
            filteredWorks().forEachIndexed { index, work ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9), dp(9), dp(6), dp(9))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFFAF8FC.toInt())); cornerRadius = dp(18).toFloat(); setStroke(dp(1), themeColor(0xFFEAE3F0.toInt())) }
                    elevation = dp(2).toFloat()
                }
                val coverView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFEAE3F0.toInt())); cornerRadius = dp(13).toFloat() }
                    clipToOutline = true
                    val coverUri = work.cover ?: work.arts.firstOrNull()
                    if (coverUri != null) try { setImageBitmap(decodeLibraryThumbnail(coverUri)) } catch (_: Exception) {}
                    if (drawable == null) setImageResource(android.R.drawable.ic_menu_gallery)
                }
                row.addView(coverView, LinearLayout.LayoutParams(dp(76), dp(94)))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                info.addView(TextView(this).apply {
                    text = work.title
                    textSize = 15f
                    setTextColor(themeColor(0xFF45394F.toInt()))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    setLineSpacing(dp(2).toFloat(), 1.02f)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                info.addView(label("${work.tracks.size} 音轨  ·  ${work.arts.size} 插画  ·  ${work.scripts.size} 台本", 10, themeColor(0xFF91869B.toInt())).apply { setPadding(0, dp(5), 0, 0) })
                val categoryLine = listOf(work.cv, work.circle).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (categoryLine.isNotBlank()) info.addView(label(categoryLine, 10, themeColor(0xFF9A8EA4.toInt())).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
                row.addView(info, LinearLayout.LayoutParams(0, dp(94), 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(themeColor(0xFF82738F.toInt()))
                    setPadding(dp(3), 0, dp(3), 0)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFF2EDF6.toInt())); cornerRadius = dp(12).toFloat() }
                    setOnClickListener { saveLibraryScrollPosition(); showWorkMenu(work) }
                }, LinearLayout.LayoutParams(dp(28), dp(94)))
                row.setOnClickListener {
                    saveLibraryScrollPosition()
                    getSharedPreferences("shengyu_playback", MODE_PRIVATE).edit()
                        .putLong("library_scroll_y", libraryScrollY.toLong()).apply()
                    val sameWork = folderUri == work.folder
                    currentFolderName = work.title
                    folderUri = work.folder
                    tracks.clear(); tracks.addAll(work.tracks)
                    artworks.clear(); artworks.addAll(work.arts)
                    scripts.clear(); scripts.addAll(work.scripts)
                    if (!sameWork) {
                        currentArt = 0
                        val saved = readPlaybackMemory(work.folder)
                        currentTrack = saved.first
                        showScript = false
                        if (currentTrack in tracks.indices) playTrack(currentTrack, saved.second) else renderHome()
                    } else { showScript = false; renderHome() }
                }
                val lp = LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(9); marginStart = dp(2); marginEnd = dp(2) }
                list.addView(row, lp)
            }
            scroll.addView(list)
            content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            libraryScrollY = getSharedPreferences("shengyu_playback", MODE_PRIVATE)
                .getLong("library_scroll_y", libraryScrollY.toLong()).toInt().coerceAtLeast(0)
            scroll.post { scroll.scrollTo(0, libraryScrollY) }
        }
        val importArea = FrameLayout(this)
        val importButton = button("+").apply {
            textSize = 26f
            setPadding(0, 0, 0, 0)
            setOnClickListener { scanningFolder = null; pickFolder.launch(null) }
        }
        importArea.addView(importButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM))
        content.addView(importArea, LinearLayout.LayoutParams(-1, dp(64)))
    }

    private fun renderHome() {
        saveScriptScrollPosition()
        root.setBackgroundColor(themeColor(0xFFF8F5FA.toInt()))
        currentScreen = "home"
        content.removeAllViews()
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColor(android.graphics.Color.argb(224, 231, 222, 235)))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), android.graphics.Color.argb(105, 255, 255, 255))
            }
            elevation = dp(2).toFloat()
        }
        heading.addView(label("NOW PLAYING  ·  正在聆听", 9, themeColor(0xFF9A8EA4.toInt()), true))
        heading.addView(label(currentFolderName, 18, themeColor(0xFF51435F.toInt()), true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(5))
        })
        val counts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addCount(glyph: String, count: Int) {
            counts.addView(label("$glyph  $count", 12, themeColor(0xFF81758F.toInt())).apply {
                setPadding(0, 0, dp(18), 0)
            })
        }
        addCount("♫", tracks.size)
        addCount("▧", artworks.size)
        addCount("▤", scripts.size)
        heading.addView(counts)
        val trackName = tracks.getOrNull(currentTrack)?.name ?: "尚未选择音轨"
        heading.addView(label(trackName, 11, themeColor(0xFF9A8EA4.toInt())).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val artFrame = FrameLayout(this).apply {
            clipToPadding = true
            clipChildren = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColor(0xFFF0EAF5.toInt()))
                cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(7), dp(8), dp(7), dp(8))
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(17).toFloat()
            }
            clipToOutline = true
        }
        if (artworks.isNotEmpty()) {
            try {
                contentResolver.openInputStream(artworks[currentArt % artworks.size])?.use {
                    val bitmap = BitmapFactory.decodeStream(it)
                    if (bitmap != null) {
                        art.setImageBitmap(bitmap)
                        artFrame.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(artworkTint(bitmap))
                            cornerRadius = dp(22).toFloat()
                        }
                    }
                }
            } catch (_: Exception) { }
        } else {
            art.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val artLp = LinearLayout.LayoutParams(-1, dp(420)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(8)
        }
        content.addView(artFrame, artLp)
        var bannerDownX = 0f
        var bannerDownY = 0f
        var bannerHandledSwipe = false
        art.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bannerDownX = event.x
                    bannerDownY = event.y
                    bannerHandledSwipe = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - bannerDownX
                    val dy = event.y - bannerDownY
                    if (artworks.size > 1 && kotlin.math.abs(dx) > dp(45) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        currentArt = if (dx < 0) (currentArt + 1) % artworks.size
                            else (currentArt - 1 + artworks.size) % artworks.size
                        bannerHandledSwipe = true
                        renderHome()
                    } else if (!bannerHandledSwipe && artworks.isNotEmpty()) {
                        showArtPreview(currentArt)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        renderBottomControls()
    }

    private fun artworkTint(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        pixels.forEach { color ->
            if (android.graphics.Color.alpha(color) > 24) {
                red += android.graphics.Color.red(color)
                green += android.graphics.Color.green(color)
                blue += android.graphics.Color.blue(color)
                count++
            }
        }
        if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        if (count == 0L) return themeColor(0x55DCCFE8.toInt())
        val r = ((red / count) * 0.24 + 255 * 0.76).toInt().coerceIn(0, 255)
        val g = ((green / count) * 0.24 + 255 * 0.76).toInt().coerceIn(0, 255)
        val b = ((blue / count) * 0.24 + 255 * 0.76).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(125, r, g, b)
    }

    private fun filteredWorks(): List<LocalWork> {
        return when (libraryFilter) {
            "最近播放" -> works.sortedByDescending { getSharedPreferences("shengyu_playback", MODE_PRIVATE).getLong("lastPlayed_" + it.folder.toString().hashCode(), 0L) }
            "导入时间" -> works.sortedByDescending { it.importedAt }
            "收藏" -> works.filter { it.favorite }
            "标签" -> works.filter { w -> appliedTags.isNotEmpty() && appliedTags.all { it in (w.cv.split(",", "，", "、") + w.circle.split(",", "，", "、") + w.tags).map { v -> v.trim() }.filter { v -> v.isNotBlank() } } }
            else -> works.toList()
        }
    }
    private fun showLibrarySections() {
        val choices = arrayOf("全部作品", "最近播放", "导入时间", "收藏", "标签")
        android.app.AlertDialog.Builder(this).setTitle("作品分类").setItems(choices) { _, i ->
            if (i == 4) showTagFilter() else { libraryFilter = choices[i]; appliedTags.clear(); getSharedPreferences("shengyu_library", MODE_PRIVATE).edit().putString("library_filter", libraryFilter).putString("applied_filter_tags", "[]").apply(); renderLibrary() }
        }.setNegativeButton("取消", null).showStyled()
    }
    private fun loadTagCatalog(): LinkedHashMap<String, MutableList<String>> {
        val prefs = getSharedPreferences("shengyu_library", MODE_PRIVATE)
        val names = listOf("CV", "社团", "自定义")
        val catalog = linkedMapOf<String, MutableList<String>>()
        val saved = prefs.getString("tag_catalog", null)
        if (saved != null) {
            try {
                val json = JSONObject(saved)
                names.forEach { name ->
                    val arr = json.optJSONArray(name) ?: JSONArray()
                    catalog[name] = (0 until arr.length()).map { arr.optString(it).trim() }
                        .filter { it.isNotBlank() }.distinct().toMutableList()
                }
            } catch (_: Exception) { catalog.clear() }
        }
        if (catalog.isEmpty()) {
            catalog["CV"] = works.flatMap { it.cv.split(",", "，", "、") }.map { it.trim() }
                .filter { it.isNotBlank() }.distinct().toMutableList()
            catalog["社团"] = works.flatMap { it.circle.split(",", "，", "、") }.map { it.trim() }
                .filter { it.isNotBlank() }.distinct().toMutableList()
            catalog["自定义"] = works.flatMap { it.tags }.map { it.trim() }
                .filter { it.isNotBlank() }.distinct().toMutableList()
        }
        names.forEach { if (catalog[it] == null) catalog[it] = mutableListOf() }
        return catalog
    }

    private fun saveTagCatalog(catalog: Map<String, List<String>>) {
        val json = JSONObject()
        listOf("CV", "社团", "自定义").forEach { name ->
            json.put(name, JSONArray(catalog[name] ?: emptyList<String>()))
        }
        getSharedPreferences("shengyu_library", MODE_PRIVATE).edit()
            .putString("tag_catalog", json.toString()).apply()
    }

    private fun exportTagCatalog(uri: Uri) {
        try {
            val catalog = loadTagCatalog()
            val tags = JSONObject()
            listOf("CV", "社团", "自定义").forEach { name ->
                tags.put(name, JSONArray(catalog[name] ?: emptyList<String>()))
            }
            val document = JSONObject().put("app", "声屿 ASMR").put("formatVersion", 1)
                .put("createdAt", System.currentTimeMillis()).put("tags", tags)
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法创建导出文件")
            output.use { it.write(document.toString(2).toByteArray(Charsets.UTF_8)) }
            val total = catalog.values.sumOf { it.size }
            Toast.makeText(this, "标签已导出，共 $total 个", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message ?: "无法写入文件"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importTagCatalog(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            val bytes = input.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 1024 * 1024) {
                throw IllegalArgumentException("文件为空或超过 1 MB")
            }
            val document = JSONObject(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
            if (document.optInt("formatVersion", -1) != 1) {
                throw IllegalArgumentException("不支持的标签文件版本")
            }
            val source = document.optJSONObject("tags")
                ?: throw IllegalArgumentException("文件中没有标签数据")
            val imported = linkedMapOf<String, List<String>>()
            listOf("CV", "社团", "自定义").forEach { name ->
                val array = source.optJSONArray(name) ?: JSONArray()
                imported[name] = (0 until array.length()).map { array.optString(it).trim() }
                    .filter { it.isNotBlank() }.distinct()
            }
            val incomingCount = imported.values.sumOf { it.size }
            if (incomingCount == 0) throw IllegalArgumentException("文件中没有可导入的标签")
            val existing = loadTagCatalog()
            var addedCount = 0
            imported.forEach { (group, values) ->
                val bucket = existing[group] ?: mutableListOf<String>().also { existing[group] = it }
                values.forEach { value -> if (value !in bucket) { bucket.add(value); addedCount++ } }
            }
            val groupSummary = listOf("CV", "社团", "自定义").joinToString("、") { group ->
                "$group ${imported[group]?.size ?: 0} 个"
            }
            android.app.AlertDialog.Builder(this).setTitle("导入标签")
                .setMessage("文件包含：$groupSummary\n\n导入会与当前标签目录合并，重复标签自动跳过；不会修改任何作品资料。")
                .setPositiveButton("合并导入") { _, _ ->
                    saveTagCatalog(existing)
                    Toast.makeText(this, "导入完成，新增 $addedCount 个标签", Toast.LENGTH_LONG).show()
                }.setNegativeButton("取消", null).showStyled()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message ?: "文件格式不正确"}", Toast.LENGTH_LONG).show()
        }
    }
    private fun showTagFilter() {
        val prefs = getSharedPreferences("shengyu_library", MODE_PRIVATE)
        val names = listOf("CV", "社团", "自定义")
        val catalog = loadTagCatalog()
        fun saveCatalog() = saveTagCatalog(catalog)
        val selected = appliedTags.toMutableSet()
        val collapsed = mutableSetOf<String>()
        var editing = false
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFFAF8FC.toInt())); cornerRadius = dp(24).toFloat() }
        }
        panel.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFD9D0E2.toInt())); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(38), dp(5)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        panel.addView(label("标签筛选", 18, themeColor(0xFF51435F.toInt()), true).apply { setPadding(0, 0, 0, dp(8)) })
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = EditText(this).apply { hint = "搜索标签"; setSingleLine(true); textSize = 14f; setPadding(dp(12), 0, dp(12), 0); background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFF0ECF4.toInt())); cornerRadius = dp(13).toFloat() } }
        top.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        val editButton = button("编辑").apply { textSize = 12f }
        top.addView(editButton, LinearLayout.LayoutParams(dp(64), dp(44)))
        panel.addView(top)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        fun redraw(query: String = "") {
            list.removeAllViews()
            names.forEach { group ->
                val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val title = TextView(this@MainActivity).apply {
                    text = (if (group in collapsed) "▸ " else "▾ ") + group + "（${catalog[group]!!.size}）"
                    textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(themeColor(0xFF514665.toInt()))
                    setPadding(0, dp(10), 0, dp(8))
                    setOnClickListener { if (!collapsed.add(group)) collapsed.remove(group); redraw(search.text.toString()) }
                }
                header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
                val add = button("+").apply {
                    visibility = if (editing) View.VISIBLE else View.GONE
                    setOnClickListener {
                        val input = EditText(this@MainActivity).apply {
                            hint = "输入新的${group}标签"
                            setSingleLine(true)
                            backgroundTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF9A82B3.toInt()))
                        }
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle("新增${group}标签").setView(input)
                            .setPositiveButton("添加") { _, _ ->
                                val value = input.text.toString().trim()
                                if (value.isNotBlank() && value !in catalog[group]!!) { catalog[group]!!.add(value); saveCatalog(); redraw(search.text.toString()) }
                            }.setNegativeButton("取消", null).showStyled()
                    }
                }
                header.addView(add, LinearLayout.LayoutParams(dp(44), dp(38)))
                list.addView(header)
                if (group !in collapsed) {
                    val chips = android.widget.GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
                    catalog[group]!!.filter { it.contains(query, true) }.forEach { tag ->
                        val chip = TextView(this@MainActivity).apply {
                            text = tag; gravity = Gravity.CENTER; textSize = 13f
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            setTextColor(if (tag in selected) -1 else themeColor(0xFF5C4D79.toInt()))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = dp(18).toFloat()
                                setColor(if (tag in selected) themeColor(0xFF8B78AD.toInt()) else themeColor(0xFFE8E0F1.toInt()))
                            }
                            setOnClickListener {
                                if (!editing) { if (!selected.add(tag)) selected.remove(tag) }
                                redraw(search.text.toString())
                            }
                            setOnLongClickListener {
                                if (!editing) return@setOnLongClickListener false
                                val items = arrayOf("上移", "下移", "从标签面板删除")
                                android.app.AlertDialog.Builder(this@MainActivity).setTitle(tag).setItems(items) { _, which ->
                                    val bucket = catalog[group]!!
                                    val index = bucket.indexOf(tag)
                                    when (which) {
                                        0 -> if (index > 0) { java.util.Collections.swap(bucket, index, index - 1); saveCatalog() }
                                        1 -> if (index >= 0 && index < bucket.lastIndex) { java.util.Collections.swap(bucket, index, index + 1); saveCatalog() }
                                        2 -> android.app.AlertDialog.Builder(this@MainActivity)
                                            .setMessage("只从标签面板目录删除“$tag”，不会修改任何作品资料。")
                                            .setPositiveButton("删除") { _, _ -> bucket.remove(tag); selected.remove(tag); appliedTags.remove(tag); saveCatalog(); redraw(search.text.toString()) }
                                            .setNegativeButton("取消", null).showStyled()
                                    }
                                    redraw(search.text.toString())
                                }.setNegativeButton("取消", null).showStyled()
                                true
                            }
                        }
                        chips.addView(chip, android.widget.GridLayout.LayoutParams().apply {
                            width = 0
                            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                            setMargins(dp(3), dp(3), dp(3), dp(3))
                        })
                    }
                    list.addView(chips)
                }
            }
        }
        editButton.setOnClickListener { editing = !editing; editButton.text = if (editing) "完成" else "编辑"; redraw(search.text.toString()) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { redraw(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        redraw()
        panel.addView(button("应用筛选").apply {
            setOnClickListener { appliedTags.clear(); appliedTags.addAll(selected); libraryFilter = "标签"; prefs.edit().putString("library_filter", libraryFilter).putString("applied_filter_tags", JSONArray(appliedTags.toList()).toString()).apply(); dialog.dismiss(); renderLibrary() }
        })
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.66f).toInt())
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setDimAmount(0.3f)
    }

    private fun showWorkMenu(work: LocalWork) {
        val options = arrayOf("编辑", if (work.favorite) "取消收藏" else "收藏", "重新扫描并更新", "从作品库删除")
        android.app.AlertDialog.Builder(this).setTitle(work.title).setItems(options) { _, which ->
            when (which) {
                0 -> showEditWork(work)
                1 -> { val i = works.indexOfFirst { it.folder == work.folder }; if (i >= 0) { works[i] = works[i].copy(favorite = !work.favorite); saveWorks(); renderLibrary() } }
                2 -> { scanningFolder = work.folder; scanFolder(work.folder) }
                3 -> android.app.AlertDialog.Builder(this).setMessage("确定从作品库删除“${work.title}”？").setPositiveButton("删除") { _, _ ->
                    works.removeAll { it.folder == work.folder }; saveWorks(); renderLibrary()
                }.setNegativeButton("取消", null).showStyled()
            }
        }.setNegativeButton("取消", null).showStyled()
    }

    private fun showEditWork(work: LocalWork) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)) }
        fun field(hint: String, value: String) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true); textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFF1ECF5.toInt())); cornerRadius = dp(12).toFloat() }
        }
        val title = field("作品名", work.title)
        var selectedCover: Uri? = work.cover ?: work.arts.firstOrNull()
        val coverButton = button("选择封面（当前：${selectedCover?.lastPathSegment ?: "默认插图"}）")
        coverButton.setOnClickListener {
            if (work.arts.isEmpty()) Toast.makeText(this, "这个作品没有插图可用作封面。", Toast.LENGTH_SHORT).show()
            else {
                val names = work.arts.mapIndexed { i, uri -> "${i + 1}. ${uri.lastPathSegment ?: "插图"}" }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle("选择封面").setItems(names) { _, index ->
                    selectedCover = work.arts[index]; coverButton.text = "选择封面（当前：${names[index]}）"
                }.setNegativeButton("取消", null).showStyled()
            }
        }

        val tagPrefs = getSharedPreferences("shengyu_library", MODE_PRIVATE)
        val catalog = linkedMapOf(
            "CV" to works.flatMap { it.cv.split(",", "，", "、") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList(),
            "社团" to works.flatMap { it.circle.split(",", "，", "、") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList(),
            "自定义" to works.flatMap { it.tags }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        )
        tagPrefs.getString("tag_catalog", null)?.let { saved ->
            try {
                val json = JSONObject(saved)
                listOf("CV", "社团", "自定义").forEach { group ->
                    val arr = json.optJSONArray(group)
                    if (arr != null) catalog[group] = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
                }
            } catch (_: Exception) {}
        }
        fun persistCatalog() {
            val json = JSONObject()
            catalog.forEach { (group, values) -> json.put(group, JSONArray(values.distinct())) }
            tagPrefs.edit().putString("tag_catalog", json.toString()).apply()
        }

        val cv = field("CV", work.cv)
        val circle = field("社团", work.circle)
        val tags = field("自定义标签（用逗号分隔）", work.tags.joinToString(", "))
        fun openTagPicker(group: String, target: EditText) {
            val available = catalog[group].orEmpty()
            val selected = target.text.toString().split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8)) }
            panel.addView(label("选择$group 标签", 17, themeColor(0xFF51435F.toInt()), true).apply { setPadding(0, 0, 0, dp(10)) })
            if (available.isEmpty()) {
                panel.addView(label("还没有已保存的$group 标签。可以先在作品信息中填写并保存。", 13, themeColor(0xFF8A7E95.toInt())))
            }
            val scroll = ScrollView(this)
            val chipArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(chipArea)
            panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(240)))
            fun redrawChips() {
                chipArea.removeAllViews()
                var row: LinearLayout? = null
                var rowWidth = 0
                val maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                available.forEach { tag ->
                    val chip = TextView(this).apply {
                        text = (if (tag in selected) "✓ " else "") + tag
                        textSize = 13f; setTextColor(if (tag in selected) themeColor(0xFF51435F.toInt()) else themeColor(0xFF756681.toInt()))
                        gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (tag in selected) themeColor(0xFFE2D5EE.toInt()) else themeColor(0xFFF1ECF5.toInt()))
                            cornerRadius = dp(18).toFloat()
                            setStroke(dp(1), if (tag in selected) themeColor(0xFFB7A1CB.toInt()) else themeColor(0xFFE7DDED.toInt()))
                        }
                        setOnClickListener { if (!selected.add(tag)) selected.remove(tag); redrawChips() }
                    }
                    chip.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.AT_MOST))
                    val width = chip.measuredWidth + dp(6)
                    if (row == null || rowWidth + width > maxWidth) {
                        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        chipArea.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                        rowWidth = 0
                    }
                    row!!.addView(chip, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
                    rowWidth += width
                }
            }
            redrawChips()
            android.app.AlertDialog.Builder(this).setView(panel)
                .setPositiveButton("确认") { _, _ -> target.setText(available.filter { it in selected }.joinToString(", ")) }
                .setNegativeButton("取消", null).showStyled()
        }
        fun tagRow(group: String, input: EditText): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(button("选择").apply {
                textSize = 12f
                setOnClickListener { openTagPicker(group, input) }
            }, LinearLayout.LayoutParams(dp(70), dp(44)).apply { marginStart = dp(8) })
            return row
        }

        box.addView(label("作品名称", 12, themeColor(0xFF8A7E95.toInt()), true))
        box.addView(title, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(coverButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })
        box.addView(label("CV", 12, themeColor(0xFF8A7E95.toInt()), true))
        box.addView(tagRow("CV", cv), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(label("社团", 12, themeColor(0xFF8A7E95.toInt()), true))
        box.addView(tagRow("社团", circle), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(label("自定义标签 · 逗号分隔", 12, themeColor(0xFF8A7E95.toInt()), true))
        box.addView(tagRow("自定义", tags), LinearLayout.LayoutParams(-1, dp(48)))

        android.app.AlertDialog.Builder(this).setTitle("编辑作品信息").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val i = works.indexOfFirst { it.folder == work.folder }
                if (i >= 0) {
                    val cvValues = cv.text.toString().split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() }
                    val circleValues = circle.text.toString().split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() }
                    val tagValues = tags.text.toString().split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() }
                    catalog["CV"] = (catalog["CV"].orEmpty() + cvValues).distinct().toMutableList()
                    catalog["社团"] = (catalog["社团"].orEmpty() + circleValues).distinct().toMutableList()
                    catalog["自定义"] = (catalog["自定义"].orEmpty() + tagValues).distinct().toMutableList()
                    persistCatalog()
                    val coverUri = selectedCover ?: work.arts.firstOrNull()
                    works[i] = works[i].copy(title = title.text.toString().trim().ifBlank { work.title }, cover = coverUri,
                        cv = cvValues.joinToString(", "), circle = circleValues.joinToString(", "), tags = tagValues)
                    saveWorks(); renderLibrary()
                }
            }.setNegativeButton("取消", null).showStyled()
    }

    private fun folderIdentity(uri: Uri): String {
        return try {
            val id = if (uri.pathSegments.contains("document")) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
            "${uri.authority}:$id"
        } catch (_: Exception) { uri.toString() }
    }

    private fun findFolderInTree(rootUri: Uri, targetUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(this, rootUri) ?: return null
        val target = folderIdentity(targetUri)
        fun find(dir: DocumentFile): DocumentFile? {
            if (folderIdentity(dir.uri) == target) return dir
            if (!dir.isDirectory) return null
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    val match = find(child)
                    if (match != null) return match
                }
            }
            return null
        }
        return find(root)
    }

    private fun scanFolder(uri: Uri) {
        saveLibraryScrollPosition()
        libraryScrollY = getSharedPreferences("shengyu_playback", MODE_PRIVATE).getLong("library_scroll_y", libraryScrollY.toLong()).toInt().coerceAtLeast(0)
        tracks.clear(); artworks.clear(); scripts.clear()
        val savedRoot = getSharedPreferences("shengyu_settings", MODE_PRIVATE).getString("library_root_uri", null)?.let { Uri.parse(it) }
        val folder = (if (savedRoot != null) findFolderInTree(savedRoot, uri) else null)
            ?: DocumentFile.fromTreeUri(this, uri)
        currentFolderName = folder?.name ?: "已选文件夹"
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { f ->
                if (f.isDirectory) walk(f) else {
                    val n = f.name ?: return@forEach
                    when (n.substringAfterLast('.', "").lowercase()) {
                        "mp3", "m4a", "aac", "wav", "ogg", "flac" -> tracks.add(LocalTrack(n, f.uri))
                        "jpg", "jpeg", "png", "webp", "gif" -> artworks.add(f.uri)
                        "txt", "lrc", "srt", "pdf" -> scripts.add(f.uri)
                    }
                }
            }
        }
        if (folder != null) walk(folder)
        showImportSelection()
    }

    private fun showImportSelection() {
        content.removeAllViews()
        content.addView(label("选择要导入的文件", 22, themeColor(0xFF3E3552.toInt()), true))
        content.addView(label("已扫描到的文件会列在这里；勾选后加入作品。", 13, themeColor(0xFF81798F.toInt())))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableListOf<Pair<CheckBox, Pair<String, Uri>>>()
        fun addGroup(title: String, items: List<Pair<String, Uri>>) {
            list.addView(label(title, 16, themeColor(0xFF5C526F.toInt()), true).apply { setPadding(0, dp(12), 0, dp(4)) })
            items.forEach { (name, uri) ->
                val cb = CheckBox(this).apply { text = name; isChecked = true; setTextColor(themeColor(0xFF3E3552.toInt())) }
                list.addView(cb); checks.add(cb to (name to uri))
            }
        }
        addGroup("音频", tracks.map { it.name to it.uri })
        addGroup("插画", artworks.map { (it.lastPathSegment ?: "插画") to it })
        addGroup("台本（TXT / LRC / SRT / PDF）", scripts.map { (it.lastPathSegment ?: "台本") to it })
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button("导入所选文件").apply {
            setOnClickListener {
                val chosen = checks.filter { it.first.isChecked }.map { it.second.second }.toSet()
                val chosenTracks = tracks.filter { chosen.contains(it.uri) }
                val chosenArts = artworks.filter { chosen.contains(it) }
                val chosenScripts = scripts.filter { chosen.contains(it) }
                val targetFolder = scanningFolder ?: folderUri
                libraryScrollY = getSharedPreferences("shengyu_playback", MODE_PRIVATE).getLong("library_scroll_y", libraryScrollY.toLong()).toInt().coerceAtLeast(0)
                if (targetFolder == null) {
                    Toast.makeText(this@MainActivity, "没有获取到文件夹，请重新选择。", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val previous = works.firstOrNull { folderIdentity(it.folder) == folderIdentity(targetFolder) }
                val work = LocalWork(currentFolderName, targetFolder, chosenTracks, chosenArts, chosenScripts,
                    previous?.cover ?: chosenArts.firstOrNull(), previous?.cv ?: "", previous?.circle ?: "", previous?.tags ?: emptyList(), previous?.favorite ?: false, previous?.importedAt ?: System.currentTimeMillis())
                val existing = works.indexOfFirst { folderIdentity(it.folder) == folderIdentity(targetFolder) }
                if (existing >= 0) works[existing] = work else works.add(work)
                saveWorks()
                scanningFolder = null
                currentTrack = -1; currentArt = 0; showScript = false
                Toast.makeText(this@MainActivity, "已加入作品库：${work.title}", Toast.LENGTH_SHORT).show()
                renderLibrary()
            }
        })
    }

    private fun showTrackList() {
        currentScreen = "tracklist"
        if (tracks.isEmpty() && scripts.isEmpty()) { Toast.makeText(this, "这个作品没有音轨或台本", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(this)
        val returnScreen = if (showScript) "script" else "home"
        var selectedTab = if (tracks.isNotEmpty()) 0 else 1
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10)); background = android.graphics.drawable.GradientDrawable().apply { setColor(themeColor(0xFFFCFBFD.toInt())); cornerRadius = dp(24).toFloat(); setStroke(dp(1), themeColor(0xFFECE7F0.toInt())) } }
        panel.addView(View(this).apply { setBackgroundColor(themeColor(0xFFB9AEC8.toInt())) }, LinearLayout.LayoutParams(dp(42), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshRows() {
            list.removeAllViews()
            if (selectedTab == 0) {
                tracks.forEachIndexed { i, track ->
                    val active = i == currentTrack
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(8))
                        minimumHeight = dp(70)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (active) themeColor(0xFFF1ECF8.toInt()) else android.graphics.Color.TRANSPARENT)
                            cornerRadius = dp(18).toFloat()
                        }
                    }
                    val title = TextView(this).apply {
                        text = (if (active) "▶  " else "") + "%02d".format(i + 1) + ".  " + track.name
                        textSize = 15f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(8), dp(6), dp(8))
                        setTextColor(if (active) themeColor(0xFF70558F.toInt()) else themeColor(0xFF494452.toInt()))
                        setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        // Keep the complete file name; let the row grow vertically instead of truncating it.
                        isSingleLine = false
                        maxLines = Int.MAX_VALUE
                        ellipsize = null
                        setHorizontallyScrolling(false)
                        breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setOnClickListener {
                            dialog.dismiss()
                            currentScreen = returnScreen
                            playTrack(i)
                        }
                    }
                    row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

                    val dragHandle = TextView(this).apply {
                        text = "⠿"
                        textSize = 22f
                        gravity = Gravity.CENTER
                        setTextColor(themeColor(0xFF9487A1.toInt()))
                        contentDescription = "长按拖动以调整音轨顺序"
                        setOnLongClickListener { v ->
                            v.startDragAndDrop(
                                android.content.ClipData.newPlainText("track-index", i.toString()),
                                View.DragShadowBuilder(v),
                                i,
                                0
                            )
                            true
                        }
                        setOnDragListener { _, e ->
                            if (e.action == android.view.DragEvent.ACTION_DROP) {
                                val from = e.localState as? Int
                                if (from != null && from in tracks.indices && from != i) {
                                    val moved = tracks.removeAt(from)
                                    tracks.add(i, moved)
                                    val wi = works.indexOfFirst { it.folder == folderUri }
                                    if (wi >= 0) {
                                        works[wi] = works[wi].copy(tracks = tracks.toList())
                                        saveWorks()
                                    }
                                    refreshRows()
                                }
                            }
                            true
                        }
                    }
                    // Center the handle vertically even when a long title makes the row taller.
                    row.addView(dragHandle, LinearLayout.LayoutParams(dp(42), dp(60)))
                    list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(2)
                    })
                }
                if (tracks.isEmpty()) list.addView(label("这个作品没有音轨", 13, themeColor(0xFF9A8EA4.toInt())).apply { setPadding(dp(12), dp(18), 0, dp(18)) })
            } else {
                scripts.forEachIndexed { i, uri ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(this).apply { text = "%02d".format(i + 1) + ".  " + (uri.lastPathSegment?.substringAfterLast("/") ?: ("台本 " + (i + 1))); textSize = 15f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(6), 0); setTextColor(themeColor(0xFF494452.toInt())); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setOnClickListener { selectedScriptIndex = i; dialog.dismiss(); currentScreen = returnScreen; showScript = true; loadScript() } }, LinearLayout.LayoutParams(0, dp(66), 1f))
                    row.addView(TextView(this).apply { text = "▤"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(themeColor(0xFF9993A7.toInt())); setOnClickListener { selectedScriptIndex = i; dialog.dismiss(); currentScreen = returnScreen; showScript = true; loadScript() } }, LinearLayout.LayoutParams(dp(42), dp(60)))
                    row.addView(TextView(this).apply { text = "⠿"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(themeColor(0xFF9487A1.toInt())); setOnLongClickListener { v -> v.startDragAndDrop(android.content.ClipData.newPlainText("script-index", i.toString()), View.DragShadowBuilder(v), i, 0); true }; setOnDragListener { _, e -> if (e.action == android.view.DragEvent.ACTION_DROP) { val from = e.localState as? Int; if (from != null && from in scripts.indices && from != i) { val moved = scripts.removeAt(from); scripts.add(i, moved); val wi = works.indexOfFirst { it.folder == folderUri }; if (wi >= 0) { works[wi] = works[wi].copy(scripts = scripts.toList()); saveWorks() }; getSharedPreferences("shengyu_script_order", MODE_PRIVATE).edit().putString("order_" + (folderUri?.toString()?.hashCode() ?: currentFolderName.hashCode()), scripts.joinToString("\n") { it.toString() }).apply(); refreshRows() } }; true } }, LinearLayout.LayoutParams(dp(42), dp(60)))
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(2) })
                }
                if (scripts.isEmpty()) list.addView(label("这个作品没有台本", 13, themeColor(0xFF9A8EA4.toInt())).apply { setPadding(dp(12), dp(18), 0, dp(18)) })
            }
        }
        fun tab(title: String, index: Int): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            box.addView(TextView(this).apply { text = title; textSize = 16f; gravity = Gravity.CENTER; setTextColor(if (selectedTab == index) themeColor(0xFF70558F.toInt()) else themeColor(0xFF8D8999.toInt())); setPadding(0, dp(8), 0, dp(10)) }, LinearLayout.LayoutParams(-1, dp(48)))
            box.addView(View(this).apply { setBackgroundColor(if (selectedTab == index) themeColor(0xFF70558F.toInt()) else themeColor(0x00FFFFFF.toInt())) }, LinearLayout.LayoutParams(-1, dp(3)))
            box.setOnClickListener { selectedTab = index; tabs.removeAllViews(); tabs.addView(tab("♫  音轨列表", 0), LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tab("▤  台本", 1), LinearLayout.LayoutParams(0, -2, 1f)); refreshRows() }
            return box
        }
        tabs.addView(tab("♫  音轨列表", 0), LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tab("▤  台本", 1), LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(tabs)
        panel.addView(View(this).apply { setBackgroundColor(themeColor(0xFFECE8F1.toInt())) }, LinearLayout.LayoutParams(-1, dp(1)))
        scroll.addView(list); panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); refreshRows()
        dialog.setContentView(panel); dialog.setOnCancelListener { currentScreen = returnScreen }; dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.68f).toInt()); dialog.window?.setGravity(Gravity.BOTTOM); dialog.window?.setDimAmount(0.28f)
    }

    private fun playTrack(index: Int, startAt: Int = 0) {
        if (index !in tracks.indices) return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, tracks[index].uri)
                setOnCompletionListener { if (index < tracks.lastIndex) playTrack(index + 1) else { updateMediaPlaybackState(false); updateMediaNotification() } }
                prepare()
                if (startAt > 0) seekTo(startAt.coerceAtMost(duration))
                start()
            }
            currentTrack = index
            resumePosition = startAt.coerceAtLeast(0)
            savePlaybackMemory()
            updateMediaMetadata()
            updateMediaPlaybackState(true)
            updateMediaNotification()
            if (showScript) loadScript() else renderHome()
        } catch (e: Exception) {
            Toast.makeText(this, "无法播放此音频格式：${e.message ?: ""}", Toast.LENGTH_LONG).show()
        }
    }

    private data class PdfPageInfo(
        val originalWidth: Int,
        val originalHeight: Int,
        var left: Float = 0f,
        var top: Float = 0f,
        var displayWidth: Float = 0f,
        var displayHeight: Float = 0f
    )

    private data class PdfRenderedPage(val quality: Float, val bitmap: Bitmap)

    /**
     * One zoom/pan surface for the whole document. PDF pages are rendered only when
     * visible (plus one page of prefetch), and old bitmaps are kept in a small LRU cache.
     */
    private inner class PdfReaderView(
        private val documentUri: Uri,
        private val initialScrollY: Int
    ) : View(this@MainActivity) {
        private val renderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val pageInfo = mutableListOf<PdfPageInfo>()
        private val renderedPages = LinkedHashMap<Int, PdfRenderedPage>(8, 0.75f, true)
        private val pendingRenders = mutableSetOf<String>()
        private val visiblePageIndices = linkedSetOf<Int>()
        private var renderer: PdfRenderer? = null
        private var descriptor: ParcelFileDescriptor? = null
        private var documentHeight = 0f
        private var zoom = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var cachedBytes = 0L
        private var disposed = false
        private var initialPositionApplied = false
        private var errorMessage: String? = null

        private val pagePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor(0xFFFFFFFF.toInt())
        }
        private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val messagePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor(0xFF81798F.toInt())
            textSize = dp(14).toFloat()
            textAlign = android.graphics.Paint.Align.CENTER
        }

        private val scaleDetector = ScaleGestureDetector(this@MainActivity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldZoom = zoom
                    val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                    if (newZoom == oldZoom) return true
                    val documentX = (detector.focusX - offsetX) / oldZoom
                    val documentY = (detector.focusY - offsetY) / oldZoom
                    zoom = newZoom
                    offsetX = detector.focusX - documentX * zoom
                    offsetY = detector.focusY - documentY * zoom
                    clampOffsets()
                    invalidate()
                    return true
                }
            })

        init {
            setBackgroundColor(themeColor(0xFFF8F5FA.toInt()))
            isClickable = true
            openDocument()
        }

        private fun openDocument() {
            renderExecutor.execute {
                var openedDescriptor: ParcelFileDescriptor? = null
                var openedRenderer: PdfRenderer? = null
                try {
                    val fd = contentResolver.openFileDescriptor(documentUri, "r")
                        ?: throw IllegalStateException("无法读取 PDF 文件")
                    openedDescriptor = fd
                    val pdf = PdfRenderer(fd)
                    openedRenderer = pdf
                    val metrics = mutableListOf<PdfPageInfo>()
                    for (i in 0 until pdf.pageCount) {
                        pdf.openPage(i).use { page ->
                            metrics.add(PdfPageInfo(page.width, page.height))
                        }
                    }
                    descriptor = fd
                    renderer = pdf
                    pdfRenderHandler.post {
                        if (disposed) return@post
                        pageInfo.clear()
                        pageInfo.addAll(metrics)
                        updatePageLayout()
                        invalidate()
                    }
                } catch (e: Exception) {
                    try { openedRenderer?.close() } catch (_: Exception) { }
                    try { openedDescriptor?.close() } catch (_: Exception) { }
                    pdfRenderHandler.post {
                        if (!disposed) {
                            errorMessage = "PDF 无法打开：" + (e.message ?: "未知错误")
                            invalidate()
                        }
                    }
                }
            }
        }

        private fun updatePageLayout() {
            if (width <= 0 || pageInfo.isEmpty()) return
            val oldDocumentY = (-offsetY / zoom).coerceAtLeast(0f)
            val sideMargin = dp(10).toFloat()
            val targetWidth = (width.toFloat() - sideMargin * 2f).coerceAtLeast(1f)
            var y = dp(12).toFloat()
            for (page in pageInfo) {
                page.left = sideMargin
                page.top = y
                page.displayWidth = targetWidth
                page.displayHeight = targetWidth * page.originalHeight.toFloat() / page.originalWidth.toFloat()
                y += page.displayHeight + dp(14)
            }
            documentHeight = y
            if (!initialPositionApplied) {
                offsetY = -initialScrollY.toFloat()
                initialPositionApplied = true
            } else {
                offsetY = -oldDocumentY * zoom
            }
            clampOffsets()
        }

        private fun clampOffsets() {
            if (width <= 0 || height <= 0) return
            val minX = width.toFloat() - width.toFloat() * zoom
            offsetX = offsetX.coerceIn(minX, 0f)
            val minY = height.toFloat() - documentHeight * zoom
            offsetY = if (minY > 0f) 0f else offsetY.coerceIn(minY, 0f)
        }

        // Rasterize only up to a bounded resolution. At high screen densities, rendering
        // every page at 3x can make a single ARGB_8888 page larger than the whole cache.
        private fun requiredQuality(): Float = zoom.coerceAtMost(1.65f)

        private fun effectivePageQuality(metric: PdfPageInfo, requested: Float): Float {
            val pageWidth = metric.displayWidth.coerceAtLeast(1f)
            val pageHeight = metric.displayHeight.coerceAtLeast(1f)
            val edgeLimit = 2400f / maxOf(pageWidth, pageHeight)
            val pixelLimit = kotlin.math.sqrt(4_000_000f / (pageWidth * pageHeight))
            return minOf(requested, edgeLimit, pixelLimit).coerceAtLeast(0.5f)
        }

        private fun requestPageRender(index: Int, requestedQuality: Float) {
            if (disposed || index !in pageInfo.indices) return
            val metric = pageInfo[index]
            val quality = effectivePageQuality(metric, requestedQuality)
            val cached = renderedPages[index]
            if (cached != null && cached.quality >= quality) return
            val qualityKey = (quality * 100f).toInt()
            val key = index.toString() + ":" + qualityKey.toString()
            if (!pendingRenders.add(key)) return

            val renderWidth = (metric.displayWidth * quality).toInt().coerceAtLeast(1)
            val renderHeight = (metric.displayHeight * quality).toInt().coerceAtLeast(1)
            try {
                renderExecutor.execute {
                    var bitmap: Bitmap? = null
                    var renderError: String? = null
                    try {
                        val pdf = renderer ?: throw IllegalStateException("PDF 尚未准备好")
                        pdf.openPage(index).use { page ->
                            val output = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                            bitmap = output
                            page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } catch (e: Exception) {
                        renderError = e.message ?: "页面渲染失败"
                        try { bitmap?.recycle() } catch (_: Exception) { }
                        bitmap = null
                    }
                    pdfRenderHandler.post {
                        pendingRenders.remove(key)
                        val completed = bitmap
                        if (disposed) {
                            try { completed?.recycle() } catch (_: Exception) { }
                            return@post
                        }
                        if (completed == null) {
                            errorMessage = "PDF 页面渲染失败：" + (renderError ?: "未知错误")
                            invalidate()
                            return@post
                        }

                        // An older/lower-resolution request may finish after a newer one.
                        // Never replace a better page image with a worse one.
                        val existing = renderedPages[index]
                        if (existing != null && existing.quality >= quality) {
                            completed.recycle()
                            invalidate()
                            return@post
                        }

                        val old = renderedPages.remove(index)
                        if (old != null) {
                            cachedBytes -= old.bitmap.byteCount.toLong()
                            if (old.bitmap !== completed) old.bitmap.recycle()
                        }
                        renderedPages[index] = PdfRenderedPage(quality, completed)
                        cachedBytes += completed.byteCount.toLong()
                        trimBitmapCache(visiblePageIndices)
                        invalidate()
                    }
                }
            } catch (_: Exception) {
                pendingRenders.remove(key)
            }
        }

        private fun trimBitmapCache(protectedPages: Set<Int> = emptySet()) {
            // Keep the pages currently on screen even when a zoom render completes.
            // Each bitmap is separately bounded to about 4 megapixels.
            val limit = 64L * 1024L * 1024L
            while (cachedBytes > limit && renderedPages.size > 1) {
                val eldest = renderedPages.entries.firstOrNull { it.key !in protectedPages } ?: break
                cachedBytes -= eldest.value.bitmap.byteCount.toLong()
                eldest.value.bitmap.recycle()
                renderedPages.remove(eldest.key)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updatePageLayout()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (pageInfo.isEmpty()) {
                val message = errorMessage ?: "正在加载 PDF…"
                canvas.drawText(message, width / 2f, height / 2f, messagePaint)
                return
            }

            val visibleTop = (-offsetY / zoom).coerceAtLeast(0f)
            val visibleBottom = (height.toFloat() - offsetY) / zoom
            val quality = requiredQuality()
            var firstVisible = -1
            var lastVisible = -1
            val visibleNow = linkedSetOf<Int>()

            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(zoom, zoom)
            for (i in pageInfo.indices) {
                val page = pageInfo[i]
                if (page.top + page.displayHeight < visibleTop || page.top > visibleBottom) continue
                if (firstVisible < 0) firstVisible = i
                lastVisible = i
                visibleNow.add(i)
                val rect = android.graphics.RectF(
                    page.left, page.top,
                    page.left + page.displayWidth, page.top + page.displayHeight
                )
                canvas.drawRect(rect, pagePaint)
                val cached = renderedPages[i]
                if (cached != null) {
                    canvas.drawBitmap(cached.bitmap, null, rect, bitmapPaint)
                }
                val pageQuality = effectivePageQuality(page, quality)
                if (cached == null || cached.quality < pageQuality) {
                    requestPageRender(i, quality)
                }
            }
            canvas.restore()
            visiblePageIndices.clear()
            visiblePageIndices.addAll(visibleNow)
            trimBitmapCache(visiblePageIndices)

            // Prefetch one page above and below the viewport for smoother continuous scrolling.
            if (firstVisible >= 0) {
                if (firstVisible > 0) requestPageRender(firstVisible - 1, quality)
                if (lastVisible < pageInfo.lastIndex) requestPageRender(lastVisible + 1, quality)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (zoom > 1.01f) offsetX += dx
                        offsetY += dy
                        clampOffsets()
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveScrollPosition()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }

        private var lastTouchX = 0f
        private var lastTouchY = 0f

        fun saveScrollPosition() {
            scriptScrollY = (-offsetY / zoom).toInt().coerceAtLeast(0)
        }

        override fun onDetachedFromWindow() {
            disposed = true
            try {
                renderExecutor.execute {
                    try { renderer?.close() } catch (_: Exception) { }
                    try { descriptor?.close() } catch (_: Exception) { }
                    renderer = null
                    descriptor = null
                }
            } catch (_: Exception) { }
            renderExecutor.shutdown()
            for (entry in renderedPages.values) {
                try { entry.bitmap.recycle() } catch (_: Exception) { }
            }
            renderedPages.clear()
            cachedBytes = 0L
            super.onDetachedFromWindow()
        }
    }

    private fun saveScriptScrollPosition() {
        if (currentScreen == "script") {
            pdfReaderView?.saveScrollPosition()
            (content.getChildAt(0) as? ScrollView)?.let { scriptScrollY = it.scrollY }
        }
    }

    private fun loadScript() {
        saveScriptScrollPosition()
        val previousScrollY = scriptScrollY
        currentScreen = "script"
        if (scripts.isEmpty()) {
            Toast.makeText(this, "这个作品没有导入台本。", Toast.LENGTH_SHORT).show()
            showScript = false
            renderHome()
            return
        }
        content.removeAllViews()
        pdfReaderView = null
        val uri = scripts.getOrNull(selectedScriptIndex.coerceIn(0, scripts.lastIndex)) ?: scripts.first()
        val isPdf = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase() == "pdf"
        if (isPdf) {
            val reader = PdfReaderView(uri, previousScrollY)
            pdfReaderView = reader
            content.addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this).apply {
                setOnScrollChangeListener { _, _, y, _, _ -> scriptScrollY = y }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(themeColor(0xFFFCFAFD.toInt()))
                    cornerRadius = dp(18).toFloat()
                }
            }
            scroll.addView(body)
            content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                currentScriptText = decodeScriptText(bytes)
            } catch (_: Exception) {
                currentScriptText = "暂时无法读取这份台本。"
            }
            body.addView(TextView(this).apply {
                text = currentScriptText
                textSize = 17.5f
                setTextColor(themeColor(0xFF51475B.toInt()))
                gravity = Gravity.START
                setLineSpacing(dp(9).toFloat(), 1.12f)
                setPadding(dp(20), dp(22), dp(20), dp(30))
                letterSpacing = 0.02f
            })
            scroll.post {
                scroll.scrollTo(0, previousScrollY)
                scriptScrollY = previousScrollY
            }
        }
        renderBottomControls()
    }

    private fun decodeScriptText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8)
        val suspicious = utf8.contains('\uFFFD') ||
            listOf("縺", "繧", "譁", "蜿", "繝", "莠", "螟", "譛").any { utf8.contains(it) }
        if (!suspicious) return utf8
        for (name in listOf("Shift_JIS", "windows-31j", "EUC-JP", "ISO-2022-JP")) {
            try {
                val decoded = String(bytes, java.nio.charset.Charset.forName(name))
                val japaneseChars = decoded.count { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
                val replacementChars = decoded.count { it == '\uFFFD' }
                if (japaneseChars > 0 && replacementChars == 0) return decoded
            } catch (_: Exception) { }
        }
        return utf8
    }

    private fun renderBottomControls() {
        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(4)) }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsedLabel = label("0:00", 11, themeColor(0xFF81798F.toInt())); durationLabel = label("0:00", 11, themeColor(0xFF81798F.toInt()))
        times.addView(elapsedLabel, LinearLayout.LayoutParams(0, -2, 1f)); durationLabel?.gravity = Gravity.END; times.addView(durationLabel); progressRow.addView(times)
        progressBar = SeekBar(this).apply { max = 1000; progress = 0; setPadding(dp(2), 0, dp(2), 0); progressTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF927DAA.toInt())); thumbTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF927DAA.toInt())); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFFE5DDEB.toInt())); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { val p = player ?: return; if (fromUser && p.duration > 0) p.seekTo((p.duration.toLong() * progress / 1000L).toInt()) }; override fun onStartTrackingTouch(seekBar: SeekBar?) {}; override fun onStopTrackingTouch(seekBar: SeekBar?) { savePlaybackMemory() } }) }
        progressRow.addView(progressBar); content.addView(progressRow); progressHandler.removeCallbacks(progressUpdater); progressHandler.post(progressUpdater)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, dp(2)) }
        fun addControl(title: String, action: () -> Unit, primary: Boolean = false) { val control = TextView(this).apply { text = title; textSize = if (primary) 24f else 19f; gravity = Gravity.CENTER; setTextColor(if (primary) themeColor(0xFFFFFFFF.toInt()) else themeColor(0xFF6D5D7D.toInt())); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(if (primary) 28 else 18).toFloat(); setColor(if (primary) themeColor(0xFF927DAA.toInt()) else themeColor(0xFFEDE5F2.toInt())) }; if (primary) elevation = dp(3).toFloat(); setOnClickListener { action() } }; row.addView(control, LinearLayout.LayoutParams(0, dp(if (primary) 56 else 48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        addControl("☷", { showTrackList() }); addControl("↶", { player?.let { p -> val seconds = getSharedPreferences("shengyu_settings", MODE_PRIVATE).getInt("seek_back_seconds", 15); p.seekTo((p.currentPosition - seconds * 1000).coerceAtLeast(0)) } })
        addControl(if (player?.isPlaying == true) "Ⅱ" else "▶", { if (player?.isPlaying == true) { player?.pause(); updateMediaPlaybackState(false); updateMediaNotification(); if (showScript) loadScript() else renderHome() } else if (currentTrack >= 0) { player?.start(); updateMediaPlaybackState(true); updateMediaNotification(); if (showScript) loadScript() else renderHome() } else if (tracks.isNotEmpty()) playTrack(0) }, true)
        addControl("↷", { player?.let { p -> val seconds = getSharedPreferences("shengyu_settings", MODE_PRIVATE).getInt("seek_forward_seconds", 15); p.seekTo((p.currentPosition + seconds * 1000).coerceAtMost(p.duration)) } })
        addControl("▤", { if (showScript) { showScript = false; renderHome() } else if (scripts.isNotEmpty()) { showScript = true; loadScript() } else Toast.makeText(this, "这个作品没有台本。", Toast.LENGTH_SHORT).show() })
        content.addView(row)
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                try {
                    val duration = p.duration.coerceAtLeast(0)
                    val position = p.currentPosition.coerceAtLeast(0).coerceAtMost(duration)
                    if (progressBar?.isPressed != true) progressBar?.progress = if (duration > 0) (position.toLong() * 1000 / duration).toInt() else 0
                    elapsedLabel?.text = formatTime(position)
                    durationLabel?.text = formatTime(duration)
                    resumePosition = position
                    savePlaybackMemory()
                } catch (_: Exception) { }
            } else {
                elapsedLabel?.text = "0:00"; durationLabel?.text = "0:00"; progressBar?.progress = 0
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    private fun formatTime(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun playbackMemoryKey(uri: Uri) = "playback_${uri.toString().hashCode()}"

    private fun savePlaybackMemory() {
        val uri = folderUri ?: return
        val index = currentTrack
        if (index < 0) return
        val position = try { player?.currentPosition ?: resumePosition } catch (_: Exception) { resumePosition }
        val key = playbackMemoryKey(uri)
        getSharedPreferences("shengyu_playback", MODE_PRIVATE).edit()
            .putInt(key + "_track", index)
            .putInt(key + "_position", position.coerceAtLeast(0))
            .putLong("lastPlayed_" + uri.toString().hashCode(), System.currentTimeMillis())
            .apply()
    }

    private fun readPlaybackMemory(uri: Uri): Pair<Int, Int> {
        val prefs = getSharedPreferences("shengyu_playback", MODE_PRIVATE)
        val key = playbackMemoryKey(uri)
        return prefs.getInt(key + "_track", -1) to prefs.getInt(key + "_position", 0)
    }

    private fun showArtPreview(startIndex: Int) {
        if (artworks.isEmpty()) return
        var index = startIndex.coerceIn(0, artworks.lastIndex)
        val dialog = Dialog(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(themeColor(0xFF17151D.toInt())) }
        val viewer = FrameLayout(this)
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX }
        viewer.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        val matrix = android.graphics.Matrix()
        val rect = android.graphics.RectF()
        var baseScale = 1f
        var currentScale = 1f
        var gridVisible = false
        var gridHeader: View? = null
        var gridClose: View? = null

        fun clampMatrix() {
            val drawable = image.drawable ?: return
            rect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            matrix.mapRect(rect)
            val vw = image.width.toFloat()
            val vh = image.height.toFloat()
            var dx = 0f
            var dy = 0f
            if (rect.width() <= vw) dx = vw / 2f - rect.centerX()
            else if (rect.left > 0f) dx = -rect.left else if (rect.right < vw) dx = vw - rect.right
            if (rect.height() <= vh) dy = vh / 2f - rect.centerY()
            else if (rect.top > 0f) dy = -rect.top else if (rect.bottom < vh) dy = vh - rect.bottom
            matrix.postTranslate(dx, dy)
            image.imageMatrix = matrix
        }

        fun displayImage() {
            try {
                contentResolver.openInputStream(artworks[index])?.use {
                    image.setImageBitmap(BitmapFactory.decodeStream(it))
                }
            } catch (_: Exception) { image.setImageDrawable(null) }
            image.post {
                val drawable = image.drawable ?: return@post
                val vw = image.width.toFloat()
                val vh = image.height.toFloat()
                if (vw <= 0f || vh <= 0f) return@post
                baseScale = minOf(vw / drawable.intrinsicWidth, vh / drawable.intrinsicHeight)
                currentScale = 1f
                matrix.reset()
                matrix.setScale(baseScale, baseScale)
                matrix.postTranslate(
                    (vw - drawable.intrinsicWidth * baseScale) / 2f,
                    (vh - drawable.intrinsicHeight * baseScale) / 2f
                )
                image.imageMatrix = matrix
            }
        }

        fun showGrid() {
            gridVisible = true
            viewer.visibility = View.GONE
            val gridPage = ScrollView(this)
            val grid = android.widget.GridLayout(this).apply {
                columnCount = 3
                setPadding(dp(8), dp(52), dp(8), dp(12))
            }
            artworks.forEachIndexed { artIndex, uri ->
                val cell = FrameLayout(this)
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(themeColor(0xFF292530.toInt()))
                    if (artIndex == index) {
                        foreground = android.graphics.drawable.GradientDrawable().apply {
                            setColor(themeColor(0x22000000.toInt()))
                            setStroke(dp(3), themeColor(0xFFD8C5F2.toInt()))
                            cornerRadius = dp(10).toFloat()
                        }
                    }
                }
                cell.addView(thumb, FrameLayout.LayoutParams(-1, dp(124)))
                Thread {
                    val bitmap = try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                        }
                    } catch (_: Exception) { null }
                    runOnUiThread { if (bitmap != null && !dialog.isShowing.not()) thumb.setImageBitmap(bitmap) }
                }.start()
                cell.setOnClickListener {
                    index = artIndex
                    gridVisible = false
                    frame.removeView(gridPage)
                    gridHeader?.let { frame.removeView(it) }
                    gridClose?.let { frame.removeView(it) }
                    gridHeader = null
                    gridClose = null
                    viewer.visibility = View.VISIBLE
                    displayImage()
                }
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(132)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                grid.addView(cell, lp)
            }
            gridPage.addView(grid, FrameLayout.LayoutParams(-1, -2))
            frame.addView(gridPage, FrameLayout.LayoutParams(-1, -1))
            val header = TextView(this).apply {
                text = "插图总览  ·  ${artworks.size} 张"
                textSize = 16f
                setTextColor(themeColor(0xFFF8F5FA.toInt()))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                setBackgroundColor(themeColor(0xFF24212B.toInt()))
            }
            gridHeader = header
            frame.addView(header, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP))
            val closeGrid = TextView(this).apply {
                text = "×"
                textSize = 30f
                setTextColor(themeColor(0xFFF8F5FA.toInt()))
                gravity = Gravity.CENTER
                setOnClickListener {
                    gridVisible = false
                    frame.removeView(gridPage)
                    gridHeader?.let { frame.removeView(it) }
                    gridClose?.let { frame.removeView(it) }
                    gridHeader = null
                    gridClose = null
                    viewer.visibility = View.VISIBLE
                }
            }
            gridClose = closeGrid
            frame.addView(closeGrid, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        }

        val gridButton = TextView(this).apply {
            text = "▦"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(themeColor(0xFFF8F3FF.toInt()))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColor(0xE62B2733.toInt()))
                setStroke(dp(1), themeColor(0x88D8C5F2.toInt()))
            }
            elevation = dp(8).toFloat()
            contentDescription = "插图网格预览"
            setOnClickListener { if (!gridVisible) showGrid() }
        }
        viewer.addView(gridButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20)
            marginEnd = dp(16)
        })

        var lastX = 0f
        var lastY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                matrix.postScale(next / currentScale, next / currentScale, detector.focusX, detector.focusY)
                currentScale = next
                clampMatrix()
                moved = true
                return true
            }
        })
        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y; startX = event.x; startY = event.y; moved = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > 1f) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        clampMatrix()
                        moved = moved || kotlin.math.abs(event.x - lastX) > 2f || kotlin.math.abs(event.y - lastY) > 2f
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!moved && kotlin.math.abs(dx) < dp(12) && kotlin.math.abs(dy) < dp(12) && currentScale <= 1.01f) {
                        dialog.dismiss()
                    } else if (currentScale <= 1.01f && kotlin.math.abs(dx) > dp(60) && kotlin.math.abs(dx) > kotlin.math.abs(dy) && artworks.size > 1) {
                        index = if (dx < 0) (index + 1) % artworks.size else (index - 1 + artworks.size) % artworks.size
                        displayImage()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> { lastX = event.x; lastY = event.y }
            }
            true
        }
        displayImage()
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            currentArt = index
            renderHome()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.setLayout(-1, -1)
    }

    private fun showVolume() {
        val seek = SeekBar(this).apply { max = 100; progress = 75 }
        AlertDialogBuilder().show(seek)
    }

    private inner class AlertDialogBuilder {
        fun show(seek: SeekBar) {
            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            box.addView(label("音量", 16, themeColor(0xFF3E3552.toInt()), true)); box.addView(seek)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { player?.setVolume(p / 100f, p / 100f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            android.app.AlertDialog.Builder(this@MainActivity).setView(box).setPositiveButton("完成", null).showStyled()
        }
    }

    private fun showSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(arrayOf("常规", "播放", "外观", "关于")) { _, which ->
                when (which) {
                    0 -> showGeneralSettings()
                    1 -> showPlaybackSettings()
                    2 -> showAppearanceSettings()
                    3 -> showAbout()
                }
            }
            .setNegativeButton("关闭", null).showStyled()
    }

    private fun showGeneralSettings() {
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        val root = prefs.getString("library_root_uri", null)?.let { Uri.parse(it) }
        val mode = prefs.getString("library_import_mode", "manual") ?: "manual"
        val rootName = root?.let { DocumentFile.fromTreeUri(this, it)?.name } ?: "尚未设置"
        android.app.AlertDialog.Builder(this).setTitle("常规")
            .setItems(arrayOf(
                "作品库目录：$rootName",
                "导入方式：${if (mode == "auto") "自动导入新作品" else "扫描后手动选择"}",
                "扫描作品库",
                "导出标签",
                "导入标签"
            )) { dialog, which ->
                when (which) {
                    0 -> { dialog.dismiss(); pickingLibraryRoot = true; pickFolder.launch(root) }
                    1 -> {
                        android.app.AlertDialog.Builder(this).setTitle("导入方式")
                            .setSingleChoiceItems(arrayOf("自动导入新作品", "扫描后手动选择"), if (mode == "auto") 0 else 1) { d, selected ->
                                prefs.edit().putString("library_import_mode", if (selected == 0) "auto" else "manual").apply()
                                d.dismiss(); showGeneralSettings()
                            }.setNegativeButton("返回", null).showStyled()
                    }
                    2 -> { dialog.dismiss(); if (root == null) { pickingLibraryRoot = true; pickFolder.launch(null) } else scanLibraryRoot(root) }
                    3 -> { dialog.dismiss(); exportTagFile.launch("shengyu-tags.json") }
                    4 -> { dialog.dismiss(); importTagFile.launch(arrayOf("application/json", "text/json", "text/plain")) }
                }
            }.setNegativeButton("关闭", null).showStyled()
    }

    private data class ScannedWork(val folder: DocumentFile, val tracks: List<LocalTrack>, val arts: List<Uri>, val scripts: List<Uri>)

    private fun scanLibraryRoot(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) { Toast.makeText(this, "无法读取目录，请重新选择。", Toast.LENGTH_LONG).show(); return }
        fun collect(folder: DocumentFile): ScannedWork {
            val ts = mutableListOf<LocalTrack>(); val arts = mutableListOf<Uri>(); val scripts = mutableListOf<Uri>()
            fun walk(dir: DocumentFile) { dir.listFiles().forEach { file ->
                if (file.isDirectory) walk(file) else { val name = file.name ?: return@forEach
                    when (name.substringAfterLast(".", "").lowercase()) {
                        "mp3", "m4a", "aac", "wav", "ogg", "flac" -> ts.add(LocalTrack(name, file.uri))
                        "jpg", "jpeg", "png", "webp", "gif" -> arts.add(file.uri)
                        "txt", "lrc", "srt", "pdf" -> scripts.add(file.uri)
                    }
                }
            } }
            walk(folder); return ScannedWork(folder, ts, arts, scripts)
        }
        val discovered = root.listFiles().filter { it.isDirectory }.map { collect(it) }.filter { it.tracks.isNotEmpty() }
        fun existing(item: ScannedWork) = works.firstOrNull { folderIdentity(it.folder) == folderIdentity(item.folder.uri) }
        fun mediaSignature(uri: Uri): String {
            val file = DocumentFile.fromSingleUri(this, uri)
            val id = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { uri.lastPathSegment ?: uri.toString() }
            return "$id|${file?.name ?: ""}|${file?.length() ?: -1L}|${file?.lastModified() ?: -1L}"
        }
        fun changed(item: ScannedWork): Boolean {
            val old = existing(item) ?: return false
            return old.tracks.map { mediaSignature(it.uri) }.sorted() != item.tracks.map { mediaSignature(it.uri) }.sorted() ||
                old.arts.map { mediaSignature(it) }.sorted() != item.arts.map { mediaSignature(it) }.sorted() ||
                old.scripts.map { mediaSignature(it) }.sorted() != item.scripts.map { mediaSignature(it) }.sorted()
        }
        fun importScannedWork(item: ScannedWork) {
            val old = existing(item)
            val index = works.indexOfFirst { folderIdentity(it.folder) == folderIdentity(item.folder.uri) }
            val w = LocalWork(item.folder.name ?: "未命名作品", item.folder.uri, item.tracks, item.arts, item.scripts, old?.cover ?: item.arts.firstOrNull(), old?.cv ?: "", old?.circle ?: "", old?.tags ?: emptyList(), old?.favorite ?: false, old?.importedAt ?: System.currentTimeMillis())
            if (index >= 0) works[index] = w else works.add(w)
        }
        val newItems = discovered.filter { existing(it) == null }; val changedItems = discovered.filter { changed(it) }
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        if ((prefs.getString("library_import_mode", "manual") ?: "manual") == "auto") {
            (newItems + changedItems).forEach { importScannedWork(it) }; saveWorks()
            Toast.makeText(this, "扫描完成：新增 ${newItems.size} 部，更新 ${changedItems.size} 部", Toast.LENGTH_LONG).show(); renderLibrary(); return
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        panel.addView(label("发现 ${discovered.size} 部作品 · 新增 ${newItems.size} 部 · 有变化 ${changedItems.size} 部", 14, themeColor(0xFF81798F.toInt())))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = mutableListOf<Pair<CheckBox, ScannedWork>>()
        discovered.forEach { item -> val old = existing(item); val isChanged = changed(item); val status = if (old == null) "新作品" else if (isChanged) "文件有变化" else "已导入"
            val cb = CheckBox(this).apply { text = "${item.folder.name ?: "未命名作品"} · $status"; isChecked = old == null || isChanged; isEnabled = old == null || isChanged; setTextColor(themeColor(0xFF3E3552.toInt())) }
            list.addView(cb); rows.add(cb to item)
        }
        scroll.addView(list); panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(350)))
        android.app.AlertDialog.Builder(this).setTitle("扫描作品库").setView(panel)
            .setPositiveButton("导入所选") { _, _ -> rows.filter { it.first.isChecked }.forEach { importScannedWork(it.second) }; saveWorks(); renderLibrary(); Toast.makeText(this, "作品库已更新", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("取消", null).showStyled()
    }
    private fun showPlaybackSettings() {
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        val options = arrayOf("5 秒", "10 秒", "15 秒", "20 秒", "30 秒", "60 秒")
        val values = intArrayOf(5, 10, 15, 20, 30, 60)
        fun chooseSeconds(key: String, title: String) {
            val current = prefs.getInt(key, 15)
            val selected = values.indexOf(current).coerceAtLeast(0)
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, selected) { dialog, which ->
                    prefs.edit().putInt(key, values[which]).apply()
                    dialog.dismiss()
                    showPlaybackSettings()
                }
                .setNegativeButton("返回", null)
                .showStyled()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("播放")
            .setItems(arrayOf(
                "回退按键：${prefs.getInt("seek_back_seconds", 15)} 秒",
                "前进按键：${prefs.getInt("seek_forward_seconds", 15)} 秒"
            )) { _, which ->
                if (which == 0) chooseSeconds("seek_back_seconds", "回退多少秒")
                else chooseSeconds("seek_forward_seconds", "前进多少秒")
            }
            .setNegativeButton("完成", null)
            .showStyled()
    }

    private fun showAppearanceSettings() {
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(8))
        }

        panel.addView(label("主题颜色", 13, themeColor(0xFF81798F.toInt()), true).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        var appearanceDialog: android.app.AlertDialog? = null
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        themeOptions.forEach { option ->
            val selected = option.id == selectedThemeId()
            val swatch = TextView(this).apply {
                text = if (selected) "✓" else ""
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                contentDescription = option.name + if (selected) "，当前主题" else "主题"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(themeColorFor(0xFF806B9A.toInt(), option.id))
                    setStroke(
                        dp(if (selected) 2 else 1),
                        if (selected) themeColorFor(0xFF51435F.toInt(), option.id)
                        else themeColorFor(0xFFEAE3F0.toInt(), option.id)
                    )
                }
                setOnClickListener {
                    prefs.edit().putString("app_theme", option.id).apply()
                    appearanceDialog?.dismiss()
                    refreshThemeScreen()
                    showAppearanceSettings()
                }
            }
            swatches.addView(swatch, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                weight = 1f
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        panel.addView(swatches, LinearLayout.LayoutParams(-1, dp(48)))

        val currentName = themeOptions.firstOrNull { it.id == selectedThemeId() }?.name ?: "薰衣草紫"
        panel.addView(label("当前：$currentName", 11, themeColor(0xFF9A8EA4.toInt())).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(12))
        })

        appearanceDialog = android.app.AlertDialog.Builder(this)
            .setTitle("外观")
            .setView(panel)
            .setPositiveButton("完成", null)
            .showStyled()
    }

    private fun showAbout() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val guide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String, body: String) {
            guide.addView(label(title, 15, themeColor(0xFF51435F.toInt()), true).apply {
                setPadding(0, dp(14), 0, dp(5))
            })
            guide.addView(TextView(this).apply {
                text = body
                textSize = 13.5f
                setTextColor(themeColor(0xFF625A6B.toInt()))
                setLineSpacing(dp(4).toFloat(), 1.04f)
                setPadding(0, 0, 0, dp(4))
            })
        }

        guide.addView(label("声屿 ASMR", 21, themeColor(0xFF51435F.toInt()), true))
        guide.addView(label("本地音频、插画与台本播放器 · 详细使用说明", 12, themeColor(0xFF91869B.toInt())).apply {
            setPadding(0, dp(3), 0, dp(6))
        })

        section("一、作品库与首次导入",
            "声屿以“作品”为单位管理本地内容。一个作品文件夹可以同时包含多条音轨、多张插画和多份台本。\n\n" +
            "单个作品导入：在作品库右下角点击“+”，选择作品文件夹。扫描后会列出音频、插画和台本，勾选想加入的文件，再点击“导入所选文件”。\n\n" +
            "支持的音频格式：MP3、M4A、AAC、WAV、OGG、FLAC。插画格式：JPG、JPEG、PNG、WEBP、GIF。台本格式：TXT、LRC、SRT、PDF。"
        )

        section("二、批量扫描作品库",
            "打开“设置 → 常规”，先设置“作品库目录”。建议把每部作品分别放在这个目录下的一个子文件夹中；扫描时会逐个检查子文件夹，并递归读取其中的音频、插画和台本。\n\n" +
            "自动导入新作品：扫描时自动加入新作品，并更新检测到文件变化的已导入作品。\n" +
            "扫描后手动选择：扫描结果会标记为新作品、文件有变化或已导入；新增和有变化的作品默认勾选，未变化的作品不会重复导入。检查后点击“导入所选”。\n\n" +
            "单个文件夹导入和作品菜单里的“重新扫描并更新”仍可单独使用。更新时会尽量保留已填写的作品信息、收藏状态和标签。"
        )

        section("三、作品库分类与作品管理",
            "点击作品库顶部的分类名称，可以切换“全部作品”“最近播放”“导入时间”“收藏”和“标签”。选择“标签”后，可以组合选择标签并应用筛选。\n\n" +
            "点击作品右侧的三点菜单，可以编辑作品名称、封面、CV、社团和自定义标签，也可以收藏/取消收藏、重新扫描并更新，或从作品库删除。\n\n" +
            "“从作品库删除”只移除 App 中保存的作品条目，不会删除原文件夹里的音频、插画或台本。"
        )

        section("四、标签管理与备份",
            "标签分为 CV、社团、自定义三组。进入作品库分类中的“标签”，点击“编辑”后可以添加标签；编辑状态下长按标签，可以调整顺序或从标签面板目录中删除。\n\n" +
            "从标签面板目录删除标签，不会清除作品资料里已经保存的 CV、社团或自定义标签。\n\n" +
            "设置 → 常规 → 导出标签：会生成 JSON 标签文件，可保存到手机或其他位置。导入标签：选择之前导出的 JSON 文件后，会把标签合并到当前目录；重复项自动跳过，不会覆盖现有标签，也不会修改作品资料。\n\n" +
            "标签备份只包含三组标签目录，不包含作品、音频文件、插画、台本或每部作品的其他资料。"
        )

        section("五、播放与音轨列表",
            "在作品库点击作品即可进入播放页。顶部显示作品名称、音轨/插画/台本数量和当前音轨名称。\n\n" +
            "底部从左到右依次是：音轨列表、回退、播放/暂停、前进、台本。进度条可以拖动定位，并显示当前时间和总时长。回退和前进秒数可在“设置 → 播放”中选择 5、10、15、20、30 或 60 秒。\n\n" +
            "点击“音轨列表”可以查看音轨和台本。点击音轨可直接播放；长按音轨右侧的拖动把手并拖到目标位置，可以调整音轨顺序，调整结果会保存。当前播放音轨会高亮显示，长标题会自动换行显示。"
        )

        section("六、插画与台本",
            "轻触播放页中的插画可以打开预览。预览中支持放大、缩小和拖动查看；作品包含多张插画时，可以左右滑动切换，也可以使用缩略图网格选择。\n\n" +
            "点击底部“台本”进入阅读页面。支持 TXT、LRC、SRT 和 PDF；如果作品导入了多份台本，可以通过音轨列表面板的“台本”标签选择。阅读台本时，底部播放控制仍可使用，音频可以继续播放。"
        )

        section("七、定时停止与外观",
            "播放页顶部的时钟图标用于设置定时停止，可设置停止时间，也可以设为默认时长；定时结束后 App 会暂停播放。\n\n" +
            "在“设置 → 外观”中可以切换六套浅色主题：薰衣草紫、樱花粉、天空蓝、薄荷绿、抹茶绿和奶油杏。选择后立即生效，并会记住上次选择。"
        )

        section("八、数据与使用建议",
            "作品列表、标签目录、主题选择和播放进度保存在本机应用数据中。导入作品时，App 使用所选文件夹的访问授权读取文件；请不要随意移动或删除原始文件，否则对应内容可能无法打开。\n\n" +
            "更换手机或重装 App 前，建议另外备份原始作品文件夹。标签 JSON 只能备份标签目录，不能代替作品文件和完整作品库备份。"
        )

        scroll.addView(guide)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("关于声屿 · 使用说明")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .showStyled()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }
    private fun showSleepTimer() {
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        val defaultEnabled = prefs.getBoolean("sleep_timer_default_enabled", false)
        val defaultMinutes = prefs.getInt("sleep_timer_default_minutes", 30)
        if (defaultEnabled && defaultMinutes > 0) {
            startSleepTimer(defaultMinutes)
            return
        }
        showSleepTimerPicker()
    }

    private fun showSleepTimerPicker() {
        val prefs = getSharedPreferences("shengyu_settings", MODE_PRIVATE)
        val defaultEnabled = prefs.getBoolean("sleep_timer_default_enabled", false)
        val initialMinutes = if (sleepTimerEndAt > System.currentTimeMillis()) {
            ((sleepTimerEndAt - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceIn(0, 60)
        } else {
            prefs.getInt("sleep_timer_default_minutes", 30).coerceIn(0, 60)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val valueLabel = TextView(this).apply {
            text = if (initialMinutes == 0) "立即停止" else "$initialMinutes 分钟后停止"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(themeColor(0xFF51435F.toInt()))
            setPadding(0, dp(12), 0, dp(18))
        }
        panel.addView(valueLabel, LinearLayout.LayoutParams(-1, -2))

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun timerStepButton(glyph: String, description: String) = TextView(this).apply {
            text = glyph
            textSize = 28f
            gravity = Gravity.CENTER
            contentDescription = description
            setTextColor(themeColor(0xFF806B9A.toInt()))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColor(0xFFEDE5F2.toInt()))
                cornerRadius = dp(14).toFloat()
            }
        }
        val minus = timerStepButton("−", "减少一分钟")
        val plus = timerStepButton("+", "增加一分钟")
        val seek = android.widget.SeekBar(this).apply {
            max = 60
            progress = initialMinutes
            progressTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF9A82B3.toInt()))
            thumbTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF9A82B3.toInt()))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFFE8DFF0.toInt()))
            contentDescription = "定时分钟数，0 到 60 分钟"
        }
        sliderRow.addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
        sliderRow.addView(seek, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(10); marginEnd = dp(10)
        })
        sliderRow.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(sliderRow)

        val scale = TextView(this).apply {
            text = "0 分钟                                                60 分钟"
            textSize = 12f
            setTextColor(themeColor(0xFF9B91A4.toInt()))
            setPadding(0, dp(4), 0, dp(18))
        }
        panel.addView(scale)

        val defaultSwitch = android.widget.CheckBox(this).apply {
            text = "设为默认"
            textSize = 15f
            setTextColor(themeColor(0xFF62536F.toInt()))
            isChecked = defaultEnabled
            buttonTintList = android.content.res.ColorStateList.valueOf(themeColor(0xFF9A82B3.toInt()))
        }
        panel.addView(defaultSwitch)

        val status = TextView(this).apply {
            text = if (sleepTimerEndAt > System.currentTimeMillis()) "当前定时剩余：${formatTimerRemaining()}" else "拖动进度条或使用 + / − 调整时间"
            textSize = 12f
            setTextColor(themeColor(0xFF9B91A4.toInt()))
            setPadding(0, dp(8), 0, dp(4))
        }
        panel.addView(status)

        fun updateValue(value: Int) {
            seek.progress = value.coerceIn(0, 60)
            valueLabel.text = if (seek.progress == 0) "立即停止" else "${seek.progress} 分钟后停止"
        }
        minus.setOnClickListener { updateValue(seek.progress - 1) }
        plus.setOnClickListener { updateValue(seek.progress + 1) }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = if (progress == 0) "立即停止" else "$progress 分钟后停止"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("定时停止")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setNeutralButton(if (sleepTimerEndAt > System.currentTimeMillis()) "取消定时" else "关闭", null)
            .setPositiveButton(if (sleepTimerEndAt > System.currentTimeMillis()) "重新计时" else "开始计时", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val minutes = seek.progress
                prefs.edit()
                    .putBoolean("sleep_timer_default_enabled", defaultSwitch.isChecked && minutes > 0)
                    .putInt("sleep_timer_default_minutes", minutes)
                    .apply()
                if (minutes == 0) {
                    cancelSleepTimer()
                    Toast.makeText(this, "已取消定时停止", Toast.LENGTH_SHORT).show()
                } else {
                    startSleepTimer(minutes)
                }
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                cancelSleepTimer()
                Toast.makeText(this, "已取消定时停止", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            val surface = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColor(0xFFFAF8FC.toInt()))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), themeColor(0xFFEAE3F0.toInt()))
            }
            dialog.window?.setBackgroundDrawable(surface)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(themeColor(0xFF806B9A.toInt()))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(themeColor(0xFF806B9A.toInt()))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(themeColor(0xFF806B9A.toInt()))
        }
        dialog.show()
    }

    private fun formatTimerRemaining(): String {
        val seconds = ((sleepTimerEndAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L).toInt()
        return "${seconds / 60} 分 ${seconds % 60} 秒"
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerEndAt = System.currentTimeMillis() + minutes * 60_000L
        val task = object : Runnable {
            override fun run() {
                val remaining = sleepTimerEndAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    sleepTimerRunnable = null
                    sleepTimerEndAt = 0L
                    try { player?.pause() } catch (_: Exception) { }
                    savePlaybackMemory()
                    if (currentScreen == "home" || currentScreen == "script" || currentScreen == "tracklist") {
                        if (showScript) loadScript() else renderHome()
                    }
                    Toast.makeText(this@MainActivity, "定时结束，已停止播放", Toast.LENGTH_LONG).show()
                } else {
                    timerHandler.postDelayed(this, minOf(remaining, 1000L))
                }
            }
        }
        sleepTimerRunnable = task
        timerHandler.post(task)
        Toast.makeText(this, "已设置 ${minutes} 分钟后停止", Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() {
        sleepTimerRunnable?.let { timerHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerEndAt = 0L
    }

    private fun android.app.AlertDialog.Builder.showStyled(): android.app.AlertDialog {
        val dialog = create()
        dialog.setOnShowListener {
            val surface = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColor(0xFFFAF8FC.toInt()))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), themeColor(0xFFEAE3F0.toInt()))
            }
            dialog.window?.setBackgroundDrawable(surface)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(themeColor(0xFF806B9A.toInt()))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(themeColor(0xFF806B9A.toInt()))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(themeColor(0xFF806B9A.toInt()))
            dialog.listView?.apply {
                setBackgroundColor(themeColor(0xFFFAF8FC.toInt()))
                divider = null
                dividerHeight = 0
            }
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            val titleContainer = titleView?.parent as? android.view.ViewGroup
            val topPanel = titleContainer?.parent as? android.view.ViewGroup
            if (titleContainer != null && topPanel != null && titleContainer.parent === topPanel) {
                val divider = View(this@MainActivity).apply {
                    setBackgroundColor(themeColor(0xFFEAE3F0.toInt()))
                }
                val dividerIndex = topPanel.indexOfChild(titleContainer) + 1
                if (dividerIndex in 0..topPanel.childCount) {
                    topPanel.addView(divider, dividerIndex, android.view.ViewGroup.LayoutParams(-1, dp(1)))
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false
        setTextColor(themeColor(0xFF62536F.toInt()))
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(themeColor(0xFFEDE5F2.toInt()))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), themeColor(0xFFE4D9EB.toInt()))
        }
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        elevation = dp(1).toFloat()
        stateListAnimator = null
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun saveWorks() {
        val arr = JSONArray()
        works.forEach { w ->
            val obj = JSONObject()
            obj.put("title", w.title)
            obj.put("folder", w.folder.toString())
            obj.put("cover", w.cover?.toString() ?: "")
            obj.put("cv", w.cv)
            obj.put("circle", w.circle)
            obj.put("tags", JSONArray(w.tags))
            obj.put("favorite", w.favorite)
            obj.put("importedAt", w.importedAt)
            fun putTracks(key: String, items: List<LocalTrack>) {
                val a = JSONArray()
                items.forEach { t -> a.put(JSONObject().put("name", t.name).put("uri", t.uri.toString())) }
                obj.put(key, a)
            }
            fun putUris(key: String, items: List<Uri>) {
                val a = JSONArray()
                items.forEach { a.put(it.toString()) }
                obj.put(key, a)
            }
            putTracks("tracks", w.tracks)
            putUris("arts", w.arts)
            putUris("scripts", w.scripts)
            arr.put(obj)
        }
        getSharedPreferences("shengyu_library", MODE_PRIVATE).edit().putString("works", arr.toString()).apply()
    }

    private fun loadWorks() {
        works.clear()
        val raw = getSharedPreferences("shengyu_library", MODE_PRIVATE).getString("works", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val savedTracks = mutableListOf<LocalTrack>()
                val ta = obj.optJSONArray("tracks") ?: JSONArray()
                for (j in 0 until ta.length()) {
                    val t = ta.getJSONObject(j)
                    savedTracks.add(LocalTrack(t.getString("name"), Uri.parse(t.getString("uri"))))
                }
                fun readUris(key: String): List<Uri> {
                    val a = obj.optJSONArray(key) ?: JSONArray()
                    return (0 until a.length()).map { Uri.parse(a.getString(it)) }
                }
                works.add(LocalWork(
                    obj.optString("title", "未命名作品"),
                    Uri.parse(obj.getString("folder")),
                    savedTracks,
                    readUris("arts"),
                    readUris("scripts"),
                    obj.optString("cover", "").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                    obj.optString("cv", ""),
                    obj.optString("circle", ""),
                    (0 until (obj.optJSONArray("tags")?.length() ?: 0)).map { obj.optJSONArray("tags")!!.optString(it) },
                    obj.optBoolean("favorite", false),
                    obj.optLong("importedAt", System.currentTimeMillis())
                ))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "旧作品库读取失败，请重新导入文件夹。", Toast.LENGTH_LONG).show()
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        currentTrack = -1
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        savePlaybackMemory()
        progressHandler.removeCallbacks(progressUpdater)
        cancelSleepTimer()
        player?.release(); player = null
        super.onDestroy()
    }
}
