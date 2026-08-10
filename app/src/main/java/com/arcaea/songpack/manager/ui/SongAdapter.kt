package com.arcaea.songpack.manager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.databinding.ItemSongBinding
import com.arcaea.songpack.manager.GameRepository
import com.arcaea.songpack.manager.model.SongItem
import com.arcaea.songpack.model.SongDifficulty

/**
 * 歌曲网格适配器。编辑模式下长按可触发拖拽(由 Activity 的 ItemTouchHelper 接管)。
 */
class SongAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onClick: (SongItem) -> Unit,
    private val onLongPressDrag: (RecyclerView.ViewHolder) -> Boolean
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private val items = mutableListOf<SongItem>()
    var editMode: Boolean = false

    fun submit(list: List<SongItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SongItem) {
            b.songTitle.text = item.title.ifBlank { item.id }
            b.songDiff.text = buildString {
                val e = item.entry
                // 隐藏空难度(自制谱补全的槽位 rating<=1, 如 PST / PST 1), 只显示真实难度
                val realDiffs = e.difficulties.filter { it.rating > 1 }
                if (realDiffs.isNotEmpty()) {
                    append(realDiffs.joinToString(" ") { diffText(it) })
                } else {
                    append("无难度")
                }
                if (!e.artist.isNullOrBlank()) append(" ${e.artist}")
            }
            b.songImage.setImageDrawable(null)
            b.songInitial.visibility = android.view.View.GONE
            val ctx = b.root.context
            val imgUri = GameRepository.getSongJacketUri(ctx, item.id)
            if (imgUri != null) {
                ImageLoader.load(scope, ctx, imgUri, b.songImage)
            } else {
                b.songImage.setImageDrawable(null)
                b.songInitial.visibility = android.view.View.VISIBLE
                b.songInitial.text = item.title.firstOrNull()?.toString()?.uppercase()
                    ?: item.id.firstOrNull()?.toString()?.uppercase() ?: "?"
            }
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener {
                if (editMode) onLongPressDrag(this) else false
            }
        }
    }

    private fun diffText(d: SongDifficulty): String {
        val label = when (d.ratingClass) {
            0 -> "PST"
            1 -> "PRS"
            2 -> "FTR"
            3 -> "BYD"
            else -> "?"
        }
        if (d.rating <= 0) return label
        return "$label ${d.rating}${if (d.ratingPlus) "+" else ""}"
    }
}
