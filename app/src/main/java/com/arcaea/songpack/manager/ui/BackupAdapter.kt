package com.arcaea.songpack.manager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.databinding.ItemBackupBinding
import com.arcaea.songpack.manager.BackupManager

/** 备份快照列表适配器 */
class BackupAdapter(
    private val onClick: (BackupManager.Snapshot) -> Unit,
    private val onLongClick: (BackupManager.Snapshot) -> Unit
) : RecyclerView.Adapter<BackupAdapter.VH>() {

    private val items = mutableListOf<BackupManager.Snapshot>()

    fun submit(list: List<BackupManager.Snapshot>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemBackupBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(snap: BackupManager.Snapshot) {
            b.backupTime.text = BackupManager.formatTime(snap.timestamp)
            b.backupType.text = snap.typeLabel
            b.backupLabel.text = snap.label.ifBlank { "备份" }
            b.backupContent.text = buildString {
                val parts = mutableListOf<String>()
                if (snap.hasSonglist) parts.add("songlist")
                if (snap.hasPacklist) parts.add("packlist")
                append("包含: ").append(parts.joinToString(" + "))
            }
            b.root.setOnClickListener { onClick(snap) }
            b.root.setOnLongClickListener { onLongClick(snap); true }
        }
    }
}
