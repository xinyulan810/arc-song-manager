package com.arcaea.songpack.manager.ui

import android.content.Context
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
