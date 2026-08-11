package com.arcaea.songpack.manager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.databinding.ItemPackBinding
import com.arcaea.songpack.R
import com.arcaea.songpack.manager.GameRepository
import com.arcaea.songpack.manager.model.Pack

/** 曲包网格项 */
data class PackWithCount(val pack: Pack, val songCount: Int)

class PackAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onClick: (Pack) -> Unit,
    private val onLongClick: (Pack) -> Unit
) : RecyclerView.Adapter<PackAdapter.VH>() {

    private val items = mutableListOf<PackWithCount>()

    fun submit(list: List<PackWithCount>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemPackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: PackWithCount) {
            val pack = item.pack
            b.packName.text = pack.displayName
            b.packInfo.text = buildString {
                if (pack.section.isNotBlank()) append(pack.section)
                val rawSections = pack.raw.optString("_sections", "")
                if (rawSections.isNotBlank()) append(" · $rawSections")
                append(" · ${b.root.context.getString(R.string.song_count, item.songCount)}")
            }
            b.packImage.setImageDrawable(null)
            b.packInitial.visibility = android.view.View.GONE
            val ctx = b.root.context
            // 无封面时用 base 封面(1080_select_base)作为默认占位
            val imgUri = GameRepository.getPackImageUri(ctx, pack.id)
                ?: GameRepository.getPackImageUri(ctx, "base")
            if (imgUri != null) {
                ImageLoader.load(scope, ctx, imgUri, b.packImage)
            } else {
                // 连 base 封面都没有才显示首字母占位
                b.packImage.setImageDrawable(null)
                b.packInitial.visibility = android.view.View.VISIBLE
                b.packInitial.text = pack.displayName.firstOrNull()?.toString()?.uppercase() ?: "?"
            }
            b.root.setOnClickListener { onClick(pack) }
            b.root.setOnLongClickListener { onLongClick(pack); true }
        }
    }
}
