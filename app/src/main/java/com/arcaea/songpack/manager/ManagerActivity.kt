package com.arcaea.songpack.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ActivityManagerBinding
import com.arcaea.songpack.manager.model.Pack
import com.arcaea.songpack.manager.ui.PackAdapter
import com.arcaea.songpack.manager.ui.PackWithCount
import com.arcaea.songpack.manager.ui.UiUtil
import com.arcaea.songpack.model.SongEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 曲包列表页(管理器入口)
 *
 * - 网格展示曲包(包图 + 名称 + 歌曲数)
 * - 搜索: 按曲包名 / 歌曲标题 / 艺术家 / 歌曲id / 曲包id 过滤
 * - 曲包操作: 新建 / 编辑 / 删除 / 导入封面
 * - 点击曲包进入该包歌曲列表
 */
class ManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding

    private var allPacks: List<Pack> = emptyList()
    private var allSongs: List<SongEntry> = emptyList()
    private var searchText: String = ""

    private var pendingImagePackId: String? = null

    private val adapter by lazy {
        PackAdapter(
            scope = lifecycleScope,
            onClick = { openPack(it) },
            onLongClick = { showPackMenu(it) }
        )
    }

    private val gameDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            GameRepository.persistGameDir(this, it)
            Toast.makeText(this, getString(R.string.dir_selected), Toast.LENGTH_SHORT).show()
            loadData()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val packId = pendingImagePackId
        pendingImagePackId = null
        if (uri != null && packId != null) {
            importPackImage(packId, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = "曲包管理"
        binding.toolbar.inflateMenu(R.menu.menu_manager)
        binding.toolbar.setOnMenuItemClickListener { onMenuClick(it) }

        binding.packGrid.layoutManager = GridLayoutManager(this, 5)
        binding.packGrid.adapter = adapter

        binding.fabAddPack.setOnClickListener { showAddPackDialog() }

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchText = s?.toString()?.trim() ?: ""
                applySearch()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        if (!GameRepository.isGameDirReady(this)) {
            promptSelectDir()
        }
        // 数据加载统一在 onResume(首次进入必调, 返回子页也刷新); 避免 onCreate+onResume 重复加载
    }

    /** 从子页(歌曲列表/备份恢复)返回时刷新数据, 保证与磁盘一致 */
    override fun onResume() {
        super.onResume()
        if (GameRepository.isGameDirReady(this)) loadData()
    }

    /** 捕获崩溃堆栈: 写到游戏目录 crash.log(File 直连可用时) 或应用私有目录 */
    private fun installCrashLogger() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("=== CRASH ===\n")
                sb.append("time=").append(System.currentTimeMillis()).append('\n')
                sb.append("thread=").append(thread.name).append('\n')
                sb.append(android.util.Log.getStackTraceString(throwable))
                val target = FileStore.fileRoot?.let { java.io.File(it, "crash.log") }
                    ?: java.io.File(filesDir, "crash.log")
                target.writeText(sb.toString())
                android.util.Log.e("ManagerActivity", sb.toString())
            } catch (_: Exception) {}
            old?.uncaughtException(thread, throwable)
        }
    }

    private fun onMenuClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_dir -> {
                gameDirLauncher.launch(null)
                true
            }
            R.id.action_refresh -> {
                loadData()
                true
            }
            R.id.action_backup -> {
                startActivity(Intent(this, BackupActivity::class.java))
                true
            }
            R.id.action_restore_packlist -> {
                restorePacklist()
                true
            }
            else -> false
        }
    }

    // ---------- 数据加载 ----------

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val t0 = TimingLog.now()
                // songlist/packlist 并行读取(SAF 读取有固定 IPC 开销)
                val (packs, songs) = withContext(Dispatchers.IO) {
                    val a = async { GameRepository.loadPacks(this@ManagerActivity) }
                    val b = async { GameRepository.loadSongEntries(this@ManagerActivity) }
                    a.await() to b.await()
                }
                TimingLog.mark("Manager.loadData", t0, "loadPacks+loadSongEntries(并行)")
                val t2 = TimingLog.now()
                withContext(Dispatchers.IO) {
                    // 封面预热 + 曲包图缓存并行
                    val warm = async { GameRepository.warmupCoverCache(this@ManagerActivity, songs) }
                    val pk = async {
                        if (FileStore.fileRoot == null) {
                            val packUris = GameRepository.loadAllPackImageUris(this@ManagerActivity)
                            if (packUris.isNotEmpty()) GameCache.setAllPackImageUris(packUris)
                        }
                    }
                    warm.await()
                    pk.await()
                }
                TimingLog.mark("Manager.loadData", t2, "warmupCoverCache+packUris")
                val t3 = TimingLog.now()
                allPacks = GameRepository.buildPackList(this@ManagerActivity, packs, songs)
                allSongs = songs
                applySearch()
                TimingLog.mark("Manager.loadData", t3, "applySearch+UI")
                TimingLog.mark("Manager.loadData", t0, "TOTAL")
                binding.toolbar.subtitle = "共 ${allSongs.size} 首歌曲 / ${allPacks.size} 个曲包"
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, getString(R.string.load_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applySearch() {
        val q = searchText.lowercase(Locale.ROOT)
        val matchedSongs = if (q.isBlank()) allSongs else allSongs.filter { s ->
            s.id.contains(q, true) ||
                s.displayTitle.contains(q, true) ||
                s.artist?.contains(q, true) == true ||
                s.set?.contains(q, true) == true
        }
        val counts = matchedSongs.groupBy { it.set ?: "" }.mapValues { it.value.size }

        val shown = allPacks.filter { p ->
            q.isBlank() ||
                p.displayName.contains(q, true) ||
                p.id.contains(q, true) ||
                counts.containsKey(p.id)
        }.map { PackWithCount(it, counts[it.id] ?: 0) }

        adapter.submit(shown)
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.packGrid.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun promptSelectDir() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_game_dir_title))
            .setMessage(getString(R.string.select_game_dir_msg))
            .setPositiveButton(getString(R.string.go_select)) { _, _ -> gameDirLauncher.launch(null) }
            .setNegativeButton(getString(R.string.later_short)) { _, _ ->
                binding.emptyView.text = getString(R.string.no_game_dir)
                binding.emptyView.visibility = View.VISIBLE
            }
            .setOnCancelListener {
                binding.emptyView.text = getString(R.string.no_game_dir)
                binding.emptyView.visibility = View.VISIBLE
            }
            .show()
    }

    // ---------- 曲包导航 ----------

    private fun openPack(pack: Pack) {
        val intent = Intent(this, PackSongsActivity::class.java)
        intent.putExtra(EXTRA_PACK_ID, pack.id)
        intent.putExtra(EXTRA_PACK_NAME, pack.displayName)
        if (searchText.isNotBlank()) intent.putExtra(EXTRA_SEARCH_QUERY, searchText)
        startActivity(intent)
    }

    // ---------- 曲包操作 ----------

    private fun showPackMenu(pack: Pack) {
        UiUtil.showActionMenu(
            this,
            "「${pack.displayName}」",
            listOf(
                "编辑信息" to { editPack(pack) },
                "导入/更换封面" to {
                    pendingImagePackId = pack.id
                    pickImageLauncher.launch("image/*")
                },
                "删除曲包" to { deletePack(pack) }
            )
        )
    }

    private fun showAddPackDialog() {
        val fields = listOf(
            UiUtil.FieldSpec("id", "曲包 ID", type = UiUtil.FieldType.TEXT, hint = "唯一标识，小写字母/数字，如 mypack"),
            UiUtil.FieldSpec("name", "曲包名称 (en)", type = UiUtil.FieldType.TEXT, hint = "显示在游戏里的名称")
        )
        UiUtil.showFieldEditor(this, "新建曲包", fields) { values ->
            val id = (values["id"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: ""
            val name = (values["name"] as? String)?.trim() ?: ""
            if (id.isBlank()) return@showFieldEditor "曲包 ID 不能为空"
            if (!id.matches(Regex("[a-z0-9_]+"))) return@showFieldEditor "曲包 ID 仅支持小写字母、数字、下划线"
            if (allPacks.any { it.id == id }) return@showFieldEditor "已存在同名曲包: $id"
            val pack = Pack.createNew(id)
            pack.raw.put("name_localized", org.json.JSONObject().put("en", name.ifBlank { id }))
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    val newPacks = allPacks.toMutableList().apply { add(pack) }
                    GameRepository.savePacklist(this@ManagerActivity, newPacks)
                }
                if (err == null) {
                    Toast.makeText(this@ManagerActivity, getString(R.string.pack_created, id), Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this@ManagerActivity, getString(R.string.pack_create_failed, err), Toast.LENGTH_LONG).show()
                }
            }
            null
        }
    }

    private fun editPack(pack: Pack) {
        val fields = listOf(
            UiUtil.FieldSpec("id", "曲包 ID", pack.id, hint = "修改会同步歌曲归属与包图文件"),
            UiUtil.FieldSpec("name", "名称 (en)", pack.nameLocalized["en"] ?: ""),
            UiUtil.FieldSpec("section", "分区", pack.section, hint = "free / mainstory / mainstory2 / archive"),
            UiUtil.FieldSpec("desc", "描述 (en)", pack.descriptionLocalized["en"] ?: ""),
            UiUtil.FieldSpec("plus", "角色 id (plus_character)", pack.plusCharacter.toString(), UiUtil.FieldType.NUMBER),
            UiUtil.FieldSpec("extend", "是否扩展包 (is_extend_pack)", type = UiUtil.FieldType.CHECKBOX, checkedInitial = pack.isExtendPack),
            UiUtil.FieldSpec("banner", "自定义横幅 (custom_banner)", type = UiUtil.FieldType.CHECKBOX, checkedInitial = pack.customBanner)
        )
        UiUtil.showFieldEditor(this, "编辑曲包「${pack.displayName}」", fields) { values ->
            val newId = (values["id"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: pack.id
            if (newId.isBlank()) return@showFieldEditor "曲包 ID 不能为空"
            if (!newId.matches(Regex("[a-z0-9_]+"))) return@showFieldEditor "曲包 ID 仅支持小写字母、数字、下划线"
            val oldId = pack.id
            // 填充字段
            pack.raw.put("id", newId)
            pack.raw.put("section", values["section"] as? String ?: "free")
            pack.raw.put("is_extend_pack", values["extend"] as? Boolean ?: false)
            pack.raw.put("custom_banner", values["banner"] as? Boolean ?: false)
            pack.raw.put("plus_character", (values["plus"] as? String)?.toIntOrNull() ?: -1)
            val name = (values["name"] as? String) ?: ""
            pack.raw.put("name_localized", org.json.JSONObject().put("en", name.ifBlank { newId }))
            val desc = (values["desc"] as? String) ?: ""
            pack.raw.put("description_localized", org.json.JSONObject().put("en", desc).put("ja", desc))

            if (newId != oldId) {
                if (allPacks.any { it.id == newId }) return@showFieldEditor "已存在同名曲包: $newId"
                // 同步: 歌曲 set 字段 + 包图文件
                allSongs.filter { it.set == oldId }.forEach { it.raw.put("set", newId) }
            }
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    val es = GameRepository.savePacklist(this@ManagerActivity, allPacks)
                    if (newId != oldId) {
                        GameRepository.renamePackImage(this@ManagerActivity, oldId, newId)
                    }
                    if (es != null) es
                    else GameRepository.saveSonglist(this@ManagerActivity, allSongs)
                }
                if (err == null) {
                    Toast.makeText(this@ManagerActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this@ManagerActivity, getString(R.string.save_failed, err), Toast.LENGTH_LONG).show()
                    loadData()
                }
            }
            null
        }
    }

    private fun deletePack(pack: Pack) {
        val count = allSongs.count { it.set == pack.id }
        val actions = mutableListOf<Pair<String, (Int) -> Unit>>(
            "仅删除曲包（${count} 首歌的 set 改为 base）" to { _ -> confirmDelete(pack, false) }
        )
        if (count > 0) {
            actions.add("连歌曲删除（${count} 首：songlist 条目 + 文件夹）" to { _ -> confirmDelete(pack, true) })
        }
        UiUtil.showActionMenu(this, "删除曲包「${pack.displayName}」", actions)
    }

    private fun confirmDelete(pack: Pack, withSongs: Boolean) {
        val msg = if (withSongs) {
            "将删除曲包「${pack.displayName}」及其 ${allSongs.count { it.set == pack.id }} 首歌曲\n\n" +
                "歌曲将从 songlist 移除，且歌曲文件夹将被删除（不可恢复）！"
        } else {
            "将删除曲包「${pack.displayName}」，其中 ${allSongs.count { it.set == pack.id }} 首歌的归属改为 base"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val remainingPacks = allPacks.filter { it.id != pack.id }
                        val se = GameRepository.savePacklist(this@ManagerActivity, remainingPacks)
                        GameRepository.deletePackImage(this@ManagerActivity, pack.id)
                        if (withSongs) {
                            val songsInPack = allSongs.filter { it.set == pack.id }
                            songsInPack.forEach { s -> GameRepository.deleteSongFolder(this@ManagerActivity, s.id) }
                            GameRepository.saveSonglist(this@ManagerActivity, allSongs.filter { it.set != pack.id })
                        } else {
                            allSongs.filter { it.set == pack.id }.forEach { it.raw.put("set", "base") }
                            GameRepository.saveSonglist(this@ManagerActivity, allSongs)
                        }
                        se
                    }
                    Toast.makeText(
                        this@ManagerActivity,
                        if (err == null) "已删除" else "删除失败: $err",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadData()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun importPackImage(packId: String, uri: Uri) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                GameRepository.importPackImage(this@ManagerActivity, packId, uri)
            }
            if (err == null) {
                Toast.makeText(this@ManagerActivity, getString(R.string.cover_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ManagerActivity, getString(R.string.cover_import_failed, err), Toast.LENGTH_LONG).show()
            }
            loadData()
        }
    }

    // ---------- 备份恢复 ----------

    private fun restorePacklist() {
        val dir = GameRepository.gameSongsDir(this)
        val hasBackup = dir?.findFile("packlist.backup") != null
        if (!hasBackup) {
            Toast.makeText(this, getString(R.string.no_packlist_backup), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_packlist))
            .setMessage(getString(R.string.restore_packlist_msg))
            .setPositiveButton(getString(R.string.restore)) { _, _ ->
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val root = GameRepository.gameSongsDir(this@ManagerActivity)
                        val bak = root?.findFile("packlist.backup")
                        val text = bak?.let { GameRepository.readDocText(this@ManagerActivity, it) }
                        if (text == null) "无法读取备份文件"
                        else {
                            GameRepository.writeDocText(this@ManagerActivity, root, "packlist", text)
                            null
                        }
                    }
                    Toast.makeText(
                        this@ManagerActivity,
                        if (err == null) "已恢复 packlist" else "恢复失败: $err",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadData()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    companion object {
        const val EXTRA_PACK_ID = "packId"
        const val EXTRA_PACK_NAME = "packName"
        const val EXTRA_SEARCH_QUERY = "searchQuery"
    }
}
