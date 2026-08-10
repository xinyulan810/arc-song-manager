package com.arcaea.songpack

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arcaea.songpack.databinding.ActivityMainBinding
import com.arcaea.songpack.model.ClassifiedFile
import com.arcaea.songpack.model.FileType
import com.arcaea.songpack.model.SongEntry
import com.arcaea.songpack.ui.ClassifiedFileAdapter
import com.arcaea.songpack.util.ArchiveExtractor
import com.arcaea.songpack.util.FileClassifier
import com.arcaea.songpack.util.SongImporter
import com.arcaea.songpack.util.SonglistParser
import com.arcaea.songpack.util.UriUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private val KEY_GAME_DIR = "game_dir_uri"

    private var extractedDir: File? = null
    private val classifiedFiles = mutableListOf<com.arcaea.songpack.model.ClassifiedFile>()
    private var entries: List<SongEntry> = emptyList()
    private var songlistCheck: SonglistParser.SonglistCheck? = null
    private var gameRootUri: Uri? = null

    private val typeLabels by lazy {
        FileType.entries.map { getString(it.displayNameRes) }.toTypedArray()
    }
    private lateinit var adapter: ClassifiedFileAdapter

    private val archiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadArchive(it) } }

    private val gameDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            gameRootUri = it
            prefs.edit().putString(KEY_GAME_DIR, it.toString()).apply()
            updateUi()
            appendLog("已选择游戏目录(已保存, 下次自动恢复)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 手动改变文件类型后, 自动重新解析 songlist 并刷新歌曲信息
        adapter = ClassifiedFileAdapter(classifiedFiles, typeLabels) { _, _ -> refreshSonglist() }
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        // 让歌曲信息文本在内容超出时能够滚动(配合布局里的 maxHeight)
        binding.songlistText.movementMethod = ScrollingMovementMethod()

        binding.btnSelectArchive.setOnClickListener {
            archiveLauncher.launch(
                arrayOf(
                    "application/zip", "application/x-zip-compressed",
                    "application/x-rar", "application/vnd.rar", "application/x-rar-compressed",
                    "*/*"
                )
            )
        }

        // 处理从外部(QQ/微信/文件管理器等)分享进来的 zip/rar
        handleSharedIntent(intent)

        binding.btnSelectGameDir.setOnClickListener { gameDirLauncher.launch(null) }
        binding.btnImport.setOnClickListener { doImport() }
        binding.btnEditSongs.setOnClickListener { showSongEditor() }
        binding.btnRestoreBackup.setOnClickListener { restoreFromBackup() }
        binding.btnEditRawSonglist.setOnClickListener { showRawSonglistEditor() }

        // 恢复上次保存的游戏目录
        restoreGameDir()

        // 首次使用: 引导选择游戏目录
        binding.root.post {
            if (gameRootUri == null) {
                AlertDialog.Builder(this)
                    .setTitle("选择游戏目录")
                    .setMessage("首次使用, 请选择游戏资源根目录(包含 songs 和 img 文件夹的目录)。选择后会自动保存。")
                    .setPositiveButton("去选择") { _, _ -> gameDirLauncher.launch(null) }
                    .setNegativeButton("稍后再说") { _, _ -> }
                    .setOnCancelListener { }
                    .show()
            }
        }

        updateUi()
    }

    /**
     * 处理外部通过"分享"(SEND)或"其他应用打开"(VIEW)进来的 zip/rar 文件。
     */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            appendLog("收到外部谱面包, 正在解析…")
            loadArchive(uri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App 已在运行时又收到分享
        handleSharedIntent(intent)
    }

    /**
     * 从本地恢复已保存的游戏目录(带持久化权限)
     */
    private fun restoreGameDir() {
        val saved = prefs.getString(KEY_GAME_DIR, null) ?: return
        try {
            val uri = Uri.parse(saved)
            val doc = DocumentFile.fromTreeUri(this, uri)
            if (doc != null && doc.exists()) {
                // 重新声明持久化权限(幂等)
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                gameRootUri = uri
                appendLog("已恢复游戏目录")
            } else {
                // 目录已失效, 清除保存
                prefs.edit().remove(KEY_GAME_DIR).apply()
            }
        } catch (_: Exception) {
            prefs.edit().remove(KEY_GAME_DIR).apply()
        }
        updateUi()
    }

    // ---------- 加载压缩包 ----------

    private fun loadArchive(uri: Uri) {
        lifecycleScope.launch {
            status("正在读取压缩包…")
            appendLog("正在读取压缩包…")
            try {
                val archiveFile = withContext(Dispatchers.IO) {
                    UriUtil.copyToCache(this@MainActivity, uri, "archive.zip")
                }
                val dest = withContext(Dispatchers.IO) {
                    File(cacheDir, "extracted").also { it.deleteRecursively(); it.mkdirs() }
                }
                withContext(Dispatchers.IO) {
                    ArchiveExtractor.extract(archiveFile, dest)
                }
                extractedDir = dest

                classifiedFiles.clear()
                classifiedFiles.addAll(FileClassifier.classify(dest))
                adapter.notifyDataSetChanged()

                // 解析 songlist 片段(自动或手动指定后都会走这里)
                refreshSonglist()
                status("识别完成: ${classifiedFiles.size} 个文件 / ${entries.size} 首歌曲")
                appendLog("压缩包解析完成, 找到 ${classifiedFiles.size} 个文件")
            } catch (e: Exception) {
                status("解析失败: ${e.message}")
                appendLog("错误: ${e.message}")
            }
        }
    }

    /**
     * 根据当前标记为 songlist 的文件, 重新解析歌曲信息并刷新界面。
     * 在自动识别或用户手动改类型后调用。
     */
    private fun refreshSonglist() {
        val songlistFile = classifiedFiles.firstOrNull { it.type == FileType.SONGLIST }
        entries = if (songlistFile != null) {
            try {
                val parsed = SonglistParser.parseFragment(songlistFile.file.readText())
                if (parsed.isEmpty()) {
                    appendLog("警告: ${songlistFile.relativePath} 未解析出歌曲条目")
                }
                parsed
            } catch (e: Exception) {
                appendLog("songlist 解析失败: ${e.message}")
                emptyList()
            }
        } else {
            appendLog("未找到标记为 songlist 的文件")
            emptyList()
        }
        // 校验 songlist 是否健康(异常则阻止导入)
        songlistCheck = SonglistParser.validateEntries(entries)
        if (songlistCheck != null && !songlistCheck!!.valid) {
            status("⚠ songlist 异常: ${songlistCheck!!.issues.joinToString("; ")}")
            appendLog("⚠ songlist 异常, 请修改源 songlist 文件后重新加载")
        } else {
            status("识别完成: ${classifiedFiles.size} 个文件 / ${entries.size} 首歌曲")
        }
        updateUi()
    }

    // ---------- 编辑歌曲信息 ----------

    /** 可编辑的歌曲字段: (json键, 界面标签) */
    private val editableFields = listOf(
        "id" to "歌曲 ID",
        "title_en" to "标题 (en)",
        "artist" to "艺术家",
        "bpm" to "BPM",
        "set" to "所属包",
        "side" to "Side (0/1)",
        "bg" to "背景图名",
        "audio" to "音乐文件名",
        "jacket" to "封面文件名",
        "version" to "版本"
    )

    /**
     * 打开歌曲信息编辑对话框: 多首歌可切换, 支持修改字段(如 bg)。
     * 修改直接写回 raw JSONObject, 保存后重建 SongEntry 并刷新显示。
     */
    private fun showSongEditor() {
        if (entries.isEmpty()) {
            toast("没有歌曲信息可编辑(请先识别 songlist)")
            return
        }
        val working = entries.toMutableList()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // 歌曲选择(多首时)
        val spinner = Spinner(this)
        val names = working.map { "${it.id} — ${it.displayTitle}" }
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, names
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        container.addView(labelOf("选择歌曲", spinner))

        // 字段输入框
        val editMap = mutableMapOf<String, EditText>()
        for ((key, label) in editableFields) {
            val et = EditText(this).apply {
                hint = label
                setSingleLine(true)
            }
            container.addView(labelOf(label, et))
            editMap[key] = et
        }
        loadFields(working.first(), editMap)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                loadFields(working[pos], editMap)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("编辑歌曲信息")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val pos = spinner.selectedItemPosition
                val entry = working[pos]
                for ((key, et) in editMap) {
                    setField(entry, key, et.text.toString().trim())
                }
                entries = working.map { SongEntry.fromJson(it.raw) }
                // 写回 songlist 源文件, 防止后续 refreshSonglist 重新解析时覆盖编辑
                saveSonglistToFile()
                songlistCheck = SonglistParser.validateEntries(entries)
                updateUi()
                appendLog("歌曲 ${entry.raw.optString("id")} 信息已更新(bg=${entry.raw.optString("bg")})")
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }

    /**
     * 把当前编辑后的歌曲信息序列化并写回 songlist 源文件,
     * 保证重新解析(refreshSonglist)读到的是编辑后的内容。
     */
    private fun saveSonglistToFile() {
        val file = classifiedFiles.firstOrNull { it.type == FileType.SONGLIST }?.file ?: return
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(it.raw) }
            file.writeText(arr.toString(4))
            appendLog("已同步编辑到 songlist 文件")
        } catch (e: Exception) {
            appendLog("写回 songlist 文件失败: ${e.message}")
        }
    }

    /**
     * 弹出窗口直接编辑源 songlist 原始文本(适合处理异常 songlist)。
     * 保存后写回源文件并重新解析校验。
     */
    private fun showRawSonglistEditor() {
        val songlistFile = classifiedFiles.firstOrNull { it.type == FileType.SONGLIST }?.file
        if (songlistFile == null) {
            toast("未找到标记为 songlist 的文件")
            return
        }
        val originalText = songlistFile.readText()

        val et = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setText(originalText)
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
            isVerticalScrollBarEnabled = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.6).toInt()
            )
        }

        AlertDialog.Builder(this)
            .setTitle("编辑源 songlist(直接改文本)")
            .setView(et)
            .setPositiveButton("保存并解析") { _, _ ->
                val newText = et.text.toString()
                try {
                    songlistFile.writeText(newText)
                    appendLog("已保存源 songlist 修改")
                    refreshSonglist()
                    toast("已保存并重新解析")
                } catch (e: Exception) {
                    toast("保存失败: ${e.message}")
                }
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }

    private fun labelOf(label: String, child: android.view.View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 13f
                }
            )
            addView(child)
        }
    }

    private fun loadFields(entry: SongEntry, editMap: Map<String, EditText>) {
        for ((key, et) in editMap) {
            et.setText(getField(entry, key))
        }
    }

    private fun getField(entry: SongEntry, key: String): String = when (key) {
        "title_en" -> entry.raw.optJSONObject("title_localized")?.optString("en") ?: ""
        else -> entry.raw.optString(key)
    }

    private fun setField(entry: SongEntry, key: String, value: String) {
        when (key) {
            "title_en" -> {
                val t = entry.raw.optJSONObject("title_localized")
                    ?: JSONObject().also { entry.raw.put("title_localized", it) }
                t.put("en", value)
            }
            "side" -> entry.raw.put("side", value.toIntOrNull() ?: 0)
            "id" -> entry.raw.put("id", value)
            else -> entry.raw.put(key, value)
        }
    }

    // ---------- 导入 ----------

    private fun doImport() {
        val rootUri = gameRootUri
        if (rootUri == null) {
            toast("请先选择游戏目录")
            return
        }
        if (entries.isEmpty()) {
            toast("没有解析到 songlist 条目, 无法导入")
            return
        }
        val ids = entries.map { it.id }
        binding.btnImport.isEnabled = false

        lifecycleScope.launch {
            // 1. 检查 id 冲突
            val existing = withContext(Dispatchers.IO) {
                SongImporter.findExistingIds(this@MainActivity, rootUri, ids)
            }
            val proceed = if (existing.isNotEmpty()) confirmReplace(existing) else true
            if (!proceed) {
                appendLog("已取消导入")
                binding.btnImport.isEnabled = true
                return@launch
            }

            // 2. 生成合并后的 songlist 文本(仅预览, 不写盘)
            val bgFile = classifiedFiles.firstOrNull { it.type == FileType.BACKGROUND }
            val prepared = withContext(Dispatchers.IO) {
                SongImporter.prepareImport(this@MainActivity, rootUri, entries, bgFile)
            }
            if (prepared == null) {
                toast("无法读取游戏目录或 songs 目录")
                binding.btnImport.isEnabled = true
                return@launch
            }

            // 3. 预览确认
            val unknowns = classifiedFiles.filter { it.type == FileType.UNKNOWN }
            val confirmed = showPreviewDialog(prepared, unknowns)
            if (!confirmed) {
                appendLog("已取消导入")
                binding.btnImport.isEnabled = true
                return@launch
            }

            // 4. 执行导入(备份 + 复制 + 写入)
            status("正在导入…")
            appendLog("开始导入…")
            val result = withContext(Dispatchers.IO) {
                SongImporter.executeImport(
                    this@MainActivity, rootUri, classifiedFiles, entries, prepared
                )
            }
            status(if (result.success) "导入成功" else "导入失败")
            appendLog(result.message)
            if (result.success) toast("导入成功: ${result.importedIds.size} 首歌曲")
            binding.btnImport.isEnabled = true
        }
    }

    /**
     * 导入前预览确认对话框。songlist 只显示末尾新增部分(最后 60 行),
     * 避免整份文件刷屏。
     */
    private suspend fun showPreviewDialog(
        prepared: SongImporter.PreparedImport,
        unknowns: List<ClassifiedFile>
    ): Boolean = suspendCancellableCoroutine { cont ->
        runOnUiThread {
            val tailLines = prepared.newSonglistText.lines().takeLast(60)
            val previewTail = tailLines.joinToString("\n")

            val summary = buildString {
                appendLine("新增歌曲: ${prepared.songIds.joinToString(", ")}")
                if (prepared.replacedIds.isNotEmpty()) {
                    appendLine("替换已有: ${prepared.replacedIds.joinToString(", ")}")
                }
                if (prepared.missingBg.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠ 以下歌曲的 bg 背景图在游戏目录中不存在:")
                    prepared.missingBg.forEach { appendLine("  · $it") }
                    appendLine("  可用\"编辑歌曲信息\"把 bg 改成游戏已有的背景图名")
                }
                if (unknowns.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠ 以下未识别文件将被忽略:")
                    unknowns.forEach { appendLine("  · ${it.relativePath}") }
                }
                if (prepared.hadExistingSonglist) {
                    appendLine()
                    appendLine("原 songlist 将备份为 songlist.backup")
                }
                appendLine()
                appendLine("──── 新增歌曲 songlist 内容(末尾) ────")
                append(previewTail)
            }

            val scroll = ScrollView(this).apply {
                val tv = TextView(context).apply {
                    setPadding(24, 24, 24, 24)
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    text = summary
                }
                addView(tv)
            }

            AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setView(scroll)
                .setPositiveButton("确认导入") { _, _ -> cont.resume(true) }
                .setNegativeButton("返回修改") { _, _ -> cont.resume(false) }
                .setOnCancelListener { cont.resume(false) }
                .show()
        }
    }

    private suspend fun confirmReplace(existing: List<String>): Boolean =
        suspendCancellableCoroutine { cont ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("检测到重复歌曲")
                    .setMessage("以下歌曲已存在于游戏 songlist 中:\n\n${existing.joinToString("\n")}\n\n是否替换旧条目?")
                    .setPositiveButton("替换") { _, _ -> cont.resume(true) }
                    .setNegativeButton("取消") { _, _ -> cont.resume(false) }
                    .setOnCancelListener { cont.resume(false) }
                    .show()
            }
        }

    // ---------- 备份恢复 ----------

    /** 从 songlist.backup 恢复 songlist, 恢复前确认并显示将移除的歌曲 */
    private fun restoreFromBackup() {
        val rootUri = gameRootUri
        if (rootUri == null) {
            toast("请先选择游戏目录")
            return
        }
        lifecycleScope.launch {
            // 先检查备份是否存在
            val backupExists = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@MainActivity, rootUri)
                root?.findFile("songs")?.findFile("songlist.backup") != null
            }
            if (!backupExists) {
                toast("未找到 songlist.backup 备份文件")
                return@launch
            }
            // 计算恢复后将被移除的歌曲
            val willRemove = withContext(Dispatchers.IO) {
                SongImporter.diffBackup(this@MainActivity, rootUri)
            }
            val confirmed = confirmRestore(willRemove)
            if (!confirmed) return@launch

            status("正在恢复…")
            appendLog("开始恢复 songlist…")
            val result = withContext(Dispatchers.IO) {
                SongImporter.restoreFromBackup(this@MainActivity, rootUri)
            }
            status(if (result.success) "恢复成功" else "恢复失败")
            appendLog(result.message)
            toast(if (result.success) "已从备份恢复 songlist" else "恢复失败: ${result.message}")
        }
    }

    private suspend fun confirmRestore(willRemove: List<String>): Boolean =
        suspendCancellableCoroutine { cont ->
            runOnUiThread {
                val msg = buildString {
                    appendLine("将从 songlist.backup 恢复歌曲列表(覆盖当前 songlist)")
                    if (willRemove.isNotEmpty()) {
                        appendLine()
                        appendLine("恢复后以下歌曲将从 songlist 中移除:")
                        willRemove.forEach { appendLine("  · $it") }
                        appendLine()
                        appendLine("注: 歌曲文件夹不会被自动删除, 可手动清理")
                    } else {
                        appendLine()
                        appendLine("备份与当前歌曲列表一致, 恢复不会移除歌曲")
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("恢复备份")
                    .setMessage(msg)
                    .setPositiveButton("恢复") { _, _ -> cont.resume(true) }
                    .setNegativeButton("取消") { _, _ -> cont.resume(false) }
                    .setOnCancelListener { cont.resume(false) }
                    .show()
            }
        }

    // ---------- UI 辅助 ----------

    private fun updateUi() {
        val hasArchive = extractedDir != null
        val hasDir = gameRootUri != null
        val checkOk = songlistCheck == null || songlistCheck!!.valid
        binding.btnImport.isEnabled = hasArchive && hasDir && entries.isNotEmpty() && checkOk

        val sl = entries.joinToString("\n\n") { it.toDisplayString() }
        binding.songlistText.text = if (sl.isBlank()) getString(R.string.songlist_empty) else sl
    }

    private fun status(text: String) {
        binding.statusText.text = text
    }

    private fun appendLog(text: String) {
        val current = binding.logView.text.toString()
        binding.logView.text = if (current.isBlank()) text else "$current\n$text"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
