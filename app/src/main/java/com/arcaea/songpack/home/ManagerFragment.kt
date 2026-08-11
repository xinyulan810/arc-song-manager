package com.arcaea.songpack.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ActivityManagerBinding
import com.arcaea.songpack.manager.BackupActivity
import com.arcaea.songpack.manager.FileStore
import com.arcaea.songpack.manager.GameCache
import com.arcaea.songpack.manager.GameRepository
import com.arcaea.songpack.manager.ManagerActivity
import com.arcaea.songpack.manager.PackSongsActivity
import com.arcaea.songpack.manager.TimingLog
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
 * 曲包列表页(底栏"曲包管理" Tab, 原 ManagerActivity 逻辑)
 */
class ManagerFragment : Fragment() {

    private var _binding: ActivityManagerBinding? = null
    private val binding get() = _binding!!

    private var allPacks: List<Pack> = emptyList()
    private var allSongs: List<SongEntry> = emptyList()
    private var searchText: String = ""

    private var pendingImagePackId: String? = null

    private val adapter by lazy {
        PackAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onClick = { openPack(it) },
            onLongClick = { showPackMenu(it) }
        )
    }

    private val gameDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            GameRepository.persistGameDir(requireContext(), it)
            Toast.makeText(requireContext(), getString(R.string.dir_selected), Toast.LENGTH_SHORT).show()
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installCrashLogger()

        binding.toolbar.title = "曲包管理"
        binding.toolbar.inflateMenu(R.menu.menu_manager)
        binding.toolbar.setOnMenuItemClickListener { onMenuClick(it) }

        binding.packGrid.layoutManager = GridLayoutManager(requireContext(), 5)
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

        if (!GameRepository.isGameDirReady(requireContext())) {
            promptSelectDir()
        }
    }

    /** 从子页返回/切回本 Tab 时刷新数据 */
    override fun onResume() {
        super.onResume()
        if (_binding != null && GameRepository.isGameDirReady(requireContext())) loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
                    ?: java.io.File(requireContext().filesDir, "crash.log")
                target.writeText(sb.toString())
                android.util.Log.e("ManagerFragment", sb.toString())
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
                startActivity(Intent(requireContext(), BackupActivity::class.java))
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val t0 = TimingLog.now()
                val (packs, songs) = withContext(Dispatchers.IO) {
                    val a = async { GameRepository.loadPacks(ctx) }
                    val b = async { GameRepository.loadSongEntries(ctx) }
                    a.await() to b.await()
                }
                TimingLog.mark("Manager.loadData", t0, "loadPacks+loadSongEntries(并行)")
                val t2 = TimingLog.now()
                withContext(Dispatchers.IO) {
                    val warm = async { GameRepository.warmupCoverCache(ctx, songs) }
                    val pk = async {
                        if (FileStore.fileRoot == null) {
                            val packUris = GameRepository.loadAllPackImageUris(ctx)
                            if (packUris.isNotEmpty()) GameCache.setAllPackImageUris(packUris)
                        }
                    }
                    warm.await()
                    pk.await()
                }
                TimingLog.mark("Manager.loadData", t2, "warmupCoverCache+packUris")
                val t3 = TimingLog.now()
                allPacks = GameRepository.buildPackList(ctx, packs, songs)
                allSongs = songs
                applySearch()
                TimingLog.mark("Manager.loadData", t3, "applySearch+UI")
                TimingLog.mark("Manager.loadData", t0, "TOTAL")
                binding.toolbar.subtitle = "共 ${allSongs.size} 首歌曲 / ${allPacks.size} 个曲包"
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_failed, e.message), Toast.LENGTH_LONG).show()
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
        AlertDialog.Builder(requireContext())
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
        val intent = Intent(requireContext(), PackSongsActivity::class.java)
        intent.putExtra(ManagerActivity.EXTRA_PACK_ID, pack.id)
        intent.putExtra(ManagerActivity.EXTRA_PACK_NAME, pack.displayName)
        if (searchText.isNotBlank()) intent.putExtra(ManagerActivity.EXTRA_SEARCH_QUERY, searchText)
        startActivity(intent)
    }

    // ---------- 曲包操作 ----------

    private fun showPackMenu(pack: Pack) {
        UiUtil.showActionMenu(
            requireContext(),
            "「${pack.displayName}」",
            listOf(
                "编辑信息" to { editPack(pack) },
                "更换封面（374×615，3:5）" to {
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
        UiUtil.showFieldEditor(requireContext(), "新建曲包", fields) { values ->
            val ctx = requireContext()
            val id = (values["id"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: ""
            val name = (values["name"] as? String)?.trim() ?: ""
            if (id.isBlank()) return@showFieldEditor "曲包 ID 不能为空"
            if (!id.matches(Regex("[a-z0-9_]+"))) return@showFieldEditor "曲包 ID 仅支持小写字母、数字、下划线"
            if (allPacks.any { it.id == id }) return@showFieldEditor "已存在同名曲包: $id"
            val pack = Pack.createNew(id)
            pack.raw.put("name_localized", org.json.JSONObject().put("en", name.ifBlank { id }))
            viewLifecycleOwner.lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    val newPacks = allPacks.toMutableList().apply { add(pack) }
                    GameRepository.savePacklist(ctx, newPacks)
                }
                if (err == null) {
                    Toast.makeText(ctx, getString(R.string.pack_created, id), Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(ctx, getString(R.string.pack_create_failed, err), Toast.LENGTH_LONG).show()
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
        UiUtil.showFieldEditor(requireContext(), "编辑曲包「${pack.displayName}」", fields) { values ->
            val ctx = requireContext()
            val newId = (values["id"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: pack.id
            if (newId.isBlank()) return@showFieldEditor "曲包 ID 不能为空"
            if (!newId.matches(Regex("[a-z0-9_]+"))) return@showFieldEditor "曲包 ID 仅支持小写字母、数字、下划线"
            val oldId = pack.id
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
                allSongs.filter { it.set == oldId }.forEach { it.raw.put("set", newId) }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    val es = GameRepository.savePacklist(ctx, allPacks)
                    if (newId != oldId) {
                        GameRepository.renamePackImage(ctx, oldId, newId)
                    }
                    if (es != null) es
                    else GameRepository.saveSonglist(ctx, allSongs)
                }
                if (err == null) {
                    Toast.makeText(ctx, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(ctx, getString(R.string.save_failed, err), Toast.LENGTH_LONG).show()
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
        UiUtil.showActionMenu(requireContext(), "删除曲包「${pack.displayName}」", actions)
    }

    private fun confirmDelete(pack: Pack, withSongs: Boolean) {
        val ctx = requireContext()
        val msg = if (withSongs) {
            "将删除曲包「${pack.displayName}」及其 ${allSongs.count { it.set == pack.id }} 首歌曲\n\n" +
                "歌曲将从 songlist 移除，且歌曲文件夹将被删除（不可恢复）！"
        } else {
            "将删除曲包「${pack.displayName}」，其中 ${allSongs.count { it.set == pack.id }} 首歌的归属改为 base"
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val remainingPacks = allPacks.filter { it.id != pack.id }
                        val se = GameRepository.savePacklist(ctx, remainingPacks)
                        GameRepository.deletePackImage(ctx, pack.id)
                        if (withSongs) {
                            val songsInPack = allSongs.filter { it.set == pack.id }
                            songsInPack.forEach { s -> GameRepository.deleteSongFolder(ctx, s.id) }
                            GameRepository.saveSonglist(ctx, allSongs.filter { it.set != pack.id })
                        } else {
                            allSongs.filter { it.set == pack.id }.forEach { it.raw.put("set", "base") }
                            GameRepository.saveSonglist(ctx, allSongs)
                        }
                        se
                    }
                    Toast.makeText(
                        ctx,
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
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                GameRepository.importPackImage(ctx, packId, uri)
            }
            if (err == null) {
                Toast.makeText(ctx, getString(R.string.cover_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, getString(R.string.cover_import_failed, err), Toast.LENGTH_LONG).show()
            }
            loadData()
        }
    }

    // ---------- 备份恢复 ----------

    private fun restorePacklist() {
        val ctx = requireContext()
        val dir = GameRepository.gameSongsDir(ctx)
        val hasBackup = dir?.findFile("packlist.backup") != null
        if (!hasBackup) {
            Toast.makeText(ctx, getString(R.string.no_packlist_backup), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.restore_packlist))
            .setMessage(getString(R.string.restore_packlist_msg))
            .setPositiveButton(getString(R.string.restore)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val root = GameRepository.gameSongsDir(ctx)
                        val bak = root?.findFile("packlist.backup")
                        val text = bak?.let { GameRepository.readDocText(ctx, it) }
                        if (text == null) "无法读取备份文件"
                        else {
                            GameRepository.writeDocText(ctx, root, "packlist", text)
                            null
                        }
                    }
                    Toast.makeText(
                        ctx,
                        if (err == null) "已恢复 packlist" else "恢复失败: $err",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadData()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }
}
