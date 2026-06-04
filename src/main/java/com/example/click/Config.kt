package com.example.click

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

        @Volatile
        var recordRequested = false

        @Volatile
        var recordedGesture: RecordedGesture = RecordedGesture.EMPTY

        @Volatile
        var preventExecution = false
    }
}
