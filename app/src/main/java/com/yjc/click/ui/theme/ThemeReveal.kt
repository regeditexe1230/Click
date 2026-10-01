package com.yjc.click.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * 配色切换的"圆形揭示"过渡。
 *
 * 旧实现切配色是 `AppCompatDelegate.setDefaultNightMode()` 直接重建 Activity，硬切没有过渡。
 * 现在：切换前抓一张当前窗口画面，改主题后把这张旧画面盖在整个窗口上，
 * 只在"从点击位置扩散开的圆"内露出新配色，圆长满全屏后撤掉覆盖层。
 *
 * 两个关键点（都是踩过的坑）：
 * 1. 抓图必须用 [PixelCopy]：Compose 内容画在硬件层上，`View.drawToBitmap()` 得到空图 → 圆外一片黑；
 * 2. 覆盖层必须是**窗口级 View**（挂在 android.R.id.content 上），不能用 Compose 的 clipPath：
 *    设置页是 Fragment（真实 View 树），Compose 的裁剪管不到它，会同时出现两套配色（"熊猫配色"）。
 */
object AppTheme {
    /** 当前渲染使用的深浅色（由 app_theme 偏好或系统设置解析而来） */
    var isDark by mutableStateOf(false)

    private const val DURATION_MS = 400L

    /** 解析主题偏好字符串 → 是否深色 */
    fun resolveDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    /**
     * 应用主题偏好：先抓旧画面 → 持久化 → 让 View 侧（各类对话框）跟随 → 播放圆形揭示。
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
        // 先落到 Compose 主题状态（新配色就位），再用旧画面做圆形揭示
        val changed = target != isDark
        isDark = target
        if (snapshot != null && changed) {
            RevealOverlay.play(activity, snapshot, origin ?: Offset.Zero)
        }
    }
}

/**
 * 窗口级圆形揭示覆盖层：整屏画旧画面，只在圆内留空（露出下层已切换好的新配色）。
 * 不吃触摸事件，动画结束自行移除。
 */
private class RevealOverlay(
    context: Context,
    private val snapshot: Bitmap,
    private val center: Offset,
) : View(context) {

    var radius = 0f
        set(value) {
            field = value
            invalidate()
        }
    var maxRadius = 0f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        if (radius >= maxRadius) return
        path.reset()
        path.addCircle(center.x, center.y, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path, Region.Op.DIFFERENCE)
        canvas.drawBitmap(snapshot, 0f, 0f, paint)
        canvas.restore()
    }

    companion object {
        fun play(activity: Activity, snapshot: Bitmap, center: Offset) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val overlay = RevealOverlay(activity, snapshot, center)
            root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            val w = root.width.toFloat()
            val h = root.height.toFloat()
            overlay.maxRadius = maxOf(
                (center - Offset(0f, 0f)).getDistance(),
                (center - Offset(w, 0f)).getDistance(),
                (center - Offset(0f, h)).getDistance(),
                (center - Offset(w, h)).getDistance(),
            )
            overlay.post {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400L
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { overlay.radius = overlay.maxRadius * (it.animatedValue as Float) }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            (overlay.parent as? ViewGroup)?.removeView(overlay)
                            snapshot.recycle()
                        }
                    })
                    start()
                }
            }
        }
    }
}

/**
 * 保留的空壳：圆形揭示现在由窗口级 [RevealOverlay] 承担，Compose 侧不再需要额外绘制。
 * 主题本身已经由 [AppTheme.isDark] 驱动，这里只是保持调用点不变。
 */
@Composable
fun ThemeReveal(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) { content() }
}
