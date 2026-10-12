package com.yjc.click

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.MotionEvent
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
        stopTouchObservation()
        instance = null
        // 用户在系统设置里关掉无障碍时，悬浮球还在屏幕上却点不动，交给它提示
        FloatingService.onAccessibilityLost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopTouchObservation()
        instance = null
        FloatingService.onAccessibilityLost()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 手势录制：在不显示任何窗口的前提下拿到用户的真实触摸
    //
    // Android 13（API 33）起可以用 TouchInteractionController：服务临时请求
    // FLAG_REQUEST_TOUCH_EXPLORATION_MODE，系统就会把这一整段触摸事件交给服务
    // （因此触摸不会再传给前台应用——这是平台限制，录完由 Click 把这一划回放出去）。
    // 低版本没有这个 API，由 FloatingService 用不可见的捕获层兜底。
    // ------------------------------------------------------------------

    private var touchController: TouchInteractionController? = null
    private var touchCallback: TouchInteractionController.Callback? = null
    private var flagsBeforeObservation = -1

    /** 当前系统是否支持「无窗口触摸观察」 */
    fun supportsTouchObservation(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 开始观察触摸。回调里拿到的就是用户这一划的原始事件流（DOWN/MOVE/UP）。
     * @return 是否成功进入观察状态
     */
    @SuppressLint("NewApi")
    fun startTouchObservation(onEvent: (MotionEvent) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (touchCallback != null) return true

        val current = serviceInfo ?: run {
            Log.e(TAG, "startTouchObservation: serviceInfo is null")
            return false
        }
        flagsBeforeObservation = current.flags
        current.flags = current.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = current

        val controller = try {
            getTouchInteractionController(Display.DEFAULT_DISPLAY)
        } catch (e: Exception) {
            Log.e(TAG, "getTouchInteractionController threw", e)
            null
        }
        if (controller == null) {
            Log.e(TAG, "startTouchObservation: controller unavailable, restoring flags")
            restoreFlags()
            return false
        }

        val callback = object : TouchInteractionController.Callback {
            override fun onMotionEvent(event: MotionEvent) = onEvent(event)

            override fun onStateChanged(state: Int) {
                Log.d(TAG, "touch state -> ${TouchInteractionController.stateToString(state)}")
            }
        }
        controller.registerCallback(mainExecutor, callback)
        touchController = controller
        touchCallback = callback
        Log.d(
            TAG,
            "touch observation started, flags=0x${current.flags.toString(16)}, state=${
                TouchInteractionController.stateToString(controller.state)
            }"
        )
        return true
    }

    /** 结束观察并立刻把触摸探索恢复原状（否则全屏的点按都会失效） */
    @SuppressLint("NewApi")
    fun stopTouchObservation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = touchCallback
        if (callback != null) {
            try {
                touchController?.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterCallback failed", e)
            }
        }
        touchCallback = null
        touchController = null
        restoreFlags()
    }

    @SuppressLint("NewApi")
    private fun restoreFlags() {
        if (flagsBeforeObservation < 0) return
        val saved = flagsBeforeObservation
        flagsBeforeObservation = -1
        val current = serviceInfo ?: return
        current.flags = saved
        serviceInfo = current
        Log.d(TAG, "touch observation flags restored to 0x${saved.toString(16)}")
    }
}
