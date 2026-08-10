package com.arcaea.songpack.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ItemClassifiedFileBinding
import com.arcaea.songpack.model.ClassifiedFile
import com.arcaea.songpack.model.FileType
import com.arcaea.songpack.util.UriUtil

/**
 * 文件识别结果列表适配器。
 * 每行: 文件名(含分辨率) | 类型 | 修改按钮 | 大小, 三列对齐。
 * 点击"修改"弹出类型选择对话框(替代原来的下拉框)。
 */
class ClassifiedFileAdapter(
    private val items: MutableList<ClassifiedFile>,
    private val typeLabels: Array<String>,
    private val onTypeChanged: (position: Int, type: FileType) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<ClassifiedFileAdapter.VH>() {

    /** bg背景 的标准分辨率提示 */
    private val bgLabelWithHint = "bg背景(标准1920x1440)"

    class VH(val binding: ItemClassifiedFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemClassifiedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val binding = holder.binding

        // 图片文件在文件名后附上分辨率, 如 "infb.jpg (1920x1440)"
        binding.fileName.text = if (item.resolution != null) {
            "${item.relativePath} (${item.resolution})"
        } else {
            item.relativePath
        }
        binding.fileSize.text = UriUtil.humanReadableSize(item.size)

        // 类型文本(背景图附加标准分辨率提醒)
        binding.typeText.text = typeLabel(item.type)

        // 修改按钮 -> 弹窗选择类型
        binding.btnModify.setOnClickListener {
            showTypeDialog(holder, item, position)
        }
    }

    private fun typeLabel(type: FileType): String {
        return if (type == FileType.BACKGROUND) {
            bgLabelWithHint
        } else {
            typeLabels.getOrElse(type.ordinal) { type.name }
        }
    }

    private fun showTypeDialog(holder: VH, item: ClassifiedFile, position: Int) {
        val context = holder.binding.root.context
        AlertDialog.Builder(context)
            .setTitle("选择文件类型")
            .setItems(typeLabels) { _, which ->
                val newType = FileType.entries[which]
                if (item.type != newType) {
                    item.type = newType
                    holder.binding.typeText.text = typeLabel(newType)
                    onTypeChanged(position, newType)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
