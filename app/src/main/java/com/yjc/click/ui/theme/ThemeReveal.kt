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
import androidx.compose.ui.graphics.toArgb

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

    /** 是否启用系统动态取色（Material You）：从壁纸派生配色 */
    var useDynamicColor by mutableStateOf(false)

    private const val DURATION_MS = 400L

    /**
     * 动态取色开关的"让路"时长：M3 Switch 拇指滑动是 TweenSpec(100ms)，
     * 而揭示层在点击后约 20~30ms 就会盖住整屏。若不等待，开关的动画在覆盖层底下播完，
     * 用户看到的是"开关停在旧状态不动，等圆圈扫到它才跳到新状态"——就是那个"闪一下"。
     * 先让它自己动完（100ms + 余量），再抓图、翻状态、播圆形揭示。
     */
    private const val SWITCH_SETTLE_MS = 180L

    /** 等待中的动态取色切换（连点两次开关时，后一次覆盖前一次） */
    private var pendingDynamicColor: Runnable? = null

    /**
     * 切换"动态取色"：等开关自己的状态动画播完 → 抓旧画面 → 翻状态 → 播圆形揭示（和切配色同一种过渡）。
     */
    fun applyDynamicColor(activity: Activity, enabled: Boolean) {
        val handler = Handler(Looper.getMainLooper())
        pendingDynamicColor?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingDynamicColor = null
            startDynamicColorReveal(activity, enabled)
        }
        pendingDynamicColor = task
        handler.postDelayed(task, SWITCH_SETTLE_MS)
    }

    private fun startDynamicColorReveal(activity: Activity, enabled: Boolean) {
        val window = activity.window
        val view = window?.decorView
        if (window == null || view == null || view.width <= 0 || view.height <= 0) {
            useDynamicColor = enabled
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val rnd = java.util.Random()
                // 状态切换交给 onReady：等覆盖层挂上去再改，否则会先渲染一帧新配色（闪一下）
                RevealOverlay.play(
                    activity,
                    bitmap,
                    Offset(rnd.nextInt(view.width).toFloat(), rnd.nextInt(view.height).toFloat()),
                    onReady = { useDynamicColor = enabled },
                )
            } else {
                useDynamicColor = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** 解析主题偏好字符串 → 是否深色 */
    fun resolveDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    /**
     * 从偏好把当前配色状态读进来。
     *
     * Activity 和服务都要调：悬浮球可能在 Activity 没启动（或已被回收）时就开始跑，
     * 那时若不读偏好，[useDynamicColor] 会是默认的 false、球会被画成固定紫色。
     * 深浅色必须读 [android.content.res.Resources.getSystem]（真实系统配置），
     * 不能用已经 AppCompat 覆盖过的 resources.configuration。
     */
    fun loadFrom(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        useDynamicColor = prefs.getBoolean("dynamic_color", false)
        val theme = prefs.getString("app_theme", "follow_system") ?: "follow_system"
        val systemDark = (android.content.res.Resources.getSystem().configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        isDark = resolveDark(theme, systemDark)
    }

    /**
     * View 侧界面（各类对话框、悬浮窗）用的 context。
     *
     * 这些界面是 XML/View 实现，颜色来自 Theme.Click 里写死的 md_theme_* 固定色板；
     * 开了动态取色后不能只换 Compose 那一半，否则同一个弹窗还是紫色。
     * Material Components 的 DynamicColors 会套一层 Material You 的 theme overlay，
     * 取的是同一组系统动态色令牌，所以和 Compose 侧的 dynamicLight/DarkColorScheme 一致。
     */
    fun viewContext(context: Context): Context =
        if (useDynamicColor) {
            com.google.android.material.color.DynamicColors.wrapContextIfAvailable(context)
        } else {
            context
        }

    /**
     * 动态取色下 Compose 用的 primary / onPrimary（与 ClickTheme 里的 dynamicLight/DarkColorScheme
     * 同一来源），供 View 侧（悬浮球这类非 Compose 界面）取色，保证和页面主色完全一致。
     */
    fun dynamicPrimaryArgb(context: Context): Int = dynamicScheme(context).primary.toArgb()

    fun dynamicOnPrimaryArgb(context: Context): Int = dynamicScheme(context).onPrimary.toArgb()

    private fun dynamicScheme(context: Context) = if (isDark) {
        androidx.compose.material3.dynamicDarkColorScheme(context)
    } else {
        androidx.compose.material3.dynamicLightColorScheme(context)
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
            // 圆心 = 屏幕上的随机一点（每次切换都随机）
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val rnd = java.util.Random()
            val center = if (root != null && root.width > 0 && root.height > 0) {
                Offset(
                    rnd.nextInt(root.width).toFloat(),
                    rnd.nextInt(root.height).toFloat(),
                )
            } else {
                Offset(snapshot.width / 2f, snapshot.height / 2f)
            }
            RevealOverlay.play(activity, snapshot, center)
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
        /** 正在播的揭示（连点两次时把上一次掐掉，避免两层覆盖层叠在一起） */
        private var runningAnimator: Animator? = null

        fun play(
            activity: Activity,
            snapshot: Bitmap,
            center: Offset,
            onReady: (() -> Unit)? = null,
        ) {
            runningAnimator?.cancel()
            runningAnimator = null
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
                onReady?.invoke()
                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400L
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { overlay.radius = overlay.maxRadius * (it.animatedValue as Float) }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (runningAnimator === animation) runningAnimator = null
                            (overlay.parent as? ViewGroup)?.removeView(overlay)
                            snapshot.recycle()
                        }
                    })
                    start()
                }
                runningAnimator = animator
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
