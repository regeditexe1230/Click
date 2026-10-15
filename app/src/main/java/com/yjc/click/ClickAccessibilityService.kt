package com.yjc.click

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClickAccessibilityService? = null
            private set
        const val TAG = "ClickService"
        private const val CLICK_DURATION_MS = 100L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FloatingService.onAccessibilityRestored()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /**
     * 点击（不等待完成）。
     * 手势固定 100ms，而点击循环每轮之后固定等待 300ms，足以覆盖手势时长。
     */
    fun click(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = buildStroke(path, CLICK_DURATION_MS) ?: return
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * 滑动并等待手势真正结束。
     *
     * dispatchGesture 是异步 API（回调式），调用后立即返回。此前循环不等它结束就进入下一轮，
     * 导致"重复次数"形同虚设：N 次滑动会在几毫秒内全部派发出去，后一次会顶掉前一次。
     *
     * @return 手势正常完成返回 true；被取消或无法派发返回 false
     */
    suspend fun swipeAndAwait(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGestureAndAwait(path, duration)
    }

    /** 回放录制路径并等待手势结束 */
    suspend fun dispatchGesturePathAndAwait(path: Path, duration: Long): Boolean =
        dispatchGestureAndAwait(path, duration)

    /**
     * 一次派发多根手指（多指并发），并等待整批手势结束。
     *
     * 每个元素是（路径, 起始时间 ms, 时长 ms）。程序执行引擎在同一个聚合窗口里
     * 攒到多条支线的动作时，会把它们合成一次调用，于是多根手指同时按在各自的位置上。
     *
     * 与 [dispatchGesturePathAndAwait] 的区别只在于这里可以带多 stroke：
     * 只有 1 条 stroke 时仍然可以走本方法，回调语义完全一样。
     *
     * @return 手势正常完成返回 true；被取消、参数非法或无法派发返回 false
     */
    suspend fun dispatchStrokesAndAwait(strokes: List<Triple<Path, Long, Long>>): Boolean {
        if (strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        var added = 0
        for ((path, startTime, duration) in strokes) {
            if (added >= GestureDescription.getMaxStrokeCount()) {
                Log.w(TAG, "too many strokes, dropping the rest")
                break
            }
            val stroke = buildStroke(path, startTime, duration) ?: continue
            builder.addStroke(stroke)
            added++
        }
        if (added == 0) return false
        return dispatchDescriptionAndAwait(builder.build())
    }

    /** 派发一条已经组装好的手势描述并等回调；失败/被取消返回 false。 */
    private suspend fun dispatchDescriptionAndAwait(
        description: GestureDescription
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val dispatched = try {
            dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture threw", e)
            false
        }
        if (!dispatched && continuation.isActive) continuation.resume(false)
    }

    /**
     * 多指笔画：在 [buildStroke] 的兜底之上，把起始时间也限制进合法区间。
     * 起始时间加上时长不能超过 [GestureDescription.getMaxGestureDuration]，
     * 否则 GestureDescription.build() 会抛异常。
     */
    private fun buildStroke(
        path: Path,
        startTime: Long,
        duration: Long
    ): GestureDescription.StrokeDescription? {
        val maxDuration = GestureDescription.getMaxGestureDuration()
        val safeStart = startTime.coerceIn(0L, (maxDuration - 1L).coerceAtLeast(0L))
        val safeDuration = duration.coerceIn(1L, (maxDuration - safeStart).coerceAtLeast(1L))
        val bounds = RectF()
        path.computeBounds(bounds, false)
        if (bounds.left < 0f || bounds.top < 0f) {
            Log.e(TAG, "Multi stroke path bounds negative, skipping: $bounds")
            return null
        }
        return GestureDescription.StrokeDescription(path, safeStart, safeDuration)
    }

    private suspend fun dispatchGestureAndAwait(path: Path, duration: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val stroke = buildStroke(path, duration)
            if (stroke == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val dispatched = try {
                dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "dispatchGesture threw", e)
                false
            }
            // dispatchGesture 同步返回 false 表示这次派发根本没被接受（例如屏幕正被触摸）
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }

    /**
     * 构造笔画，并统一做参数兜底：
     * - 坐标为负的路径必然派发失败，直接拒绝（返回 null）；
     * - 时长必须严格为正，否则 GestureDescription 抛 IllegalArgumentException
     *   （滑动时长填 0、或录制手势耗时不足 1ms 都会命中）。
     */
    private fun buildStroke(path: Path, duration: Long): GestureDescription.StrokeDescription? {
        val bounds = RectF()
        path.computeBounds(bounds, false)
        if (bounds.left < 0f || bounds.top < 0f) {
            Log.e(TAG, "Path bounds negative, skipping: $bounds")
            return null
        }
        return GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1L))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility service unbinding")
        instance = null
        // 用户在系统设置里关掉无障碍时，悬浮球还在屏幕上却点不动，交给它提示
        FloatingService.onAccessibilityLost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        FloatingService.onAccessibilityLost()
        super.onDestroy()
    }
}
