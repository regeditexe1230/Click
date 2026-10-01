package com.yjc.click.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 配色切换的"圆形揭示"过渡。
 *
 * 旧实现切配色是 `AppCompatDelegate.setDefaultNightMode()` 直接重建 Activity，硬切没有过渡。
 * 现在：
 * 1. 主题深浅由 [AppTheme.isDark] 这个 Compose 状态驱动（Activity 声明 configChanges="uiMode"，不重建）；
 * 2. 切换前用 [PixelCopy] 从窗口抓一张当前画面——必须走 GPU 侧抓取：Compose 内容画在硬件层上，
 *    `View.drawToBitmap()` 得到的是空图，会让圆外一片漆黑（之前"熊猫配色"就是这个原因）；
 * 3. 抓图成功后再改主题，动画期间圆外继续画这张旧图、圆内画新配色内容，
 *    圆从点击位置向外长到铺满屏幕——即"新配色从一个点扩散开"。
 */
object AppTheme {
    /** 当前渲染使用的深浅色（由 app_theme 偏好或系统设置解析而来） */
    var isDark by mutableStateOf(false)

    /** 待播放的圆形揭示请求；播放完由 [ThemeReveal] 清空 */
    var pendingReveal by mutableStateOf<RevealRequest?>(null)

    /** 解析主题偏好字符串 → 是否深色 */
    fun resolveDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    /**
     * 应用主题偏好：先抓旧画面 → 持久化 → 让 View 侧（各类对话框）跟随 → 请求一次圆形揭示。
     * @param origin 揭示圆心（屏幕像素坐标，一般是手指点击的位置）
     */
    fun apply(activity: Activity, theme: String, systemDark: Boolean, origin: Offset?) {
        val target = resolveDark(theme, systemDark)
        val window = activity.window
        val view = window?.decorView
        if (window == null || view == null || view.width <= 0 || view.height <= 0) {
            commit(activity, theme, target, null, origin)
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            window,
            bitmap,
            { result ->
                commit(
                    activity,
                    theme,
                    target,
                    if (result == PixelCopy.SUCCESS) bitmap else null,
                    origin,
                )
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun commit(
        activity: Activity,
        theme: String,
        target: Boolean,
        snapshot: Bitmap?,
        origin: Offset?,
    ) {
        activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
            .edit().putString("app_theme", theme).apply()
        // View 侧跟随（Activity 声明了 configChanges="uiMode"，不会重建，动画不会被打断）
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        if (snapshot != null && target != isDark) {
            pendingReveal = RevealRequest(snapshot.asImageBitmap(), origin ?: Offset.Zero)
        }
        isDark = target
    }
}

/** 一次圆形揭示：旧画面位图 + 圆心 */
class RevealRequest(val snapshot: ImageBitmap, val center: Offset)

/**
 * 把内容包在圆形揭示里：动画期间圆外显示旧配色快照、圆内显示当前（新配色）内容。
 */
@Composable
fun ThemeReveal(content: @Composable () -> Unit) {
    val request = AppTheme.pendingReveal
    // 每个请求都新建 Animatable，且初值就是 0：请求出现后的第一帧已经是"旧画面盖满屏幕"，
    // 不会先按"动画已完成"画一帧新配色（否则会闪一下）。
    val progress = remember(request) { Animatable(if (request == null) 1f else 0f) }

    LaunchedEffect(request) {
        if (request != null) {
            progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            AppTheme.pendingReveal = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val req = request ?: return@drawWithContent
                val w = size.width
                val h = size.height
                // 半径取到四个角的最远距离，任意圆心都能铺满
                val maxRadius = maxOf(
                    (req.center - Offset(0f, 0f)).getDistance(),
                    (req.center - Offset(w, 0f)).getDistance(),
                    (req.center - Offset(0f, h)).getDistance(),
                    (req.center - Offset(w, h)).getDistance(),
                )
                val radius = maxRadius * progress.value
                if (radius >= maxRadius) return@drawWithContent
                val hole = Path().apply {
                    addOval(Rect(center = req.center, radius = radius))
                }
                clipPath(hole, clipOp = ClipOp.Difference) {
                    drawImage(
                        image = req.snapshot,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        dstOffset = IntOffset(0, 0),
                    )
                }
            },
    ) {
        content()
    }
}
