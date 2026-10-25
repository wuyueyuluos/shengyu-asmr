package com.example.shengyuasmr

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile

data class LocalTrack(val name: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private val tracks = mutableListOf<LocalTrack>()
    private val artworks = mutableListOf<Uri>()
    private val scripts = mutableListOf<Uri>()
    private var player: MediaPlayer? = null
    private var currentTrack = -1
    private var currentArt = 0
    private var showScript = false
    private var currentFolderName = "还没有导入作品"
    private var currentScriptText = ""
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
        renderHome()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(0xFFF7F4FB.toInt())
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(label("声屿 ASMR", 24, 0xFF3E3552.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val menu = button("☰")
        menu.setOnClickListener { showSettings() }
        header.addView(menu)
        root.addView(header)
        root.addView(label("把喜欢的声音，收进自己的小岛。", 13, 0xFF81798F.toInt()))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderHome() {
        content.removeAllViews()
        val title = label(currentFolderName, 20, 0xFF3E3552.toInt(), true)
        title.setPadding(0, dp(22), 0, dp(8))
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
        artFrame.setOnClickListener {
            if (scripts.isNotEmpty()) {
                showScript = !showScript
                if (showScript) loadScript() else renderHome()
            } else if (artworks.size > 1) {
                currentArt = (currentArt + 1) % artworks.size
                renderHome()
            }
        }
        val hint = if (scripts.isNotEmpty()) "轻触插画切换台本 / 插画" else if (artworks.size > 1) "轻触切换下一张插画" else "作品插画预览"
        content.addView(label(hint, 12, 0xFF81798F.toInt()).apply { gravity = Gravity.CENTER })
        val now = label(if (currentTrack >= 0) "正在播放：${tracks[currentTrack].name}" else "选择一条音轨开始聆听", 14, 0xFF5C526F.toInt(), true)
        now.setPadding(0, dp(12), 0, dp(8)); content.addView(now)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("曲目").apply { setOnClickListener { showTrackList() } })
        controls.addView(button("上一首").apply { setOnClickListener { playTrack((currentTrack - 1).coerceAtLeast(0)) } })
        controls.addView(button(if (player?.isPlaying == true) "暂停" else "播放").apply {
            setOnClickListener {
                if (player?.isPlaying == true) { player?.pause(); renderHome() }
                else if (currentTrack >= 0) { player?.start(); renderHome() }
                else if (tracks.isNotEmpty()) playTrack(0)
            }
        })
        controls.addView(button("下一首").apply { setOnClickListener { playTrack((currentTrack + 1).coerceAtMost(tracks.lastIndex)) } })
        controls.addView(button("音量").apply { setOnClickListener { showVolume() } })
        content.addView(controls)
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
                        "txt", "lrc", "srt" -> scripts.add(f.uri)
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
        addGroup("台本（TXT / LRC / SRT）", scripts.map { (it.lastPathSegment ?: "台本") to it })
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button("导入所选文件").apply {
            setOnClickListener {
                val chosen = checks.filter { it.first.isChecked }.map { it.second.second }.toSet()
                val chosenTracks = tracks.filter { chosen.contains(it.uri) }
                val chosenArts = artworks.filter { chosen.contains(it) }
                val chosenScripts = scripts.filter { chosen.contains(it) }
                tracks.clear(); tracks.addAll(chosenTracks)
                artworks.clear(); artworks.addAll(chosenArts)
                scripts.clear(); scripts.addAll(chosenScripts)
                currentArt = 0; currentTrack = -1; showScript = false
                renderHome()
            }
        })
        content.addView(button("返回作品页").apply { setOnClickListener { renderHome() } })
    }

    private fun showTrackList() {
        if (tracks.isEmpty()) { Toast.makeText(this, "请先导入作品文件夹", Toast.LENGTH_SHORT).show(); return }
        content.removeAllViews()
        content.addView(label("曲目列表", 22, 0xFF3E3552.toInt(), true))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tracks.forEachIndexed { i, t ->
            val b = button("${if (i == currentTrack) "▶ " else ""}${i + 1}. ${t.name}")
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setOnClickListener { playTrack(i) }
            list.addView(b)
        }
        scroll.addView(list); content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button("返回作品页").apply { setOnClickListener { renderHome() } })
    }

    private fun playTrack(index: Int) {
        if (index !in tracks.indices) return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, tracks[index].uri)
                setOnCompletionListener { if (index < tracks.lastIndex) playTrack(index + 1) }
                prepare()
                start()
            }
            currentTrack = index
            renderHome()
        } catch (e: Exception) {
            Toast.makeText(this, "无法播放此音频格式：${e.message ?: ""}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadScript() {
        if (scripts.isEmpty()) return
        try {
            currentScriptText = contentResolver.openInputStream(scripts.first())?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) { currentScriptText = "暂时无法读取这份台本。" }
        content.removeAllViews()
        content.addView(label("台本", 22, 0xFF3E3552.toInt(), true))
        val tv = TextView(this).apply {
            text = currentScriptText
            textSize = 16f
            setTextColor(0xFF4C435B.toInt())
            setLineSpacing(dp(5).toFloat(), 1.05f)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val sc = ScrollView(this); sc.addView(tv)
        content.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button("返回插画").apply { setOnClickListener { showScript = false; renderHome() } })
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
            .setItems(arrayOf("导入作品文件夹", "重新选择文件夹")) { _, _ -> pickFolder.launch(null) }
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }
}
