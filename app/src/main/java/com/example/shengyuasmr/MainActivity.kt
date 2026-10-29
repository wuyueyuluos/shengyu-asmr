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
    val favorite: Boolean = false
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
    private var previewArtIndex = 0
    private var currentScreen = "library"
    private var lastBackPressedAt = 0L
    private val progressHandler = Handler(Looper.getMainLooper())
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
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(0xFFF7F4FB.toInt())
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(View(this), LinearLayout.LayoutParams(0, dp(18), 1f))
        val menu = button("☰")
        menu.setOnClickListener { showSettings() }
        header.addView(menu)
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderLibrary() {
        currentScreen = "library"
        content.removeAllViews()
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        heading.addView(label("全部作品", 22, 0xFF3E3552.toInt(), true))
        heading.addView(label("  ${works.size}", 16, 0xFF81798F.toInt()))
        content.addView(heading)
        if (works.isEmpty()) content.addView(label("还没有作品。点击下方按钮，选择一个作品文件夹导入。", 14, 0xFF81798F.toInt()))
        if (works.isEmpty()) {
            val empty = TextView(this).apply {
                text = "♫\n\n声屿还在等你的第一份声音。"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF81798F.toInt())
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            works.forEachIndexed { index, work ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    setBackgroundColor(0xFFEDE7F5.toInt())
                }
                val coverView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val coverUri = work.cover ?: work.arts.firstOrNull()
                    if (coverUri != null) try { contentResolver.openInputStream(coverUri)?.use { setImageBitmap(BitmapFactory.decodeStream(it)) } } catch (_: Exception) {}
                    if (drawable == null) setImageResource(android.R.drawable.ic_menu_gallery)
                }
                row.addView(coverView, LinearLayout.LayoutParams(dp(76), dp(94)))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                info.addView(TextView(this).apply {
                    text = work.title
                    textSize = 16f
                    setTextColor(0xFF3E3552.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                info.addView(label("${work.tracks.size} 条音轨 · ${work.arts.size} 张插画 · ${work.scripts.size} 份台本", 11, 0xFF81798F.toInt()))
                row.addView(info, LinearLayout.LayoutParams(0, dp(94), 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF5C4D79.toInt())
                    setPadding(dp(4), 0, dp(4), 0)
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
                val lp = LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(8) }
                list.addView(row, lp)
            }
            scroll.addView(list)
            content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        content.addView(button("＋ 导入作品文件夹").apply {
            setOnClickListener { scanningFolder = null; pickFolder.launch(null) }
        })
    }

    private fun renderHome() {
        currentScreen = "home"
        content.removeAllViews()
        val title = label(currentFolderName, 20, 0xFF3E3552.toInt(), true)
        title.setPadding(0, dp(2), 0, dp(8))
        content.addView(title)
        content.addView(label(if (tracks.isEmpty()) "先导入一个作品文件夹，应用会帮你识别音频、插画和台本。" else "已识别 ${tracks.size} 条音轨 · ${artworks.size} 张插画 · ${scripts.size} 份台本", 14, 0xFF81798F.toInt()))
        val artFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFFE9E2F1.toInt())
        }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (artworks.isNotEmpty()) {
            try { contentResolver.openInputStream(artworks[currentArt % artworks.size])?.use { art.setImageBitmap(BitmapFactory.decodeStream(it)) } } catch (_: Exception) {}
        } else {
            art.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val artLp = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(18); bottomMargin = dp(14) }
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

    private fun showWorkMenu(work: LocalWork) {
        val options = arrayOf("编辑", if (work.favorite) "取消收藏" else "收藏", "重新扫描并更新", "从作品库删除")
        android.app.AlertDialog.Builder(this).setTitle(work.title).setItems(options) { _, which ->
            when (which) {
                0 -> showEditWork(work)
                1 -> { val i = works.indexOfFirst { it.folder == work.folder }; if (i >= 0) { works[i] = works[i].copy(favorite = !work.favorite); saveWorks(); renderLibrary() } }
                2 -> { scanningFolder = work.folder; scanFolder(work.folder) }
                3 -> android.app.AlertDialog.Builder(this).setMessage("确定从作品库删除“${work.title}”？").setPositiveButton("删除") { _, _ ->
                    works.removeAll { it.folder == work.folder }; saveWorks(); renderLibrary()
                }.setNegativeButton("取消", null).show()
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun showEditWork(work: LocalWork) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        fun field(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(true) }
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
                }.setNegativeButton("取消", null).show()
            }
        }
        box.addView(title); box.addView(coverButton); box.addView(cv); box.addView(circle); box.addView(tags)
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
            }.setNegativeButton("取消", null).show()
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
                    previous?.cover ?: chosenArts.firstOrNull(), previous?.cv ?: "", previous?.circle ?: "", previous?.tags ?: emptyList(), previous?.favorite ?: false)
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
        if (tracks.isEmpty()) { Toast.makeText(this, "请先导入作品文件夹", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(this)
        val returnScreen = if (showScript) "script" else "home"
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10)); setBackgroundColor(0xFFF7F4FB.toInt()) }
        panel.addView(View(this).apply { setBackgroundColor(0xFFB9AEC8.toInt()) }, LinearLayout.LayoutParams(dp(42), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        panel.addView(label("音轨列表", 17, 0xFF3E3552.toInt(), true).apply { setPadding(dp(4), 0, 0, dp(8)) })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshRows() {
            list.removeAllViews()
            tracks.forEachIndexed { i, t ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val rowLp = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) }
                row.addView(button("${if (i == currentTrack) "▶ " else ""}${i + 1}. ${t.name}").apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(2), 0)
                    setOnClickListener { dialog.dismiss(); currentScreen = returnScreen; playTrack(i) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(TextView(this).apply {
                    text = "⠿"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(0xFF5C4D79.toInt()); setBackgroundColor(0xFFE8E0F1.toInt()); minimumWidth = 0; minimumHeight = 0
                    setOnLongClickListener { view -> view.startDragAndDrop(android.content.ClipData.newPlainText("track-index", i.toString()), View.DragShadowBuilder(view), i, 0); true }
                    setOnDragListener { _, event ->
                        if (event.action == android.view.DragEvent.ACTION_DROP) {
                            val from = event.localState as? Int
                            if (from != null && from in tracks.indices && from != i) {
                                val moved = tracks.removeAt(from); tracks.add(i, moved)
                                val wi = works.indexOfFirst { it.folder == folderUri }
                                if (wi >= 0) { works[wi] = works[wi].copy(tracks = tracks.toList()); saveWorks() }
                                refreshRows()
                            }
                        }; true
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                list.addView(row, rowLp)
            }
        }
        refreshRows(); scroll.addView(list); panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.setOnCancelListener { currentScreen = returnScreen }
        dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.46f).toInt()); dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setDimAmount(0.35f)
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

    private fun loadScript() {
        currentScreen = "script"
        if (scripts.isEmpty()) {
            Toast.makeText(this, "这个作品没有导入台本。", Toast.LENGTH_SHORT).show()
            showScript = false
            renderHome()
            return
        }
        content.removeAllViews()
        content.addView(label("台本", 22, 0xFF3E3552.toInt(), true))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val uri = scripts.first()
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
                textSize = 16f
                setTextColor(0xFF4C435B.toInt())
                setLineSpacing(dp(5).toFloat(), 1.05f)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
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
        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsedLabel = label("0:00", 12, 0xFF81798F.toInt())
        durationLabel = label("0:00", 12, 0xFF81798F.toInt())
        times.addView(elapsedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        durationLabel?.gravity = Gravity.END
        times.addView(durationLabel)
        progressRow.addView(times)
        progressBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = player ?: return
                    if (fromUser && p.duration > 0) p.seekTo((p.duration.toLong() * progress / 1000L).toInt())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { savePlaybackMemory() }
            })
        }
        progressRow.addView(progressBar)
        content.addView(progressRow)
        progressHandler.removeCallbacks(progressUpdater)
        progressHandler.post(progressUpdater)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun addControl(title: String, action: () -> Unit) {
            row.addView(button(title).apply {
                textSize = 11f
                minWidth = 0
                setPadding(dp(2), dp(4), dp(2), dp(4))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addControl("音轨列表") { showTrackList() }
        addControl("倒退 15秒") { player?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) } }
        addControl(if (player?.isPlaying == true) "暂停" else "播放") {
            if (player?.isPlaying == true) {
                player?.pause()
                if (showScript) loadScript() else renderHome()
            } else if (currentTrack >= 0) {
                player?.start()
                if (showScript) loadScript() else renderHome()
            } else if (tracks.isNotEmpty()) playTrack(0)
        }
        addControl("前进 15秒") { player?.let { it.seekTo((it.currentPosition + 15000).coerceAtMost(it.duration)) } }
        addControl("台本") {
            if (showScript) {
                showScript = false
                renderHome()
            } else if (scripts.isNotEmpty()) {
                showScript = true
                loadScript()
            } else Toast.makeText(this, "这个作品没有台本。", Toast.LENGTH_SHORT).show()
        }
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
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        fun displayImage() {
            try {
                contentResolver.openInputStream(artworks[index])?.use { image.setImageBitmap(BitmapFactory.decodeStream(it)) }
            } catch (_: Exception) { image.setImageDrawable(null) }
            image.scaleX = 1f
            image.scaleY = 1f
        }
        var downX = 0f
        var downY = 0f
        var dragged = false
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = (image.scaleX * detector.scaleFactor).coerceIn(1f, 5f)
                image.scaleX = factor
                image.scaleY = factor
                return true
            }
        })
        image.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragged = false }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) > dp(20) || kotlin.math.abs(event.y - downY) > dp(20)) dragged = true
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    if (dragged && kotlin.math.abs(dx) > dp(60) && artworks.size > 1) {
                        index = if (dx < 0) (index + 1) % artworks.size else (index - 1 + artworks.size) % artworks.size
                        displayImage()
                    } else if (!dragged) dialog.dismiss()
                }
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
            android.app.AlertDialog.Builder(this@MainActivity).setView(box).setPositiveButton("完成", null).show()
        }
    }

    private fun showSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(arrayOf("导入作品文件夹", "返回作品库")) { _, which -> if (which == 0) { scanningFolder = null; pickFolder.launch(null) } else renderLibrary() }
            .setNegativeButton("关闭", null).show()
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false
        setTextColor(0xFF5C4D79.toInt())
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE8E0F1.toInt()))
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
                    obj.optBoolean("favorite", false)
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
        player?.release(); player = null
        super.onDestroy()
    }
}
