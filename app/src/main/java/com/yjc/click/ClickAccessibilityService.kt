package com.yjc.click

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClickAccessibilityService? = null
            private set
        const val TAG = "ClickService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    fun click(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        buildAndDispatch(path, 100)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        buildAndDispatch(path, duration)
    }

    fun dispatchGesturePath(path: Path, duration: Long) {
        buildAndDispatch(path, duration)
    }

    private fun buildAndDispatch(path: Path, duration: Long) {
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, false)
        if (bounds.left < 0 || bounds.top < 0) {
            Log.e(TAG, "Path bounds negative, skipping: $bounds")
            return
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), null, null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility service unbinding")
        instance = null
        return super.onUnbind(intent)
    }
}
