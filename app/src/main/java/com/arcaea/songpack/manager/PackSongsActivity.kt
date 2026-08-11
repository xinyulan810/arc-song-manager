package com.arcaea.songpack.manager

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ActivityPackSongsBinding
import com.arcaea.songpack.manager.model.Pack
import com.arcaea.songpack.manager.model.SongItem
import com.arcaea.songpack.manager.ui.PackSideAdapter
import com.arcaea.songpack.manager.ui.PackSideItem
import com.arcaea.songpack.manager.ui.SongAdapter
import com.arcaea.songpack.model.SongEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 曲包内歌曲列表页
 *
 * - 网格展示歌曲(封面 + 标题 + 难度)
 * - 搜索本包歌曲、切换排序(默认顺序 / 文件夹修改时间)
 * - 编辑模式: 左侧曲包栏 + 右侧歌曲网格
 *     * 长按歌曲拖动 → 松手在左侧曲包 = 跨包移动(改 set 字段)
 *     * 松手在歌曲网格 = 同包内排序(仅默认排序模式)
 */
class PackSongsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPackSongsBinding

    private var currentPackId: String = ""
    private var packName: String = ""
    private var searchQuery: String = ""
    private var searchText: String = ""

    private var allSongs: List<SongEntry> = emptyList()
    private var packs: List<Pack> = emptyList()
    private var folderInfo: Map<String, Long> = emptyMap()
    private var displayed: List<SongItem> = emptyList()

    private var sortMode: Int = 0 // 0=默认 1=按修改时间
    private var editMode: Boolean = false

    private val songAdapter by lazy {
        SongAdapter(
            scope = lifecycleScope,
            onClick = { openSongDetail(it) },
            onLongPressDrag = { holder -> startSongDrag(holder) }
        )
    }
    private val packSideAdapter by lazy { PackSideAdapter(onClick = { pack -> switchPack(pack) }) }

    private var dragSong: SongItem? = null
    private var dragSourceId: String? = null
    /** 拖拽过程中最近高亮的曲包 id(DROP 时坐标若落在 item 间隙, 回退用它) */
    private var dragHoverPackId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackSongsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPackId = intent.getStringExtra(ManagerActivity.EXTRA_PACK_ID) ?: ""
        packName = intent.getStringExtra(ManagerActivity.EXTRA_PACK_NAME) ?: currentPackId
        searchQuery = intent.getStringExtra(ManagerActivity.EXTRA_SEARCH_QUERY) ?: ""
        searchText = searchQuery

        setupUi()
        // 数据加载统一在 onResume(首次进入 + 从详情返回都刷新); 避免重复加载
        // 进入该界面即表示要编辑, 默认直接进编辑模式
        setEditMode(true)
    }

    /** 首次进入与从歌曲详情返回时加载/刷新 */
    override fun onResume() {
        super.onResume()
        if (currentPackId.isNotBlank()) loadData()
    }

    private fun setupUi() {
        binding.toolbar.title = packName
        binding.toolbar.subtitle = "id: $currentPackId"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.songGrid.layoutManager = GridLayoutManager(this, 5)
        binding.songGrid.adapter = songAdapter

        binding.packSideList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.packSideList.adapter = packSideAdapter

        binding.btnSort.text = getString(R.string.sort_default)
        binding.btnSort.setOnClickListener {
            sortMode = if (sortMode == 0) 1 else 0
            binding.btnSort.text = if (sortMode == 0) getString(R.string.sort_default) else getString(R.string.sort_by_time)
            refreshSongs()
        }
        binding.btnEditMode.setOnClickListener { setEditMode(!editMode) }
        binding.btnDoneEdit.setOnClickListener { setEditMode(false) }

        binding.searchInput.setText(searchText)
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchText = s?.toString()?.trim() ?: ""
                refreshSongs()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 侧栏: 拖拽悬停高亮 + 松手跨包移动
        binding.packSideList.setOnDragListener { _, event -> onSideDrag(event) }
        // 网格: 松手同包排序
        binding.songGrid.setOnDragListener { _, event -> onGridDrag(event) }
    }

    // ---------- 数据加载 ----------

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val t0 = TimingLog.now()
                // songlist/packlist 并行读取
                val (songs, p) = withContext(Dispatchers.IO) {
                    val a = async { GameRepository.loadSongEntries(this@PackSongsActivity) }
                    val b = async { GameRepository.loadPacks(this@PackSongsActivity) }
                    a.await() to b.await()
                }
                TimingLog.mark("PackSongs.loadData", t0, "loadSongEntries+loadPacks(并行)")
                val t2 = TimingLog.now()
                val info = withContext(Dispatchers.IO) { GameRepository.loadSongFolderInfo(this@PackSongsActivity) }
                TimingLog.mark("PackSongs.loadData", t2, "loadSongFolderInfo(含封面)")
                allSongs = songs
                packs = GameRepository.buildPackList(this@PackSongsActivity, p, songs)
                folderInfo = info
                refreshSongs()
                refreshSideBar()
                TimingLog.mark("PackSongs.loadData", t0, "TOTAL")
            } catch (e: Exception) {
                Toast.makeText(this@PackSongsActivity, getString(R.string.load_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshSongs() {
        val base = allSongs.filter { it.set == currentPackId }
        val sorted = if (sortMode == 1) {
            base.sortedByDescending { folderInfo[it.id] ?: 0L }
        } else {
            base
        }
        displayed = sorted.map { e ->
            val t = folderInfo[e.id]
            SongItem(e, t ?: 0L, t != null)
        }.filter { matchesSearch(it) }

        songAdapter.submit(displayed)
        binding.emptyView.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.songGrid.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        binding.toolbar.subtitle = "id: $currentPackId · ${displayed.size} 首"
    }

    private fun matchesSearch(item: SongItem): Boolean {
        val q = searchText.lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        val e = item.entry
        return e.id.contains(q, true) || e.displayTitle.contains(q, true) ||
            e.artist?.contains(q, true) == true || e.bpm?.contains(q, true) == true
    }

    private fun refreshSideBar() {
        val counts = allSongs.groupBy { it.set ?: "" }.mapValues { it.value.size }
        val items = packs.map { p -> PackSideItem(p, counts[p.id] ?: 0, p.id == currentPackId) }
        packSideAdapter.submit(items, currentPackId)
    }

    /** 编辑模式点击左侧曲包 → 切换到该曲包, 显示其歌曲(便于连续拖拽管理) */
    private fun switchPack(pack: Pack) {
        if (currentPackId == pack.id) return
        currentPackId = pack.id
        packName = pack.displayName
        binding.toolbar.title = packName
        refreshSongs()
        refreshSideBar()
        // 上下滑动动画: 歌曲项从下往上逐个滑入
        val anim = android.view.animation.AnimationUtils.loadLayoutAnimation(
            this, com.arcaea.songpack.R.anim.layout_slide_up
        )
        binding.songGrid.layoutAnimation = anim
        binding.songGrid.scheduleLayoutAnimation()
    }

    // ---------- 编辑模式 ----------

    private fun setEditMode(on: Boolean) {
        editMode = on
        songAdapter.editMode = on
        binding.packSideList.visibility = if (on) View.VISIBLE else View.GONE
        binding.editBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEditMode.text = if (on) getString(R.string.done) else getString(R.string.edit)
        if (on) {
            refreshSideBar()
        }
    }

    // ---------- 拖拽 ----------

    private fun startSongDrag(holder: RecyclerView.ViewHolder): Boolean {
        if (!editMode) return false
        val pos = holder.bindingAdapterPosition
        if (pos < 0 || pos >= displayed.size) return false
        val item = displayed[pos]
        dragSong = item
        dragSourceId = item.id
        val data = ClipData.newPlainText("song", item.id)
        val shadow = View.DragShadowBuilder(holder.itemView)
        holder.itemView.startDragAndDrop(data, shadow, item, 0)
        return true
    }

    /** 侧栏拖拽: 高亮悬停曲包, 松手跨包移动 */
    private fun onSideDrag(event: DragEvent): Boolean {
        val song = dragSong ?: return true
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                val pack = findSidePackAt(event.x, event.y)
                dragHoverPackId = pack?.id
                packSideAdapter.setActivated(pack?.id)
                return true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                packSideAdapter.setActivated(null)
                return true
            }
            DragEvent.ACTION_DROP -> {
                packSideAdapter.setActivated(null)
                // 精确命中优先; 松手瞬间指针可能落在 item 间隙, 回退到最近高亮的曲包
                val pack = findSidePackAt(event.x, event.y)
                    ?: dragHoverPackId?.let { id -> packs.firstOrNull { it.id == id } }
                if (pack != null) {
                    moveSongToPack(song, pack)
                } else {
                    toast("未落到曲包上")
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                packSideAdapter.setActivated(null)
                dragSong = null
                dragSourceId = null
                dragHoverPackId = null
                return true
            }
            else -> return true
        }
    }

    private fun findSidePackAt(x: Float, y: Float): Pack? {
        val child = childAt(binding.packSideList, x, y) ?: return null
        val pos = binding.packSideList.getChildAdapterPosition(child)
        val item = packSideAdapter.currentItem(pos) ?: return null
        return item.pack
    }

    /** 找到落点位置下的子 View(命中范围包含 item 边距, 避免落在间隙找不到) */
    private fun childAt(recycler: RecyclerView, x: Float, y: Float): View? {
        for (i in recycler.childCount - 1 downTo 0) {
            val child = recycler.getChildAt(i) ?: continue
            val lp = child.layoutParams as? RecyclerView.LayoutParams
            val left = child.left - (lp?.leftMargin ?: 0)
            val top = child.top - (lp?.topMargin ?: 0)
            val right = child.right + (lp?.rightMargin ?: 0)
            val bottom = child.bottom + (lp?.bottomMargin ?: 0)
            val tx = child.translationX
            val ty = child.translationY
            if (x >= left + tx && x <= right + tx &&
                y >= top + ty && y <= bottom + ty
            ) {
                return child
            }
        }
        return null
    }

    /** 网格拖拽: 松手在网格上 = 同包内排序(仅默认排序模式) */
    private fun onGridDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DROP -> {
                val song = dragSong ?: return true
                reorderSong(song, event.x, event.y)
                dragSong = null
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dragSong = null
                dragSourceId = null
                return true
            }
            else -> return true
        }
    }

    private fun reorderSong(song: SongItem, x: Float, y: Float) {
        if (sortMode != 0) {
            toast("同包排序需切回默认排序")
            return
        }
        val child = childAt(binding.songGrid, x, y) ?: return
        val targetPos = binding.songGrid.getChildAdapterPosition(child)
        val oldIdx = displayed.indexOfFirst { it.id == song.id }
        if (oldIdx < 0 || oldIdx == targetPos) return

        val list = displayed.toMutableList()
        list.removeAt(oldIdx)
        list.add(targetPos.coerceIn(0, list.size), song)

        // 重建 allSongs: 该包歌曲按新顺序, 其余歌曲保持原顺序
        val packSongIds = list.map { it.id }
        val byId = allSongs.associateBy { it.id }
        val reorderedPack = packSongIds.mapNotNull { byId[it] }
        val nonPack = allSongs.filter { it.set != currentPackId }
        allSongs = nonPack + reorderedPack

        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                GameRepository.saveSonglist(this@PackSongsActivity, allSongs)
            }
            if (err == null) toast("已调整排序") else toast("保存失败: $err")
            refreshSongs()
        }
    }

    private fun moveSongToPack(song: SongItem, targetPack: Pack) {
        if (song.set == targetPack.id) {
            toast("歌曲已在「${targetPack.displayName}」")
            return
        }
        val entry = allSongs.firstOrNull { it.id == song.id } ?: return
        entry.raw.put("set", targetPack.id)
        val name = song.title.ifBlank { song.id }
        // 显示"正在移动"灰屏, 保存+刷新完成后消失
        binding.moveOverlayText.text = getString(R.string.moving_name, name)
        binding.moveOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val err = withContext(Dispatchers.IO) {
                    GameRepository.saveSonglist(this@PackSongsActivity, allSongs)
                }
                refreshSongs()
                refreshSideBar()
                if (err == null) {
                    toast("已移动「$name」到 ${targetPack.displayName}")
                } else {
                    toast("保存失败: $err")
                }
            } finally {
                binding.moveOverlay.visibility = View.GONE
            }
        }
    }

    // ---------- 导航 ----------

    private fun openSongDetail(item: SongItem) {
        if (editMode) return // 编辑模式下点击不进入详情, 避免误触
        val intent = Intent(this, SongDetailActivity::class.java)
        intent.putExtra(SongDetailActivity.EXTRA_SONG_ID, item.id)
        startActivity(intent)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
