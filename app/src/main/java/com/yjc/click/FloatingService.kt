package com.yjc.click

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
import android.widget.Toast
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
    private var floatingViewReady = false
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
    private var operationPaused = false
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var touchDownCenterX = 0f
    private var touchDownCenterY = 0f
    private var targetMarker: View? = null
    private var clickTargetX = 0f
    private var clickTargetY = 0f
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

        if (!floatingViewReady || !floatingView.isAttachedToWindow) {
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
        val ballSize = (60 * resources.displayMetrics.density).toInt()

        params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
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
        params.y = (30 * resources.displayMetrics.density).toInt()

        windowManager.addView(floatingView, params)
        floatingViewReady = true

        // 拖动阈值
        val dragThreshold = (25 * resources.displayMetrics.density).toInt()

        floatingView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingView.alpha = 0.5f
                    // 记录触摸起始屏幕坐标
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    // 记录当前悬浮球位置
                    val location = IntArray(2)
                    floatingView.getLocationOnScreen(location)
                    initialX = location[0]
                    initialY = location[1]
                    touchDownCenterX = event.rawX - event.x + view.width / 2f
                    touchDownCenterY = event.rawY - event.y + view.height / 2f
                    isDragging = false
                    android.util.Log.d("FloatingService", "ACTION_DOWN rawX=${event.rawX} rawY=${event.rawY} x=${event.x} y=${event.y} initialX=$initialX initialY=$initialY")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    
                    if (!isDragging) {
                        android.util.Log.d("FloatingService", "ACTION_MOVE dx=$dx dy=$dy threshold=$dragThreshold")
                        // 超过阈值才开始拖动
                        if (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold) {
                            android.util.Log.d("FloatingService", "DRAG STARTED!")
                            isDragging = true
                            operationPaused = false
                            if (job?.isActive == true) {
                                AppConfig.running = false
                                job?.cancel()
                            }
                        }
                    }
                    
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                        val actual = floatingView.layoutParams as WindowManager.LayoutParams
                        android.util.Log.d("FloatingService", "DRAG params.x=${params.x} params.y=${params.y} actual.x=${actual.x} actual.y=${actual.y}")
                        params.x = actual.x
                        params.y = actual.y
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("FloatingService", "ACTION_UP isDragging=$isDragging mode=${AppConfig.current.mode}")
                    floatingView.alpha = 1.0f
                    
                    if (!isDragging) {
                        // 点击操作
                        if (AppConfig.current.mode == Mode.CLICK && targetMarker == null) {
                            Toast.makeText(
                                this@FloatingService,
                                "请先拖动悬浮球设到点击位置",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnTouchListener true
                        }
                        if (AppConfig.current.mode == Mode.CLICK) {
                            lastTapX = clickTargetX
                            lastTapY = clickTargetY
                        } else {
                            lastTapX = touchDownCenterX
                            lastTapY = touchDownCenterY
                        }
                        executeAction()
                    } else {
                        // 拖动结束
                        if (AppConfig.current.mode == Mode.CLICK) {
                            val half = (30 * resources.displayMetrics.density).toInt()
                            showTargetMarker((params.x + half).toFloat(), (params.y + half).toFloat())
                            snapBallToDefault()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showTargetMarker(x: Float, y: Float) {
        hideTargetMarker()
        val markerSize = (60 * resources.displayMetrics.density).toInt()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val marker = inflater.inflate(R.layout.floating_view, null) as FrameLayout
        marker.alpha = 0.35f

        val markerParams = WindowManager.LayoutParams(
            markerSize,
            markerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        markerParams.gravity = Gravity.TOP or Gravity.START
        markerParams.x = (x - markerSize / 2f).toInt()
        markerParams.y = (y - markerSize / 2f).toInt()

        windowManager.addView(marker, markerParams)
        val actualLp = marker.layoutParams as WindowManager.LayoutParams
        clickTargetX = actualLp.x + markerSize / 2f
        clickTargetY = actualLp.y + markerSize / 2f
        targetMarker = marker
    }

    private fun hideTargetMarker() {
        targetMarker?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        targetMarker = null
    }

    private fun snapBallToDefault() {
        params.x = 0
        params.y = 200
        if (floatingViewReady && floatingView.isAttachedToWindow) {
            windowManager.updateViewLayout(floatingView, params)
            val actual = floatingView.layoutParams as WindowManager.LayoutParams
            params.x = actual.x
            params.y = actual.y
        }
    }

    private fun showRecordingOverlay() {
        if (floatingViewReady && floatingView.isAttachedToWindow) {
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
            when (event.actionMasked) {
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

        if (com.yjc.click.MainActivity.floatTutorialPending) {
            com.yjc.click.MainActivity.floatTutorialPending = false
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            launch?.putExtra("show_float_tutorial", true)
            startActivity(launch)
            return
        }

        showFloatingView()
    }

    private fun executeAction() {
        android.util.Log.d("FloatingService", "executeAction called, preventExecution=${
            AppConfig.preventExecution
        }, jobIsActive=${job?.isActive}, running=${AppConfig.running}, paused=$operationPaused")
        if (AppConfig.preventExecution) {
            android.util.Log.w("FloatingService", "executeAction: blocked by preventExecution=true")
            return
        }

        val now = System.currentTimeMillis()

        if (job?.isActive == true) {
            if (now - lastTapTime < 500) {
                android.util.Log.d("FloatingService", "executeAction: double-tap while running, pausing")
                lastTapTime = 0
                AppConfig.running = false
                operationPaused = true
                job?.cancel()
                Toast.makeText(this, "操作已暂停", Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.d("FloatingService", "executeAction: first tap while running")
                lastTapTime = now
            }
            return
        }

        if (operationPaused) {
            android.util.Log.d("FloatingService", "executeAction: single tap, resuming")
            lastTapTime = now
            operationPaused = false
            startOperation()
            return
        }

        if (now - lastTapTime < 500) {
            android.util.Log.d("FloatingService", "executeAction: debounce, elapsed=${now - lastTapTime}")
            return
        }
        lastTapTime = now

        android.util.Log.d("FloatingService", "executeAction: starting")
        startOperation()
    }

    private fun startOperation() {
        val config = AppConfig.current
        val tapX = lastTapX
        val tapY = lastTapY

        AppConfig.running = true

        job = scope.launch {
            try {
                var service = ClickAccessibilityService.instance
                if (service == null) {
                    android.util.Log.w("FloatingService", "startOperation: waiting for accessibility service")
                    val deadline = System.currentTimeMillis() + 5000
                    while (service == null && System.currentTimeMillis() < deadline) {
                        delay(100)
                        service = ClickAccessibilityService.instance
                    }
                }
                if (service == null) {
                    android.util.Log.w("FloatingService", "startOperation: accessibility service timed out")
                    return@launch
                }
                android.util.Log.d("FloatingService", "startOperation: starting operation mode=${config.mode}")
                if (config.mode == Mode.SWIPE) {
                    executeSwipe(service, config)
                } else {
                    executeClick(service, config, tapX, tapY)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: new tap cancels old job
            } catch (e: Exception) {
                android.util.Log.e("FloatingService", "startOperation failed", e)
            }
        }
        android.util.Log.d("FloatingService", "startOperation: job launched")
    }

    private suspend fun executeClick(service: ClickAccessibilityService, config: AppConfig, tapX: Float, tapY: Float) {
        val half = (30 * resources.displayMetrics.density).toInt()
        var count = 0
        while (shouldContinue(config, count) && !AppConfig.preventExecution) {
            if (config.delayMs > 0) delay(config.delayMs)
            try {
                var clickX = tapX
                var clickY = tapY
                if (config.mode == Mode.CLICK) {
                    val marker = targetMarker
                    if (marker != null) {
                        val loc = IntArray(2)
                        marker.getLocationOnScreen(loc)
                        clickX = (loc[0] + half).toFloat()
                        clickY = (loc[1] + half).toFloat()
                    }
                }
                hideTargetMarker()
                service.click(clickX, clickY)
                delay(300)
            } finally {
                if (config.mode == Mode.CLICK && targetMarker == null) {
                    showTargetMarker(clickTargetX, clickTargetY)
                }
            }
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
        val firstX = maxOf(gesture.points.first().first, 0f)
        val firstY = maxOf(gesture.points.first().second, 0f)
        path.moveTo(firstX, firstY)
        for (i in 1 until gesture.points.size) {
            val x = maxOf(gesture.points[i].first, 0f)
            val y = maxOf(gesture.points[i].second, 0f)
            path.lineTo(x, y)
        }
        service.dispatchGesturePath(path, gesture.totalDuration)
    }

    private fun shouldContinue(config: AppConfig, count: Int): Boolean {
        return AppConfig.running && (config.isInfinite || count < config.repeatCount)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        AppConfig.running = false
        recordingOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        hideTargetMarker()
        if (floatingViewReady && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        floatingViewReady = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floating_service_channel"
    }
}
