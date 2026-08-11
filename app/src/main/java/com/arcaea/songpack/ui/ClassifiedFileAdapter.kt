package com.arcaea.songpack.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    /** 窄屏时 bg 类型用短标签, 避免挤占文件名列 */
    private val bgLabelShort = "bg背景"

    /** 是否为窄屏(<600dp): 窄屏压缩类型/大小列, 让文件名列显示更多 */
    private var isNarrow: Boolean = false

    class VH(val binding: ItemClassifiedFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemClassifiedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val widthDp = parent.context.resources.configuration.screenWidthDp
        isNarrow = widthDp < 600
        if (isNarrow) {
            val density = parent.context.resources.displayMetrics.density
            // 窄屏: 省略文件大小列; 布局 = 第一列占满剩余空间左对齐, 第二列(类型)固定宽右对齐, 第三列(修改)靠右
            binding.fileSize.visibility = View.GONE

            // 第一列: 占满剩余空间, 文本左对齐
            val nameParams = binding.fileName.layoutParams as LinearLayout.LayoutParams
            nameParams.width = 0
            nameParams.weight = 1f
            binding.fileName.layoutParams = nameParams

            // 第二列: 固定宽度足够容纳类型文本, 文本右对齐(右边缘贴近修改按钮), 右侧留2空格
            val typeParams = binding.typeText.layoutParams as LinearLayout.LayoutParams
            typeParams.width = (110 * density).toInt()
            typeParams.rightMargin = (12 * density).toInt()
            binding.typeText.layoutParams = typeParams
            binding.typeText.gravity = android.view.Gravity.END
            binding.typeText.setEllipsize(android.text.TextUtils.TruncateAt.END)

            // 第三列: 修改按钮, 内边距缩小
            binding.btnModify.setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
        }
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val binding = holder.binding

        // 只显示文件名(去掉文件夹前缀), 图片文件在文件名后附上分辨率, 如 "infb.jpg (1920x1440)"
        val name = item.relativePath.substringAfterLast('/').substringAfterLast('\\')
        binding.fileName.text = if (item.resolution != null) {
            "$name (${item.resolution})"
        } else {
            name
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
            if (isNarrow) bgLabelShort else bgLabelWithHint
        } else {
            typeLabels.getOrElse(type.ordinal) { type.name }
        }
    }

    private fun showTypeDialog(holder: VH, item: ClassifiedFile, position: Int) {
        val context = holder.binding.root.context
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.select_file_type))
            .setItems(typeLabels) { _, which ->
                val newType = FileType.entries[which]
                if (item.type != newType) {
                    item.type = newType
                    holder.binding.typeText.text = typeLabel(newType)
                    onTypeChanged(position, newType)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }
}
