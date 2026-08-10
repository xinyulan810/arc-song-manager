package com.arcaea.songpack.manager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.databinding.ItemPackSideBinding
import com.arcaea.songpack.manager.model.Pack

/** 编辑模式左侧曲包栏条目 */
data class PackSideItem(
    val pack: Pack,
    val songCount: Int,
    val isCurrent: Boolean
)

class PackSideAdapter(
    private val onClick: (Pack) -> Unit
) : RecyclerView.Adapter<PackSideAdapter.VH>() {

    private val items = mutableListOf<PackSideItem>()
    private var activatedId: String? = null
    private var rv: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rv = null
    }

    fun submit(list: List<PackSideItem>, currentPackId: String?) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * 高亮拖拽悬停目标。
     * 注意: 不能 notifyDataSetChanged(那会在拖拽过程中触发全量重排, 导致 DROP 时 item position 失效)。
     * 这里直接更新可见 item 视图的 isActivated。
     */
    fun setActivated(packId: String?) {
        if (activatedId != packId) {
            activatedId = packId
            updateActivatedViews()
        }
    }

    private fun updateActivatedViews() {
        val recycler = rv ?: return
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val pos = recycler.getChildAdapterPosition(child)
            val item = items.getOrNull(pos)
            child.isActivated = item?.pack?.id == activatedId
        }
    }

    fun currentItem(pos: Int): PackSideItem? = items.getOrNull(pos)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPackSideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemPackSideBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: PackSideItem) {
            b.sideName.text = item.pack.displayName
            b.sideCount.text = "${item.songCount} 首"
            b.root.isSelected = item.isCurrent
            b.root.isActivated = item.pack.id == activatedId
            b.root.setOnClickListener { onClick(item.pack) }
        }
    }
}
