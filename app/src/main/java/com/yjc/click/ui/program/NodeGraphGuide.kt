package com.yjc.click.ui.program

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yjc.click.FontManager
import com.yjc.click.R
import com.yjc.click.ui.theme.AppTheme

/**
 * 节点图的第一次使用说明。
 *
 * 和首次启动的使用说明（`MainActivity.showFirstLaunchDialog`）是同一套样式：
 * [MaterialAlertDialogBuilder] + 标题 + 分点正文 + 知道了，字体也跟着全局字体走。
 *
 * 两个细节：
 * - 只弹一次（写在 settings 里），之后无论进哪个程序都不再弹；
 * - 「知道了」在弹出来的 5 秒内不可点，按钮文字上带倒计时，倒计时走完才放开。
 *   目的是让人至少扫一眼，而不是无脑连点。
 */
internal object NodeGraphGuide {

    private const val PREFS = "settings"
    private const val KEY_SEEN = "node_graph_guide_seen"

    /** 按钮锁定时长：5 秒，和用户的要求一致。 */
    private const val LOCK_MS = 5_000L

    fun showOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEN, false)) return
        // 先落标记再弹：万一弹窗过程中被杀掉，也不会变成每次进都弹。
        prefs.edit().putBoolean(KEY_SEEN, true).apply()

        val dialog = MaterialAlertDialogBuilder(AppTheme.viewContext(context))
            .setTitle(R.string.program_guide_title)
            .setMessage(R.string.program_guide_message)
            .setPositiveButton(R.string.ok, null)
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)

        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val handler = Handler(Looper.getMainLooper())
        var left = (LOCK_MS / 1000L).toInt()

        fun render() {
            button.text = if (left > 0) {
                context.getString(R.string.program_guide_ok_wait, left)
            } else {
                context.getString(R.string.ok)
            }
        }

        // 锁住期间点它没有任何反应：不关弹窗，也不排队一次点击。
        button.isEnabled = false
        render()

        val tick = object : Runnable {
            override fun run() {
                left -= 1
                if (left > 0) {
                    render()
                    handler.postDelayed(this, 1000L)
                } else {
                    button.text = context.getString(R.string.ok)
                    button.isEnabled = true
                    button.setOnClickListener { dialog.dismiss() }
                }
            }
        }
        handler.postDelayed(tick, 1000L)
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
    }
}

/**
 * 进节点图时挂上 [NodeGraphGuide]：等进场动画走完再弹，别和「卡片放大」抢前 0.5 秒。
 */
@Composable
internal fun NodeGraphGuideEffect() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(700)
        NodeGraphGuide.showOnce(context)
    }
}
