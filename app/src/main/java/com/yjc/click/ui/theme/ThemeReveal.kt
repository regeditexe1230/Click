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

    /** 固定配色下选中的颜色（见 [ClickColor]），动态取色开启时不起作用 */
    var colorKey by mutableStateOf(ClickColor.PURPLE.key)

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
        if (RevealOverlay.isPlaying) {
            // 上一次揭示还在播：挂起，等它播完再重来（不打断旧动画）
            pendingAction = { startDynamicColorReveal(activity, enabled) }
            return
        }
        capture(activity) { snapshot ->
            // 状态切换交给 onReady：等覆盖层挂上去再改，否则会先渲染一帧新配色（闪一下）
            if (snapshot != null) {
                RevealOverlay.play(
                    activity, snapshot, revealCenter(activity, snapshot),
                    onReady = { useDynamicColor = enabled },
                    onFinished = { drainPending() },
                )
            } else {
                useDynamicColor = enabled
            }
        }
    }

    /** 切换固定配色的颜色：和切配色方案完全同一种圆形揭示（含"播放中不打断"的排队） */
    fun applyColor(activity: Activity, key: String) {
        // 偏好先落盘：即使动画还没播完，这次选择也不会丢
        activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
            .edit().putString("app_color", key).apply()
        if (RevealOverlay.isPlaying) {
            pendingAction = { applyColor(activity, key) }
            return
        }
        capture(activity) { snapshot ->
            val changed = key != colorKey
            colorKey = key
            if (snapshot != null && changed) {
                RevealOverlay.play(
                    activity, snapshot, revealCenter(activity, snapshot),
                    onFinished = { drainPending() },
                )
            } else {
                snapshot?.recycle()
                drainPending()
            }
        }
    }

    /** 抓一张当前窗口画面；失败或窗口不可用时回调 null */
    private fun capture(activity: Activity, onCaptured: (Bitmap?) -> Unit) {
        val window = activity.window
        val view = window?.decorView
        if (window == null || view == null || view.width <= 0 || view.height <= 0) {
            onCaptured(null)
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            onCaptured(if (result == PixelCopy.SUCCESS) bitmap else null)
        }, Handler(Looper.getMainLooper()))
    }

    /** 揭示圆心：屏幕上的随机一点（每次切换都随机） */
    private fun revealCenter(activity: Activity, snapshot: Bitmap): Offset {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val rnd = java.util.Random()
        return if (root != null && root.width > 0 && root.height > 0) {
            Offset(rnd.nextInt(root.width).toFloat(), rnd.nextInt(root.height).toFloat())
        } else {
            Offset(snapshot.width / 2f, snapshot.height / 2f)
        }
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
        colorKey = ClickColor.fromKey(prefs.getString("app_color", ClickColor.PURPLE.key)).key
        val theme = prefs.getString("app_theme", "follow_system") ?: "follow_system"
        val systemDark = (android.content.res.Resources.getSystem().configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        isDark = resolveDark(theme, systemDark)
    }

    /**
     * View 侧界面（各类对话框、悬浮窗）用的 context：动态取色套 Material You overlay，
     * 自选颜色套对应的 theme overlay（见 ThemeOverlay.Click.Color.*），默认紫不套。
     */
    fun viewContext(context: Context): Context = when {
        useDynamicColor -> com.google.android.material.color.DynamicColors.wrapContextIfAvailable(context)
        else -> {
            val overlay = ClickColor.fromKey(colorKey).overlayStyle
            if (overlay != 0) android.view.ContextThemeWrapper(context, overlay) else context
        }
    }

    /** 当前配色的 primary / onPrimary（Compose 侧同一份 scheme），供悬浮球这类 View 取色 */
    fun currentPrimaryArgb(context: Context): Int = currentScheme(context).primary.toArgb()

    fun currentOnPrimaryArgb(context: Context): Int = currentScheme(context).onPrimary.toArgb()

    private fun currentScheme(context: Context) =
        schemeFor(context, ClickColor.fromKey(colorKey), isDark, useDynamicColor)

    /**
     * 应用主题偏好：先抓旧画面 → 持久化 → 让 View 侧（各类对话框）跟随 → 播放圆形揭示。
     *
     * 如果上一次揭示还在播，这里**不打断它**：把"再走一遍"挂起来，等旧动画自己播完再重新
     * 抓图 + 翻状态 + 播新动画。之前是直接把上一次的覆盖层 cancel 掉，结果旧圆定格成一张
     * 静态图（新动画从随机点扩出去、周围新旧同色，肉眼看就是"动画停了"）。
     *
     * @param origin 揭示圆心（屏幕像素坐标，一般是手指点击的位置）
     */
    fun apply(activity: Activity, theme: String, systemDark: Boolean, origin: Offset?) {
        // 偏好先落盘：即使动画还没播完，这次选择也不会丢
        activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
            .edit().putString("app_theme", theme).apply()
        if (RevealOverlay.isPlaying) {
            pendingAction = { apply(activity, theme, systemDark, origin) }
            return
        }
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

    /** 挂起的"再来一次"（上一次揭示还没播完时的新请求），只保留最后一次选择 */
    private var pendingAction: (() -> Unit)? = null

    /** 上一次揭示播完了，把挂起的请求放掉 */
    private fun drainPending() {
        val action = pendingAction ?: return
        pendingAction = null
        action()
    }

    private fun commit(
        activity: Activity,
        theme: String,
        target: Boolean,
        snapshot: Bitmap?,
        origin: Offset?,
    ) {
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
            RevealOverlay.play(activity, snapshot, center, onFinished = { drainPending() })
        } else {
            // 没有动画可播，挂起的请求直接接着走
            drainPending()
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
        /**
         * 是否有揭示正在播。调用方靠它决定"挂起"还是"直接播"——
         * 播放中绝不会再叠一层（叠了就会把上一个动画冻成静态图）。
         */
        var isPlaying = false
            private set

        fun play(
            activity: Activity,
            snapshot: Bitmap,
            center: Offset,
            onReady: (() -> Unit)? = null,
            onFinished: (() -> Unit)? = null,
        ) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                // 没有可挂的地方（窗口已销毁）：状态照旧要落，动画跳过
                snapshot.recycle()
                onReady?.invoke()
                onFinished?.invoke()
                return
            }
            isPlaying = true
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
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400L
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { overlay.radius = overlay.maxRadius * (it.animatedValue as Float) }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            (overlay.parent as? ViewGroup)?.removeView(overlay)
                            snapshot.recycle()
                            isPlaying = false
                            onFinished?.invoke()
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
