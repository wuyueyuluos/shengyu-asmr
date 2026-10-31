package com.example.shengyuasmr

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
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
    private val progressHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var sleepTimerEndAt = 0L
    private var sleepTimerRunnable: Runnable? = null
    private var progressBar: SeekBar? = null
    private var elapsedLabel: TextView? = null
    private var durationLabel: TextView? = null
    private var resumePosition = 0
    private var folderUri: Uri? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            folderUri = uri
            scanFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
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

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(8))
            setBackgroundColor(0xFFF8F5FA.toInt())
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderLibrary() {
        root.setBackgroundColor(0xFFF8F5FA.toInt())
        currentScreen = "library"
        content.removeAllViews()
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(label("声屿", 27, 0xFF51435F.toInt(), true))
        brandText.addView(label("SHENGYU  ·  ASMR LIBRARY", 9, 0xFFA095AA.toInt(), true))
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        fun addHeaderIcon(glyph: String, description: String, action: () -> Unit, longAction: (() -> Unit)? = null) {
            val icon = TextView(this).apply {
                text = glyph
                textSize = 23f
                gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(0xFF756681.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x00FFFFFF)
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
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFEDE5F2.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener { showLibrarySections() }
        }
        heading.addView(label(libraryFilter + "  ▾", 16, 0xFF51435F.toInt(), true))
        heading.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        heading.addView(label(filteredWorks().size.toString() + " 部作品", 12, 0xFF91869B.toInt()))
        content.addView(heading, LinearLayout.LayoutParams(-1, dp(48)))
        if (works.isEmpty()) {
            val empty = TextView(this).apply {
                text = "♫\n\n声屿还在等你的第一份声音。"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF8A8192.toInt())
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this).apply { clipToPadding = false; setPadding(0, dp(8), 0, dp(8)) }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(1), 0, dp(1), dp(8)) }
            filteredWorks().forEachIndexed { index, work ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9), dp(9), dp(6), dp(9))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFAF8FC.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFFEAE3F0.toInt()) }
                    elevation = dp(2).toFloat()
                }
                val coverView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFEAE3F0.toInt()); cornerRadius = dp(13).toFloat() }
                    clipToOutline = true
                    val coverUri = work.cover ?: work.arts.firstOrNull()
                    if (coverUri != null) try { contentResolver.openInputStream(coverUri)?.use { setImageBitmap(BitmapFactory.decodeStream(it)) } } catch (_: Exception) {}
                    if (drawable == null) setImageResource(android.R.drawable.ic_menu_gallery)
                }
                row.addView(coverView, LinearLayout.LayoutParams(dp(76), dp(94)))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                info.addView(TextView(this).apply {
                    text = work.title
                    textSize = 15f
                    setTextColor(0xFF45394F.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    setLineSpacing(dp(2).toFloat(), 1.02f)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                info.addView(label("${work.tracks.size} 音轨  ·  ${work.arts.size} 插画  ·  ${work.scripts.size} 台本", 10, 0xFF91869B.toInt()).apply { setPadding(0, dp(5), 0, 0) })
                val categoryLine = listOf(work.cv, work.circle).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (categoryLine.isNotBlank()) info.addView(label(categoryLine, 10, 0xFF9A8EA4.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
                row.addView(info, LinearLayout.LayoutParams(0, dp(94), 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF82738F.toInt())
                    setPadding(dp(3), 0, dp(3), 0)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFF2EDF6.toInt()); cornerRadius = dp(12).toFloat() }
                    setOnClickListener { showWorkMenu(work) }
                }, LinearLayout.LayoutParams(dp(28), dp(94)))
                row.setOnClickListener {
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
        root.setBackgroundColor(0xFFF8F5FA.toInt())
        currentScreen = "home"
        content.removeAllViews()
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(224, 231, 222, 235))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), android.graphics.Color.argb(105, 255, 255, 255))
            }
            elevation = dp(2).toFloat()
        }
        heading.addView(label("NOW PLAYING  ·  正在聆听", 9, 0xFF9A8EA4.toInt(), true))
        heading.addView(label(currentFolderName, 18, 0xFF51435F.toInt(), true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(5))
        })
        val counts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addCount(glyph: String, count: Int) {
            counts.addView(label("$glyph  $count", 12, 0xFF81758F.toInt()).apply {
                setPadding(0, 0, dp(18), 0)
            })
        }
        addCount("♫", tracks.size)
        addCount("▧", artworks.size)
        addCount("▤", scripts.size)
        heading.addView(counts)
        val trackName = tracks.getOrNull(currentTrack)?.name ?: "尚未选择音轨"
        heading.addView(label(trackName, 11, 0xFF9A8EA4.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val artFrame = FrameLayout(this).apply {
            clipToPadding = true
            clipChildren = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF0EAF5.toInt())
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
        updateArtworkFrameBackground(artFrame)
        content.addView(artFrame, artLp)
        art.setOnClickListener {
            if (artworks.size > 1) {
                currentArt = (currentArt + 1) % artworks.size
                renderHome()
            }
        }
        art.setOnLongClickListener {
            if (artworks.isNotEmpty()) showArtPreview(currentArt)
            true
        }
        renderBottomControls()
    }

    /**
     * Uses the current illustration as a softly blurred backdrop only inside the artwork frame.
     * The source is decoded at a small size to keep memory and CPU use modest on older phones.
     */
    private fun updateArtworkFrameBackground(frame: FrameLayout) {
        if (!getSharedPreferences("shengyu_settings", MODE_PRIVATE).getBoolean("artwork_blur_background", true)) return
        val uri = artworks.getOrNull(currentArt % artworks.size)
        if (uri == null) {
            frame.setBackgroundColor(0xFFF0EAF5.toInt())
            return
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                root.setBackgroundColor(0xFFF8F5FA.toInt())
                return
            }

            // Decode a small source first, then center-crop it to the screen's aspect ratio.
            val targetW = (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(1)
            val targetH = dp(420)
            val targetRatio = targetW.toFloat() / targetH.coerceAtLeast(1)
            val sample = maxOf(1, minOf(bounds.outWidth / 360, bounds.outHeight / 640))
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("Unable to decode artwork")

            val sourceRatio = decoded.width.toFloat() / decoded.height.coerceAtLeast(1)
            val cropW: Int
            val cropH: Int
            if (sourceRatio > targetRatio) {
                cropH = decoded.height
                cropW = (cropH * targetRatio).toInt().coerceIn(1, decoded.width)
            } else {
                cropW = decoded.width
                cropH = (cropW / targetRatio).toInt().coerceIn(1, decoded.height)
            }
            val cropX = (decoded.width - cropW) / 2
            val cropY = (decoded.height - cropH) / 2
            val cropped = Bitmap.createBitmap(decoded, cropX, cropY, cropW, cropH)
            if (cropped !== decoded && !decoded.isRecycled) decoded.recycle()

            // Prepare a modest-size backdrop and apply a real two-pass box blur.
            val blurW = 180
            val blurH = maxOf(240, (blurW / targetRatio).toInt().coerceAtMost(420))
            val small = Bitmap.createScaledBitmap(cropped, blurW, blurH, true)
            if (small !== cropped && !cropped.isRecycled) cropped.recycle()
            val pixels = IntArray(blurW * blurH)
            small.getPixels(pixels, 0, blurW, 0, 0, blurW, blurH)
            val radius = 7
            val horizontal = IntArray(pixels.size)
            for (y in 0 until blurH) {
                for (x in 0 until blurW) {
                    var rr = 0; var gg = 0; var bb = 0; var count = 0
                    for (k in (x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(blurW - 1)) {
                        val c = pixels[y * blurW + k]
                        rr += android.graphics.Color.red(c)
                        gg += android.graphics.Color.green(c)
                        bb += android.graphics.Color.blue(c)
                        count++
                    }
                    horizontal[y * blurW + x] = android.graphics.Color.rgb(rr / count, gg / count, bb / count)
                }
            }
            val blurredPixels = IntArray(pixels.size)
            for (y in 0 until blurH) {
                for (x in 0 until blurW) {
                    var rr = 0; var gg = 0; var bb = 0; var count = 0
                    for (k in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(blurH - 1)) {
                        val c = horizontal[k * blurW + x]
                        rr += android.graphics.Color.red(c)
                        gg += android.graphics.Color.green(c)
                        bb += android.graphics.Color.blue(c)
                        count++
                    }
                    blurredPixels[y * blurW + x] = android.graphics.Color.rgb(rr / count, gg / count, bb / count)
                }
            }
            val blurred = Bitmap.createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
            blurred.setPixels(blurredPixels, 0, blurW, 0, 0, blurW, blurH)
            if (!small.isRecycled) small.recycle()

            val backdrop = android.graphics.drawable.BitmapDrawable(resources, blurred).apply {
                gravity = Gravity.FILL
            }
            // A subtle dark veil deepens the backdrop without dimming foreground controls.
            val veil = android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(38, 0, 0, 0))
            val shade = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x24F8F5FA, 0x14F8F5FA, 0x99241C30.toInt())
            )
            frame.background = android.graphics.drawable.LayerDrawable(arrayOf(backdrop, veil, shade))
        } catch (_: Exception) {
            root.setBackgroundColor(0xFFF8F5FA.toInt())
        }
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
        if (count == 0L) return 0x55DCCFE8
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
            if (i == 4) showTagFilter() else { libraryFilter = choices[i]; appliedTags.clear(); renderLibrary() }
        }.setNegativeButton("取消", null).showStyled()
    }
    private fun showTagFilter() {
        val prefs = getSharedPreferences("shengyu_library", MODE_PRIVATE)
        val catalog = linkedMapOf<String, MutableList<String>>()
        val names = listOf("CV", "社团", "自定义")
        val saved = prefs.getString("tag_catalog", null)
        if (saved != null) {
            try {
                val json = JSONObject(saved)
                names.forEach { name ->
                    val arr = json.optJSONArray(name) ?: JSONArray()
                    catalog[name] = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
                }
            } catch (_: Exception) { }
        }
        if (catalog.isEmpty()) {
            catalog["CV"] = works.flatMap { it.cv.split(",", "，", "、") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
            catalog["社团"] = works.flatMap { it.circle.split(",", "，", "、") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
            catalog["自定义"] = works.flatMap { it.tags }.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        }
        names.forEach { if (catalog[it] == null) catalog[it] = mutableListOf() }
        fun saveCatalog() {
            val json = JSONObject()
            catalog.forEach { (name, tags) -> json.put(name, JSONArray(tags)) }
            prefs.edit().putString("tag_catalog", json.toString()).apply()
        }
        saveCatalog()
        val selected = appliedTags.toMutableSet()
        val collapsed = mutableSetOf<String>()
        var editing = false
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFAF8FC.toInt()); cornerRadius = dp(24).toFloat() }
        }
        panel.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFD9D0E2.toInt()); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(38), dp(5)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        panel.addView(label("标签筛选", 18, 0xFF51435F.toInt(), true).apply { setPadding(0, 0, 0, dp(8)) })
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = EditText(this).apply { hint = "搜索标签"; setSingleLine(true); textSize = 14f; setPadding(dp(12), 0, dp(12), 0); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFF0ECF4.toInt()); cornerRadius = dp(13).toFloat() } }
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
                    textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF514665.toInt())
                    setPadding(0, dp(10), 0, dp(8))
                    setOnClickListener { if (!collapsed.add(group)) collapsed.remove(group); redraw(search.text.toString()) }
                }
                header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
                val add = button("+").apply {
                    visibility = if (editing) View.VISIBLE else View.GONE
                    setOnClickListener {
                        val input = EditText(this@MainActivity).apply { hint = "输入新的${group}标签"; setSingleLine(true) }
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
                            setTextColor(if (tag in selected) -1 else 0xFF5C4D79.toInt())
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = dp(18).toFloat()
                                setColor(if (tag in selected) 0xFF8B78AD.toInt() else 0xFFE8E0F1.toInt())
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
            setOnClickListener { appliedTags.clear(); appliedTags.addAll(selected); libraryFilter = "标签"; dialog.dismiss(); renderLibrary() }
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
        fun field(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(true); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFF1ECF5.toInt()); cornerRadius = dp(12).toFloat() } }
        val title = field("作品名", work.title)
        val cv = field("CV", work.cv)
        val circle = field("社团", work.circle)
        val tags = field("自定义标签（用逗号分隔）", work.tags.joinToString(", "))
        var selectedCover: Uri? = work.cover ?: work.arts.firstOrNull()
        val coverButton = button("选择封面（当前：${selectedCover?.lastPathSegment ?: "默认插图"}）")
        coverButton.setOnClickListener {
            if (work.arts.isEmpty()) {
                Toast.makeText(this, "这个作品没有插图可用作封面。", Toast.LENGTH_SHORT).show()
            } else {
                val names = work.arts.mapIndexed { i, uri -> "${i + 1}. ${uri.lastPathSegment ?: "插图"}" }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle("选择封面").setItems(names) { _, index ->
                    selectedCover = work.arts[index]
                    coverButton.text = "选择封面（当前：${names[index]}）"
                }.setNegativeButton("取消", null).showStyled()
            }
        }
        box.addView(label("作品名称", 12, 0xFF8A7E95.toInt(), true))
        box.addView(title, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(coverButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })
        box.addView(label("CV", 12, 0xFF8A7E95.toInt(), true))
        box.addView(cv, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(label("社团", 12, 0xFF8A7E95.toInt(), true))
        box.addView(circle, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        box.addView(label("自定义标签 · 逗号分隔", 12, 0xFF8A7E95.toInt(), true))
        box.addView(tags, LinearLayout.LayoutParams(-1, dp(48)))
        android.app.AlertDialog.Builder(this).setTitle("编辑作品信息").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val i = works.indexOfFirst { it.folder == work.folder }
                if (i >= 0) {
                    val coverUri = selectedCover ?: work.arts.firstOrNull()
                    works[i] = works[i].copy(title = title.text.toString().trim().ifBlank { work.title }, cover = coverUri,
                        cv = cv.text.toString().trim(), circle = circle.text.toString().trim(),
                        tags = tags.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() })
                    saveWorks(); renderLibrary()
                }
            }.setNegativeButton("取消", null).showStyled()
    }

    private fun scanFolder(uri: Uri) {
        tracks.clear(); artworks.clear(); scripts.clear()
        val folder = DocumentFile.fromTreeUri(this, uri)
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
        content.addView(label("选择要导入的文件", 22, 0xFF3E3552.toInt(), true))
        content.addView(label("已扫描到的文件会列在这里；勾选后加入作品。", 13, 0xFF81798F.toInt()))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableListOf<Pair<CheckBox, Pair<String, Uri>>>()
        fun addGroup(title: String, items: List<Pair<String, Uri>>) {
            list.addView(label(title, 16, 0xFF5C526F.toInt(), true).apply { setPadding(0, dp(12), 0, dp(4)) })
            items.forEach { (name, uri) ->
                val cb = CheckBox(this).apply { text = name; isChecked = true; setTextColor(0xFF3E3552.toInt()) }
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
                if (targetFolder == null) {
                    Toast.makeText(this@MainActivity, "没有获取到文件夹，请重新选择。", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val previous = works.firstOrNull { it.folder.toString() == targetFolder.toString() }
                val work = LocalWork(currentFolderName, targetFolder, chosenTracks, chosenArts, chosenScripts,
                    previous?.cover ?: chosenArts.firstOrNull(), previous?.cv ?: "", previous?.circle ?: "", previous?.tags ?: emptyList(), previous?.favorite ?: false, previous?.importedAt ?: System.currentTimeMillis())
                val existing = works.indexOfFirst { it.folder.toString() == targetFolder.toString() }
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
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFCFBFD.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFFECE7F0.toInt()) } }
        panel.addView(View(this).apply { setBackgroundColor(0xFFB9AEC8.toInt()) }, LinearLayout.LayoutParams(dp(42), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshRows() {
            list.removeAllViews()
            if (selectedTab == 0) {
                tracks.forEachIndexed { i, track ->
                    val active = i == currentTrack
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = android.graphics.drawable.GradientDrawable().apply { setColor(if (active) 0xFFF1ECF8.toInt() else android.graphics.Color.TRANSPARENT); cornerRadius = dp(18).toFloat() } }
                    row.addView(TextView(this).apply { text = (if (active) "▶  " else "") + "%02d".format(i + 1) + ".  " + track.name; textSize = 15f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(6), 0); setTextColor(if (active) 0xFF70558F.toInt() else 0xFF494452.toInt()); setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setOnClickListener { dialog.dismiss(); currentScreen = returnScreen; playTrack(i) } }, LinearLayout.LayoutParams(0, dp(66), 1f))
                    row.addView(TextView(this).apply { text = "⠿"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF9487A1.toInt()); setOnLongClickListener { v -> v.startDragAndDrop(android.content.ClipData.newPlainText("track-index", i.toString()), View.DragShadowBuilder(v), i, 0); true }; setOnDragListener { _, e -> if (e.action == android.view.DragEvent.ACTION_DROP) { val from = e.localState as? Int; if (from != null && from in tracks.indices && from != i) { val moved = tracks.removeAt(from); tracks.add(i, moved); val wi = works.indexOfFirst { it.folder == folderUri }; if (wi >= 0) { works[wi] = works[wi].copy(tracks = tracks.toList()); saveWorks() }; refreshRows() } }; true } }, LinearLayout.LayoutParams(dp(42), dp(60)))
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(2) })
                }
                if (tracks.isEmpty()) list.addView(label("这个作品没有音轨", 13, 0xFF9A8EA4.toInt()).apply { setPadding(dp(12), dp(18), 0, dp(18)) })
            } else {
                scripts.forEachIndexed { i, uri ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(this).apply { text = "%02d".format(i + 1) + ".  " + (uri.lastPathSegment?.substringAfterLast("/") ?: ("台本 " + (i + 1))); textSize = 15f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(6), 0); setTextColor(0xFF494452.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setOnClickListener { selectedScriptIndex = i; dialog.dismiss(); currentScreen = returnScreen; showScript = true; loadScript() } }, LinearLayout.LayoutParams(0, dp(66), 1f))
                    row.addView(TextView(this).apply { text = "▤"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xFF9993A7.toInt()); setOnClickListener { selectedScriptIndex = i; dialog.dismiss(); currentScreen = returnScreen; showScript = true; loadScript() } }, LinearLayout.LayoutParams(dp(42), dp(60)))
                    row.addView(TextView(this).apply { text = "⠿"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF9487A1.toInt()); setOnLongClickListener { v -> v.startDragAndDrop(android.content.ClipData.newPlainText("script-index", i.toString()), View.DragShadowBuilder(v), i, 0); true }; setOnDragListener { _, e -> if (e.action == android.view.DragEvent.ACTION_DROP) { val from = e.localState as? Int; if (from != null && from in scripts.indices && from != i) { val moved = scripts.removeAt(from); scripts.add(i, moved); val wi = works.indexOfFirst { it.folder == folderUri }; if (wi >= 0) { works[wi] = works[wi].copy(scripts = scripts.toList()); saveWorks() }; getSharedPreferences("shengyu_script_order", MODE_PRIVATE).edit().putString("order_" + (folderUri?.toString()?.hashCode() ?: currentFolderName.hashCode()), scripts.joinToString("\n") { it.toString() }).apply(); refreshRows() } }; true } }, LinearLayout.LayoutParams(dp(42), dp(60)))
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(2) })
                }
                if (scripts.isEmpty()) list.addView(label("这个作品没有台本", 13, 0xFF9A8EA4.toInt()).apply { setPadding(dp(12), dp(18), 0, dp(18)) })
            }
        }
        fun tab(title: String, index: Int): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            box.addView(TextView(this).apply { text = title; textSize = 16f; gravity = Gravity.CENTER; setTextColor(if (selectedTab == index) 0xFF70558F.toInt() else 0xFF8D8999.toInt()); setPadding(0, dp(8), 0, dp(10)) }, LinearLayout.LayoutParams(-1, dp(48)))
            box.addView(View(this).apply { setBackgroundColor(if (selectedTab == index) 0xFF70558F.toInt() else 0x00FFFFFF) }, LinearLayout.LayoutParams(-1, dp(3)))
            box.setOnClickListener { selectedTab = index; tabs.removeAllViews(); tabs.addView(tab("♫  音轨列表", 0), LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tab("▤  台本", 1), LinearLayout.LayoutParams(0, -2, 1f)); refreshRows() }
            return box
        }
        tabs.addView(tab("♫  音轨列表", 0), LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tab("▤  台本", 1), LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(tabs)
        panel.addView(View(this).apply { setBackgroundColor(0xFFECE8F1.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
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
                setOnCompletionListener { if (index < tracks.lastIndex) playTrack(index + 1) }
                prepare()
                if (startAt > 0) seekTo(startAt.coerceAtMost(duration))
                start()
            }
            currentTrack = index
            resumePosition = startAt.coerceAtLeast(0)
            savePlaybackMemory()
            if (showScript) loadScript() else renderHome()
        } catch (e: Exception) {
            Toast.makeText(this, "无法播放此音频格式：${e.message ?: ""}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveScriptScrollPosition() {
        if (currentScreen == "script") {
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
        val scroll = ScrollView(this).apply {
            setOnScrollChangeListener { _, _, y, _, _ -> scriptScrollY = y }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFCFAFD.toInt()); cornerRadius = dp(18).toFloat() } }
        scroll.addView(body)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val uri = scripts.getOrNull(selectedScriptIndex.coerceIn(0, scripts.lastIndex)) ?: scripts.first()
        if ((uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase() == "pdf") {
            try {
                contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val w = (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(1)
                                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                body.addView(ImageView(this).apply {
                                    setImageBitmap(bitmap)
                                    adjustViewBounds = true
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                body.addView(label("PDF 无法打开：${e.message ?: "未知错误"}", 15, 0xFF81798F.toInt()))
            }
        } else {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                currentScriptText = decodeScriptText(bytes)
            } catch (_: Exception) { currentScriptText = "暂时无法读取这份台本。" }
            body.addView(TextView(this).apply {
                text = currentScriptText
                textSize = 17.5f
                setTextColor(0xFF51475B.toInt())
                gravity = Gravity.START
                setLineSpacing(dp(9).toFloat(), 1.12f)
                setPadding(dp(20), dp(22), dp(20), dp(30))
                letterSpacing = 0.02f
            })
        }
        scroll.post { scroll.scrollTo(0, previousScrollY); scriptScrollY = previousScrollY }
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
        elapsedLabel = label("0:00", 11, 0xFF81798F.toInt()); durationLabel = label("0:00", 11, 0xFF81798F.toInt())
        times.addView(elapsedLabel, LinearLayout.LayoutParams(0, -2, 1f)); durationLabel?.gravity = Gravity.END; times.addView(durationLabel); progressRow.addView(times)
        progressBar = SeekBar(this).apply { max = 1000; progress = 0; setPadding(dp(2), 0, dp(2), 0); progressTintList = android.content.res.ColorStateList.valueOf(0xFF927DAA.toInt()); thumbTintList = android.content.res.ColorStateList.valueOf(0xFF927DAA.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE5DDEB.toInt()); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { val p = player ?: return; if (fromUser && p.duration > 0) p.seekTo((p.duration.toLong() * progress / 1000L).toInt()) }; override fun onStartTrackingTouch(seekBar: SeekBar?) {}; override fun onStopTrackingTouch(seekBar: SeekBar?) { savePlaybackMemory() } }) }
        progressRow.addView(progressBar); content.addView(progressRow); progressHandler.removeCallbacks(progressUpdater); progressHandler.post(progressUpdater)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, dp(2)) }
        fun addControl(title: String, action: () -> Unit, primary: Boolean = false) { val control = TextView(this).apply { text = title; textSize = if (primary) 24f else 19f; gravity = Gravity.CENTER; setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF6D5D7D.toInt()); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(if (primary) 28 else 18).toFloat(); setColor(if (primary) 0xFF927DAA.toInt() else 0xFFEDE5F2.toInt()) }; if (primary) elevation = dp(3).toFloat(); setOnClickListener { action() } }; row.addView(control, LinearLayout.LayoutParams(0, dp(if (primary) 56 else 48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        addControl("☷", { showTrackList() }); addControl("↶", { player?.let { p -> val seconds = getSharedPreferences("shengyu_settings", MODE_PRIVATE).getInt("seek_back_seconds", 15); p.seekTo((p.currentPosition - seconds * 1000).coerceAtLeast(0)) } })
        addControl(if (player?.isPlaying == true) "Ⅱ" else "▶", { if (player?.isPlaying == true) { player?.pause(); if (showScript) loadScript() else renderHome() } else if (currentTrack >= 0) { player?.start(); if (showScript) loadScript() else renderHome() } else if (tracks.isNotEmpty()) playTrack(0) }, true)
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
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF17151D.toInt()) }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val matrix = android.graphics.Matrix()
        val rect = android.graphics.RectF()
        var baseScale = 1f
        var currentScale = 1f
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
                contentResolver.openInputStream(artworks[index])?.use { image.setImageBitmap(BitmapFactory.decodeStream(it)) }
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
                val scaledW = drawable.intrinsicWidth * baseScale
                val scaledH = drawable.intrinsicHeight * baseScale
                matrix.postTranslate((vw - scaledW) / 2f, (vh - scaledH) / 2f)
                image.imageMatrix = matrix
            }
        }
        var lastX = 0f
        var lastY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val next = (currentScale * factor).coerceIn(1f, 5f)
                val applied = next / currentScale
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                currentScale = next
                clampMatrix()
                moved = true
                return true
            }
        })
        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; startX = event.x; startY = event.y; moved = false }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > 1f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        matrix.postTranslate(dx, dy)
                        clampMatrix()
                        moved = moved || kotlin.math.abs(dx) > 2f || kotlin.math.abs(dy) > 2f
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!moved && kotlin.math.abs(dx) < dp(12) && kotlin.math.abs(dy) < dp(12) && currentScale <= 1.01f) dialog.dismiss()
                    else if (currentScale <= 1.01f && kotlin.math.abs(dx) > dp(60) && kotlin.math.abs(dx) > kotlin.math.abs(dy) && artworks.size > 1) {
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
            box.addView(label("音量", 16, 0xFF3E3552.toInt(), true)); box.addView(seek)
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
            .setItems(arrayOf("播放设置", "外观", "关于")) { _, which ->
                when (which) {
                    0 -> showPlaybackSettings()
                    1 -> showAppearanceSettings()
                    2 -> showAbout()
                }
            }
            .setNegativeButton("关闭", null).showStyled()
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
            .setTitle("播放设置")
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
        val useBlur = prefs.getBoolean("artwork_blur_background", true)
        android.app.AlertDialog.Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(arrayOf("插画动态模糊背景", "柔和主色背景"), if (useBlur) 0 else 1) { dialog, which ->
                prefs.edit().putBoolean("artwork_blur_background", which == 0).apply()
                dialog.dismiss()
                if (currentScreen == "home" || currentScreen == "script" || currentScreen == "tracklist") {
                    if (showScript) loadScript() else renderHome()
                }
            }
            .setNegativeButton("完成", null)
            .showStyled()
    }

    private fun showAbout() {
        val message = "声屿 ASMR\n本地音频与台本播放器\n\n作品和播放记录保存在你的设备上。"
        android.app.AlertDialog.Builder(this)
            .setTitle("关于声屿")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .showStyled()
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
            setTextColor(0xFF51435F.toInt())
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
            setTextColor(0xFF806B9A.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEDE5F2.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        val minus = timerStepButton("−", "减少一分钟")
        val plus = timerStepButton("+", "增加一分钟")
        val seek = android.widget.SeekBar(this).apply {
            max = 60
            progress = initialMinutes
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF9A82B3.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF9A82B3.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8DFF0.toInt())
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
            setTextColor(0xFF9B91A4.toInt())
            setPadding(0, dp(4), 0, dp(18))
        }
        panel.addView(scale)

        val defaultSwitch = android.widget.CheckBox(this).apply {
            text = "设为默认"
            textSize = 15f
            setTextColor(0xFF62536F.toInt())
            isChecked = defaultEnabled
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF9A82B3.toInt())
        }
        panel.addView(defaultSwitch)

        val status = TextView(this).apply {
            text = if (sleepTimerEndAt > System.currentTimeMillis()) "当前定时剩余：${formatTimerRemaining()}" else "拖动进度条或使用 + / − 调整时间"
            textSize = 12f
            setTextColor(0xFF9B91A4.toInt())
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
                setColor(0xFFFAF8FC.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFFEAE3F0.toInt())
            }
            dialog.window?.setBackgroundDrawable(surface)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF806B9A.toInt())
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF806B9A.toInt())
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFF806B9A.toInt())
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
                setColor(0xFFFAF8FC.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFFEAE3F0.toInt())
            }
            dialog.window?.setBackgroundDrawable(surface)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF806B9A.toInt())
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF806B9A.toInt())
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFF806B9A.toInt())
            dialog.listView?.apply {
                setBackgroundColor(0xFFFAF8FC.toInt())
                divider = null
                dividerHeight = 0
            }
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            val titleContainer = titleView?.parent as? android.view.ViewGroup
            val topPanel = titleContainer?.parent as? android.view.ViewGroup
            if (titleContainer != null && topPanel != null && titleContainer.parent === topPanel) {
                val divider = View(this@MainActivity).apply {
                    setBackgroundColor(0xFFEAE3F0.toInt())
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
        setTextColor(0xFF62536F.toInt())
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFEDE5F2.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xFFE4D9EB.toInt())
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
