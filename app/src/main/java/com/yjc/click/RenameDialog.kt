package com.yjc.click

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yjc.click.ui.theme.AppTheme

/**
 * 重命名弹窗（手势预设、应用背景预设共用）。
 *
 * 与其它弹窗同一套写法：MaterialAlertDialogBuilder + [FontManager.applyFontToDialog]（不改窗口动画）。
 * 内容是一个预填当前名字的输入框 + 取消 / 确定；确定时回调**去掉首尾空格**的新名字，
 * 清空后确定就是传空串——调用方把它当成"没有自定义名"，回到「手势一 / 预设一」这种自动编号名。
 */
object RenameDialog {

    fun show(
        context: Context,
        titleRes: Int,
        currentName: String,
        onConfirm: (String) -> Unit,
    ) {
        val ctx = AppTheme.viewContext(context)
        val density = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            setText(currentName)
            hint = ctx.getString(R.string.rename_field)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setSelection(text.length)
        }
        // 输入框自带左右留白，和弹窗的标题/按钮对齐
        val container = FrameLayout(ctx).apply {
            setPadding((24 * density).toInt(), (4 * density).toInt(), (24 * density).toInt(), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                onConfirm(input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }
}
