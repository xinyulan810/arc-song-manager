package com.arcaea.songpack.manager

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ActivitySongDetailBinding
import com.arcaea.songpack.manager.model.Pack
import com.arcaea.songpack.manager.ui.ImageLoader
import com.arcaea.songpack.manager.ui.UiUtil
import com.arcaea.songpack.model.SongEntry
import com.arcaea.songpack.util.UriUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 歌曲详情页
 *
 * - 封面大图
 * - 基本信息编辑(标题/艺术家/BPM/Side/背景/版本/试听等)
 * - 难度编辑(定数/加号/谱师/画师/自定义封面)
 * - 文件夹内文件列表
 * - 操作: 保存 / 移动曲包 / 重命名 id / 删除
 */
class SongDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySongDetailBinding

    private var songId: String = ""
    private var allSongs: List<SongEntry> = emptyList()
    private var entry: SongEntry? = null
    private var packs: List<Pack> = emptyList()

    /** 基本信息字段: key -> EditText */
    private val infoFields = LinkedHashMap<String, EditText>()

    /** 难度编辑器列表 */
    private data class DiffEditor(
        val index: Int,
        val ratingInput: EditText,
        val plusCheck: CheckBox,
        val chartInput: EditText,
        val jacketInput: EditText,
        val overrideCheck: CheckBox
    )

    private val diffEditors = mutableListOf<DiffEditor>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySongDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        songId = intent.getStringExtra(EXTRA_SONG_ID) ?: ""
        binding.toolbar.title = songId
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_song_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { save(); true }
                R.id.action_move -> { showMoveDialog(); true }
                R.id.action_rename -> { showRenameDialog(); true }
                R.id.action_delete -> { confirmDelete(); true }
                R.id.action_refresh -> { loadData(); true }
                else -> false
            }
        }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { GameRepository.loadSongEntries(this@SongDetailActivity) }
            val p = withContext(Dispatchers.IO) { GameRepository.loadPacks(this@SongDetailActivity) }
            allSongs = songs
            packs = GameRepository.buildPackList(this@SongDetailActivity, p, songs)
            entry = allSongs.firstOrNull { it.id == songId }
            if (entry == null) {
                Toast.makeText(this@SongDetailActivity, getString(R.string.song_not_found, songId), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            render()
        }
    }

    private fun render() {
        val e = entry ?: return
        binding.toolbar.title = "${e.displayTitle.ifBlank { e.id }}"
        binding.toolbar.subtitle = "id: ${e.id}"

        // 封面
        val ctx = this
        binding.songImage.setImageDrawable(null)
        binding.songInitial.visibility = View.GONE
        val imgUri = GameRepository.getSongJacketUri(this, e.id)
        if (imgUri != null) {
            ImageLoader.load(lifecycleScope, ctx, imgUri, binding.songImage)
        } else {
            binding.songImage.setImageDrawable(null)
            binding.songInitial.visibility = View.VISIBLE
            binding.songInitial.text = e.displayTitle.firstOrNull()?.toString()?.uppercase()
                ?: e.id.firstOrNull()?.toString()?.uppercase() ?: "?"
        }

        buildInfo()
        buildDiffs()
        buildFiles()
    }

    // ---------- 基本信息 ----------

    private fun buildInfo() {
        val container = binding.infoContainer
        container.removeAllViews()
        infoFields.clear()
        addContainerTitle(container, "基本信息（保存后生效）")
        val e = entry ?: return
        val r = e.raw
        addInfoField("title_en", "标题 (en)", r.optJSONObject("title_localized")?.optString("en") ?: "")
        addInfoField("artist", "艺术家", r.optString("artist"))
        addInfoField("bpm", "BPM (可范围如 175-230)", r.optString("bpm"))
        addInfoFieldNum("bpm_base", "基准 BPM", r.optInt("bpm_base", 0))
        addInfoFieldNum("side", "Side (0=光 1=对立)", r.optInt("side", 0))
        addInfoField("bg", "背景图名 (去扩展名)", r.optString("bg"))
        addInfoField("version", "版本", r.optString("version"))
        addInfoFieldNum("audioPreview", "试听起点 (ms)", r.optInt("audioPreview", 0))
        addInfoFieldNum("audioPreviewEnd", "试听终点 (ms)", r.optInt("audioPreviewEnd", 0))
        addInfoField("purchase", "购买标识", r.optString("purchase"))
        addInfoField("set", "所属曲包 id", r.optString("set", "base"))
    }

    private fun addInfoField(key: String, label: String, value: String) {
        val et = UiUtil.editText(this, label, value, InputType.TYPE_CLASS_TEXT)
        binding.infoContainer.addView(UiUtil.labelOf(this, label, et))
        infoFields[key] = et
    }

    private fun addInfoFieldNum(key: String, label: String, value: Int) {
        val et = UiUtil.editText(this, label, value.toString(), InputType.TYPE_CLASS_NUMBER)
        binding.infoContainer.addView(UiUtil.labelOf(this, label, et))
        infoFields[key] = et
    }

    // ---------- 难度 ----------

    private fun buildDiffs() {
        val container = binding.diffContainer
        container.removeAllViews()
        diffEditors.clear()
        val e = entry ?: return
        addContainerTitle(container, "难度（4 槽位缺一不可）")
        val diffs = e.raw.optJSONArray("difficulties") ?: return
        for (i in 0 until diffs.length()) {
            val d = diffs.getJSONObject(i)
            val rc = d.optInt("ratingClass", i)
            addContainerSubtitle(container, "${diffLabel(rc)}  (ratingClass=$rc)")

            val rating = d.optInt("rating", 0)
            val plus = d.optBoolean("ratingPlus", false)
            val chart = d.optString("chartDesigner", "")
            val jacket = d.optString("jacketDesigner", "")
            val override = d.optBoolean("jacketOverride", false)

            val ratingEt = UiUtil.editText(this, "定数 (0~12+)", rating.toString(), InputType.TYPE_CLASS_NUMBER)
            container.addView(UiUtil.labelOf(this, "定数", ratingEt))

            val plusCb = CheckBox(this).apply { text = "定数加号 (+)" ; isChecked = plus }
            container.addView(plusCb)

            val chartEt = UiUtil.editText(this, "谱师", chart, InputType.TYPE_CLASS_TEXT)
            container.addView(UiUtil.labelOf(this, "谱师", chartEt))

            val jacketEt = UiUtil.editText(this, "画师", jacket, InputType.TYPE_CLASS_TEXT)
            container.addView(UiUtil.labelOf(this, "画师", jacketEt))

            val overrideCb = CheckBox(this).apply {
                text = "自定义封面 (使用 ${rc}.jpg)"
                isChecked = override
            }
            container.addView(overrideCb)

            diffEditors.add(DiffEditor(i, ratingEt, plusCb, chartEt, jacketEt, overrideCb))
        }
    }

    // ---------- 文件列表 ----------

    private fun buildFiles() {
        val container = binding.filesContainer
        container.removeAllViews()
        val e = entry ?: return
        addContainerTitle(container, "文件夹内文件 (songs/${e.id}/)")
        val files = withContextOrEmpty(e.id)
        if (files.isEmpty()) {
            val tv = TextView(this).apply {
                text = "文件夹不存在或为空"
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }
            container.addView(tv)
            return
        }
        for (f in files) {
            val row = TextView(this).apply {
                text = "${f.name}   (${UriUtil.humanReadableSize(f.size)})"
                textSize = 13f
                setPadding(0, 4, 0, 4)
                setTextIsSelectable(true)
            }
            container.addView(row)
        }
    }

    /** 文件列表读取在 IO 线程完成 */
    private fun withContextOrEmpty(songId: String): List<GameRepository.SongFileInfo> {
        return try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) { GameRepository.listSongFiles(this@SongDetailActivity, songId) }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ---------- 保存 ----------

    private fun save() {
        val e = entry ?: return
        val r = e.raw
        // 基本信息
        for ((key, et) in infoFields) {
            val value = et.text.toString().trim()
            when (key) {
                "title_en" -> {
                    val t = r.optJSONObject("title_localized") ?: JSONObject().also { r.put("title_localized", it) }
                    t.put("en", value)
                }
                "bpm_base", "side", "audioPreview", "audioPreviewEnd" -> r.put(key, value.toIntOrNull() ?: 0)
                "set" -> r.put("set", value.ifBlank { "base" })
                else -> r.put(key, value)
            }
        }
        // 难度
        val diffs = r.optJSONArray("difficulties")
        if (diffs != null) {
            for (ed in diffEditors) {
                val d = diffs.optJSONObject(ed.index) ?: continue
                d.put("rating", ed.ratingInput.text.toString().toIntOrNull() ?: 0)
                d.put("ratingPlus", ed.plusCheck.isChecked)
                d.put("chartDesigner", ed.chartInput.text.toString())
                d.put("jacketDesigner", ed.jacketInput.text.toString())
                d.put("jacketOverride", ed.overrideCheck.isChecked)
            }
        }
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                GameRepository.saveSonglist(this@SongDetailActivity, allSongs)
            }
            if (err == null) {
                Toast.makeText(this@SongDetailActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                // 若 set 变了, 更新 songId 显示(仍在原包, 无需离开)
            } else {
                Toast.makeText(this@SongDetailActivity, getString(R.string.save_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 移动 / 重命名 / 删除 ----------

    private fun showMoveDialog() {
        val e = entry ?: return
        val names = packs.map { "${it.displayName}  (${it.id})${if (it.id == e.set) "  ←当前" else ""}" }
        val currentIdx = packs.indexOfFirst { it.id == e.set }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.move_song))
            .setSingleChoiceItems(names.toTypedArray(), if (currentIdx >= 0) currentIdx else 0) { _, _ -> }
            .setPositiveButton(getString(R.string.move)) { dialog, _ ->
                val idx = (dialog as AlertDialog).listView.checkedItemPosition
                if (idx in packs.indices) {
                    val target = packs[idx]
                    e.raw.put("set", target.id)
                    save()
                    Toast.makeText(this, getString(R.string.moved_to, target.displayName), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun showRenameDialog() {
        val e = entry ?: return
        val et = UiUtil.editText(this, "新 id", e.id, InputType.TYPE_CLASS_TEXT)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(UiUtil.labelOf(this@SongDetailActivity, "歌曲 id（也是文件夹名，修改将重命名文件夹）", et))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_song_id))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newId = et.text.toString().trim()
                if (newId.isBlank()) { Toast.makeText(this, getString(R.string.id_empty), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (newId == e.id) return@setPositiveButton
                if (allSongs.any { it.id == newId }) {
                    Toast.makeText(this, getString(R.string.id_duplicate, newId), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val re = GameRepository.renameSong(this@SongDetailActivity, allSongs, e.id, newId)
                        if (re == null) GameRepository.saveSonglist(this@SongDetailActivity, allSongs) else re
                    }
                    if (err == null) {
                        Toast.makeText(this@SongDetailActivity, getString(R.string.renamed_to, newId), Toast.LENGTH_SHORT).show()
                        songId = newId
                        loadData()
                    } else {
                        Toast.makeText(this@SongDetailActivity, getString(R.string.rename_failed, err), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun confirmDelete() {
        val e = entry ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_song_title, e.displayTitle.ifBlank { e.id }))
            .setMessage(getString(R.string.delete_song_msg, e.id))
            .setPositiveButton(getString(R.string.confirm_delete_song)) { _, _ ->
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val list = allSongs.toMutableList()
                        val de = GameRepository.deleteSong(this@SongDetailActivity, list, e.id, true)
                        if (de == null) GameRepository.saveSonglist(this@SongDetailActivity, list) else de
                    }
                    if (err == null) {
                        Toast.makeText(this@SongDetailActivity, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@SongDetailActivity, getString(R.string.delete_failed, err), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    // ---------- 辅助 ----------

    private fun addContainerTitle(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 0, 0, 6)
            setTextColor(0xFF3700B3.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
    }

    private fun addContainerSubtitle(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 12, 0, 2)
            setTextColor(0xFF00897B.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
    }

    private fun diffLabel(rc: Int): String = when (rc) {
        0 -> "PST (Past)"
        1 -> "PRS (Present)"
        2 -> "FTR (Future)"
        3 -> "BYD (Beyond)"
        else -> "难度 $rc"
    }

    companion object {
        const val EXTRA_SONG_ID = "songId"
    }
}
