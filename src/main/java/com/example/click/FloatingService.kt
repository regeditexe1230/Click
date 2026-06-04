package com.example.click

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private var recordingOverlay: View? = null
    private var recordingPoints = mutableListOf<Pair<Float, Float>>()
    private var recordingStartTime = 0L
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastTapTime = 0L
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)

        if (AppConfig.recordRequested && recordingOverlay == null) {
            AppConfig.recordRequested = false
            showRecordingOverlay()
            return START_STICKY
        }

        if (!::floatingView.isInitialized || !floatingView.isAttachedToWindow) {
            showFloatingView()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "点击服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val text = when {
            AppConfig.recordRequested -> "录制手势中..."
            AppConfig.current.isInfinite -> "无限循环中"
            else -> "点击悬浮球执行操作"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮球运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showFloatingView() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_view, null) as FrameLayout

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        windowManager.addView(floatingView, params)

        floatingView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingView.alpha = 0.5f
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    floatingView.alpha = 1.0f
                    if (!isDragging) {
                        executeAction()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showRecordingOverlay() {
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        recordingOverlay = inflater.inflate(R.layout.recording_overlay, null)

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(recordingOverlay, overlayParams)

        recordingPoints.clear()
        recordingStartTime = 0L

        recordingOverlay?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordingPoints.clear()
                    recordingStartTime = System.currentTimeMillis()
                    recordingPoints.add(Pair(event.rawX, event.rawY))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    recordingPoints.add(Pair(event.rawX, event.rawY))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    recordingPoints.add(Pair(event.rawX, event.rawY))
                    finishRecording()
                    true
                }
                else -> false
            }
        }
    }

    private fun finishRecording() {
        recordingOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        recordingOverlay = null

        if (recordingPoints.size < 2) {
            showFloatingView()
            return
        }

        val totalDuration = System.currentTimeMillis() - recordingStartTime
        AppConfig.recordedGesture = RecordedGesture(
            points = recordingPoints.toList(),
            totalDuration = totalDuration
        )

        recordingPoints.clear()
        showFloatingView()
    }

    private fun executeAction() {
        if (AppConfig.preventExecution) return
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) {
            job?.cancel()
            return
        }
        lastTapTime = now

        val config = AppConfig.current
        val service = ClickAccessibilityService.instance ?: return

        job?.cancel()
        job = scope.launch {
            if (config.mode == Mode.SWIPE) {
                executeSwipe(service, config)
            } else {
                executeClick(service, config)
            }
        }
    }

    private suspend fun executeClick(service: ClickAccessibilityService, config: AppConfig) {
        val offset = dpToPx(30)
        val x = (params.x + offset).toFloat()
        val y = (params.y + offset).toFloat()

        var count = 0
        while (shouldContinue(config, count) && !AppConfig.preventExecution) {
            if (config.delayMs > 0) delay(config.delayMs)
            service.click(x, y)
            count++
            if (config.isInfinite) delay(500)
        }
        if (config.isInfinite) stopSelf()
    }

    private suspend fun executeSwipe(service: ClickAccessibilityService, config: AppConfig) {
        var count = 0
        while (shouldContinue(config, count) && !AppConfig.preventExecution) {
            if (config.delayMs > 0) delay(config.delayMs)
            if (config.swipeMethod == SwipeMethod.GESTURE) {
                val gesture = AppConfig.recordedGesture
                if (gesture.points.size >= 2) {
                    replayGesture(service, gesture)
                }
            } else {
                service.swipe(
                    config.swipeX1, config.swipeY1,
                    config.swipeX2, config.swipeY2,
                    config.swipeDuration
                )
            }
            count++
            if (config.isInfinite) delay(500)
        }
        if (config.isInfinite) stopSelf()
    }

    private fun replayGesture(service: ClickAccessibilityService, gesture: RecordedGesture) {
        val path = Path()
        path.moveTo(gesture.points.first().first, gesture.points.first().second)
        for (i in 1 until gesture.points.size) {
            path.lineTo(gesture.points[i].first, gesture.points[i].second)
        }
        service.dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, gesture.totalDuration
                    )
                )
                .build(),
            null, null
        )
    }

    private fun shouldContinue(config: AppConfig, count: Int): Boolean {
        return AppConfig.running && (config.isInfinite || count < config.repeatCount)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        AppConfig.running = false
        recordingOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floating_service_channel"
    }
}
