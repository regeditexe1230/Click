package com.yjc.click

enum class Mode { CLICK, SWIPE }
enum class SwipeMethod { MANUAL, GESTURE }

data class RecordedGesture(
    val points: List<Pair<Float, Float>> = emptyList(),
    val totalDuration: Long = 0L
) {
    companion object {
        val EMPTY = RecordedGesture()
    }
}

data class AppConfig(
    val mode: Mode = Mode.CLICK,
    val swipeMethod: SwipeMethod = SwipeMethod.MANUAL,
    val swipeX1: Float = 0f,
    val swipeY1: Float = 0f,
    val swipeX2: Float = 0f,
    val swipeY2: Float = 0f,
    val swipeDuration: Long = 0L,
    val delayMs: Long = 0L,
    val repeatCount: Int = 1
) {
    val isInfinite: Boolean get() = repeatCount < 0

    companion object {
        @Volatile
        var current: AppConfig = AppConfig()

        @Volatile
        var running = false

        /**
         * 是否正在执行点击/滑动循环。
         * 与 [running] 区分：running 表示"悬浮球会话已启动"（球已部署，可能只是在等用户点击），
         * operationActive 才表示循环真的在跑，用于向用户展示准确的通知状态。
         */
        @Volatile
        var operationActive = false

        /**
         * 手势录制会话是否活着（等待用户点通知开始 / 正在录制）。
         * 由 FloatingService 维护，MainActivity 在回到前台时用它撤销悬挂的会话。
         */
        @Volatile
        var recordSessionActive = false

        @Volatile
        var recordedGesture: RecordedGesture = RecordedGesture.EMPTY

        @Volatile
        var preventExecution = false
    }
}

/**
 * 各种「提示过一次就别再烦用户」的开关。
 *
 * 和 [AppConfig] 不同，这里要落盘：用户勾了「不再提醒」之后，重启应用、重装（不清数据）都不该再弹。
 * 只有两个提示走这里：
 * - 悬浮通知（录制横幅）被关掉 —— 点「添加手势」时检查
 * - 后台弹出界面被关掉 —— 表现为「录完回不到 Click」，只能事后回到前台时提示
 */
object PromptFlags {
    private const val PREFS = "click_prompt_flags"
    private const val KEY_HEADS_UP_MUTED = "notification_heads_up_muted"
    private const val KEY_BACK_POPUP_MUTED = "back_popup_muted"
    private const val KEY_RETURN_BLOCKED = "return_blocked"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /** 用户勾过「不再提醒」：以后不再检查悬浮通知 */
    fun isHeadsUpMuted(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_HEADS_UP_MUTED, false)

    fun setHeadsUpMuted(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEADS_UP_MUTED, value).apply()
    }

    /** 用户勾过「不再提醒」：以后不再提示「后台弹出界面」 */
    fun isBackPopupMuted(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_BACK_POPUP_MUTED, false)

    fun setBackPopupMuted(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACK_POPUP_MUTED, value).apply()
    }

    /**
     * 上次录制结束时没能自动跳回前台（MIUI/HyperOS 的「后台弹出界面」被关）。
     * 系统不给普通应用读这个开关的接口，所以只能拿「回没回来」这件事当判据，
     * 等用户自己回到应用时再说明一次。
     */
    fun setReturnBlocked(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETURN_BLOCKED, value).apply()
    }

    /** 读一次并清掉，保证同一次「回不来」只提示一次 */
    fun consumeReturnBlocked(context: android.content.Context): Boolean {
        val value = prefs(context).getBoolean(KEY_RETURN_BLOCKED, false)
        if (value) prefs(context).edit().putBoolean(KEY_RETURN_BLOCKED, false).apply()
        return value
    }
}
