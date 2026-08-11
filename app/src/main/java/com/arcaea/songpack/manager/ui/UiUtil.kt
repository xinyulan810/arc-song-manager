package com.arcaea.songpack.manager.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arcaea.songpack.R
import androidx.appcompat.app.AlertDialog

/**
 * 通用 UI 辅助: 标签行、输入框、字段编辑对话框。
 * 供曲包编辑 / 歌曲编辑复用。
 */
object UiUtil {

    enum class FieldType { TEXT, NUMBER, CHECKBOX }

    /**
     * 网格列数: 按屏幕宽度保证每张卡片可读。
     *
     * 规则:
     *  - 每列至少 [minCardWidthDp] 宽(另加 12dp 卡片间距), 由可用宽度算出最多能放几列
     *  - 手机(宽度 < 600dp): 竖屏最多 2 列, 横屏最多 3 列
     *  - 平板(宽度 >= 600dp): 按宽度排, 最多 [maxColumns] 列
     */
    fun gridColumnsFor(context: Context, minCardWidthDp: Int = 150, maxColumns: Int = 5): Int {
        val cfg = context.resources.configuration
        val widthDp = cfg.screenWidthDp
        val isTablet = widthDp >= 600
        val max = if (isTablet) maxColumns else if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
        // 减去 RecyclerView 左右 padding(12dp * 2)
        return gridColumnsForWidth(widthDp - 24, isTablet, max, minCardWidthDp)
    }

    /**
     * 基于给定可用宽度(不含左右 padding, 单位 dp)计算网格列数。
     * 供网格实际宽度与屏幕宽度不一致的场景使用(如编辑模式下有侧栏占宽)。
     */
    fun gridColumnsForWidth(
        availWidthDp: Int,
        isTablet: Boolean,
        maxColumns: Int,
        minCardWidthDp: Int = 150
    ): Int {
        val avail = availWidthDp.coerceAtLeast(1)
        val byWidth = (avail / (minCardWidthDp + 12)).coerceAtLeast(2)
        return byWidth.coerceAtMost(maxColumns)
    }

    data class FieldSpec(
        val key: String,
        val label: String,
        val initial: String = "",
        val type: FieldType = FieldType.TEXT,
        val checkedInitial: Boolean = false,
        val hint: String = ""
    )

    fun labelOf(context: Context, label: String, child: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 13f
            })
            addView(child)
        }
    }

    fun editText(context: Context, hint: String, text: String, inputType: Int): EditText {
        return EditText(context).apply {
            this.hint = hint
            setText(text)
            setSingleLine(true)
            this.inputType = inputType
        }
    }

    /**
     * 字段编辑对话框。
     * @param onSave 回调接收 键->值 (String / Boolean); 返回 String 非空表示校验失败需留在对话框
     */
    fun showFieldEditor(
        context: Context,
        title: String,
        fields: List<FieldSpec>,
        onSave: (Map<String, Any>) -> String?
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val widgets = mutableMapOf<String, View>()
        for (f in fields) {
            when (f.type) {
                FieldType.CHECKBOX -> {
                    val cb = CheckBox(context).apply {
                        text = f.label
                        isChecked = f.checkedInitial
                    }
                    container.addView(cb)
                    widgets[f.key] = cb
                }
                else -> {
                    val et = editText(
                        context,
                        f.hint.ifBlank { f.label },
                        f.initial,
                        if (f.type == FieldType.NUMBER) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
                    )
                    container.addView(labelOf(context, f.label, et))
                    widgets[f.key] = et
                }
            }
        }
        val scroll = ScrollView(context).apply { addView(container) }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                val values = mutableMapOf<String, Any>()
                for ((key, view) in widgets) {
                    when (view) {
                        is CheckBox -> values[key] = view.isChecked
                        is EditText -> values[key] = view.text.toString().trim()
                    }
                }
                val err = onSave(values)
                if (err != null) {
                    android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    /** 长按操作菜单 */
    fun showActionMenu(
        context: Context,
        title: String,
        actions: List<Pair<String, (Int) -> Unit>>  // (标题, 选中回调)
    ) {
        val items = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> actions[which].second(which) }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            .show()
    }
}
