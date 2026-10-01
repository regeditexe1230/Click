package com.yjc.click

import android.content.Context

/**
 * 点击/滑动配置的持久化存储。
 *
 * 坐标、时长等输入框内容按原始字符串保存，用于区分"未填写"与"填写了 0"：
 * 若按数值保存，重启后未填写的坐标会被还原成 "0.0"，使 [MainActivity.isSwipeConfigValid]
 * 误判为参数已填好，从而允许执行一次 (0,0)->(0,0) 的空滑动。
 */
object ConfigStore {

    private const val PREFS_NAME = "click_config"

    private const val KEY_IS_SWIPE_MODE = "is_swipe_mode"
    private const val KEY_IS_GESTURE_MODE = "is_gesture_mode"
    private const val KEY_SWIPE_X1 = "swipe_x1"
    private const val KEY_SWIPE_Y1 = "swipe_y1"
    private const val KEY_SWIPE_X2 = "swipe_x2"
    private const val KEY_SWIPE_Y2 = "swipe_y2"
    private const val KEY_SWIPE_DURATION = "swipe_duration"
    private const val KEY_DELAY = "delay"
    private const val KEY_REPEAT = "repeat"
    private const val KEY_INFINITE = "infinite"

    /** 界面上可持久化的配置快照，默认值与布局中的初始值保持一致。 */
    data class Snapshot(
        val isSwipeMode: Boolean = false,
        val isGestureMode: Boolean = false,
        val swipeX1: String = "",
        val swipeY1: String = "",
        val swipeX2: String = "",
        val swipeY2: String = "",
        val swipeDuration: String = "",
        val delay: String = "0",
        val repeat: String = "1",
        val infinite: Boolean = false
    )

    fun load(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Snapshot(
            isSwipeMode = prefs.getBoolean(KEY_IS_SWIPE_MODE, false),
            isGestureMode = prefs.getBoolean(KEY_IS_GESTURE_MODE, false),
            swipeX1 = prefs.getString(KEY_SWIPE_X1, "") ?: "",
            swipeY1 = prefs.getString(KEY_SWIPE_Y1, "") ?: "",
            swipeX2 = prefs.getString(KEY_SWIPE_X2, "") ?: "",
            swipeY2 = prefs.getString(KEY_SWIPE_Y2, "") ?: "",
            swipeDuration = prefs.getString(KEY_SWIPE_DURATION, "") ?: "",
            delay = prefs.getString(KEY_DELAY, "0") ?: "0",
            repeat = prefs.getString(KEY_REPEAT, "1") ?: "1",
            infinite = prefs.getBoolean(KEY_INFINITE, false)
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_SWIPE_MODE, snapshot.isSwipeMode)
            .putBoolean(KEY_IS_GESTURE_MODE, snapshot.isGestureMode)
            .putString(KEY_SWIPE_X1, snapshot.swipeX1)
            .putString(KEY_SWIPE_Y1, snapshot.swipeY1)
            .putString(KEY_SWIPE_X2, snapshot.swipeX2)
            .putString(KEY_SWIPE_Y2, snapshot.swipeY2)
            .putString(KEY_SWIPE_DURATION, snapshot.swipeDuration)
            .putString(KEY_DELAY, snapshot.delay)
            .putString(KEY_REPEAT, snapshot.repeat)
            .putBoolean(KEY_INFINITE, snapshot.infinite)
            .apply()
    }
}
