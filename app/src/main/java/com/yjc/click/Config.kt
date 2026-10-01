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

        @Volatile
        var recordRequested = false

        @Volatile
        var recordedGesture: RecordedGesture = RecordedGesture.EMPTY

        @Volatile
        var preventExecution = false
    }
}
