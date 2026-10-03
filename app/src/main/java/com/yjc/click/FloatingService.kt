package com.yjc.click

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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
    private var dynamicColorWatcher: Job? = null
    private var overlayOpWatcher: AppOpsManager.OnOpChangedListener? = null
    private var overlayRevoked = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        watchOverlayPermission()
    }

    /**
     * 监听悬浮窗权限：用户在执行过程中撤销权限时，系统会直接收走球窗口，
     * 服务若继续留在后台就成了「有通知没球」的僵尸态，这里主动停掉并提示。
     */
    private fun watchOverlayPermission() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
        val watcher = object : AppOpsManager.OnOpChangedListener {
            override fun onOpChanged(op: String?, packageName: String?) {
                if (op != AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW) return
                if (packageName != null && packageName != this@FloatingService.packageName) return
                if (!Settings.canDrawOverlays(this@FloatingService)) {
                    scope.launch { handleOverlayRevoked() }
                }
            }
        }
        try {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, watcher)
            overlayOpWatcher = watcher
        } catch (e: Exception) {
            android.util.Log.w("FloatingService", "watchOverlayPermission failed", e)
        }
    }

    private fun handleOverlayRevoked() {
        if (overlayRevoked) return
        overlayRevoked = true
        android.util.Log.w("FloatingService", "overlay permission revoked, stopping service")
        Toast.makeText(this, R.string.toast_overlay_revoked, Toast.LENGTH_LONG).show()
        AppConfig.running = false
        AppConfig.operationActive = false
        job?.cancel()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「停止」按钮：直接结束服务，无需切回 App
        if (intent?.action == ACTION_STOP) {
            AppConfig.running = false
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

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
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val text = when {
            AppConfig.recordRequested -> getString(R.string.notification_recording)
            AppConfig.operationActive && AppConfig.current.isInfinite -> getString(R.string.notification_infinite)
            AppConfig.operationActive -> getString(R.string.notification_running)
            else -> getString(R.string.notification_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notification_action_stop), stopServiceIntent())
            .build()
    }

    /** 点击通知回到 App */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, REQUEST_OPEN_APP, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 通知栏「停止」按钮 */
    private fun stopServiceIntent(): PendingIntent {
        val intent = Intent(this, FloatingService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, REQUEST_STOP_SERVICE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 立即刷新前台通知，使其反映当前运行状态 */
    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun showFloatingView() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_view, null) as FrameLayout
        applyFloatingIcon(floatingView)
        watchDynamicColor()
        val ballSize = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()

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
                                AppConfig.operationActive = false
                                job?.cancel()
                                updateNotification()
                            }
                        }
                    }
                    
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        clampBallToScreen()
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
                                R.string.toast_drag_to_position,
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

    /**
     * 悬浮球圆底配色：默认紫用旧版 ic_floating_icon（@color/purple_500），与重构前一致；
     * 动态取色/自选颜色都用运行时上色（圆底 = 当前配色的 primary，加号 = onPrimary）。
     * 别用"向量里写 ?attr/colorPrimary"：Service 的 theme 解析不出来，圆底会画成透明（踩过）。
     */
    private fun applyFloatingIcon(root: FrameLayout) {
        val icon = root.findViewById<android.widget.ImageView>(R.id.floatingIcon) ?: return
        val color = com.yjc.click.ui.theme.ClickColor.fromKey(com.yjc.click.ui.theme.AppTheme.colorKey)
        val plainPurple = !com.yjc.click.ui.theme.AppTheme.useDynamicColor && color.seed == null
        if (plainPurple) {
            icon.setImageResource(R.drawable.ic_floating_icon)
            return
        }
        val ballSize = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        val oval = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(com.yjc.click.ui.theme.AppTheme.currentPrimaryArgb(this@FloatingService))
            setSize(ballSize, ballSize)
        }
        // 旧向量里圆是 r=28 / 60 视口，四周各留 2dp，这里保持一致
        val inset = (2 * resources.displayMetrics.density).toInt()
        val layers = mutableListOf<android.graphics.drawable.Drawable>(
            android.graphics.drawable.InsetDrawable(oval, inset),
        )
        // 加号用 onPrimary：深色配色的 primary 是浅色，白加号会看不清
        androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, R.drawable.ic_floating_plus)
            ?.mutate()
            ?.apply {
                setTint(com.yjc.click.ui.theme.AppTheme.currentOnPrimaryArgb(this@FloatingService))
                layers += this
            }
        icon.setImageDrawable(android.graphics.drawable.LayerDrawable(layers.toTypedArray()))
    }

    /** 悬浮球常驻期间可能去设置里改配色（动态取色开关或自选颜色），服务不重建，这里订阅状态重上色 */
    private fun watchDynamicColor() {
        if (dynamicColorWatcher != null) return
        dynamicColorWatcher = scope.launch {
            androidx.compose.runtime.snapshotFlow {
                com.yjc.click.ui.theme.AppTheme.useDynamicColor to com.yjc.click.ui.theme.AppTheme.colorKey
            }.collect {
                if (floatingViewReady && floatingView.isAttachedToWindow) {
                    applyFloatingIcon(floatingView)
                }
                (targetMarker as? FrameLayout)?.let { applyFloatingIcon(it) }
            }
        }
    }

    private fun showTargetMarker(x: Float, y: Float) {
        hideTargetMarker()
        val markerSize = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val marker = inflater.inflate(R.layout.floating_view, null) as FrameLayout
        applyFloatingIcon(marker)
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

    /**
     * 把悬浮球限制在屏幕范围内。
     *
     * 滑动模式抬手时不会回弹（只有点击模式会 snapBallToDefault），若允许拖出屏幕，
     * 悬浮球会彻底消失且再也无法拖动或点击，只能去通知栏/App 里停止服务重开。
     */
    private fun clampBallToScreen() {
        val metrics = resources.displayMetrics
        val size = if (floatingView.width > 0) floatingView.width else (BALL_SIZE_DP * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
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
                AppConfig.operationActive = false
                operationPaused = true
                job?.cancel()
                updateNotification()
                Toast.makeText(this, R.string.toast_operation_paused, Toast.LENGTH_SHORT).show()
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
        AppConfig.operationActive = true
        updateNotification()

        job = scope.launch {
            try {
                var service = ClickAccessibilityService.instance
                if (service == null) {
                    android.util.Log.w("FloatingService", "startOperation: waiting for accessibility service")
                    val deadline = System.currentTimeMillis() + 2000
                    while (service == null && System.currentTimeMillis() < deadline) {
                        delay(100)
                        service = ClickAccessibilityService.instance
                    }
                }
                if (service == null) {
                    android.util.Log.w("FloatingService", "startOperation: accessibility service timed out")
                    // 无障碍没开时点球不能毫无反应，给个提示（旧版这里是静默返回）
                    Toast.makeText(
                        this@FloatingService,
                        R.string.toast_need_accessibility,
                        Toast.LENGTH_LONG
                    ).show()
                    // 无法执行时也要复位状态，否则通知与界面会一直停留在"执行中"
                    AppConfig.running = false
                    AppConfig.operationActive = false
                    updateNotification()
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
        finishOperation(config)
    }

    private suspend fun executeSwipe(service: ClickAccessibilityService, config: AppConfig) {
        var count = 0
        while (shouldContinue(config, count) && !AppConfig.preventExecution) {
            if (config.delayMs > 0) delay(config.delayMs)
            val completed = if (config.swipeMethod == SwipeMethod.GESTURE) {
                val gesture = AppConfig.recordedGesture
                if (gesture.points.size >= 2) {
                    replayGesture(service, gesture)
                } else {
                    false
                }
            } else {
                service.swipeAndAwait(
                    config.swipeX1, config.swipeY1,
                    config.swipeX2, config.swipeY2,
                    config.swipeDuration
                )
            }
            if (!completed) {
                android.util.Log.w("FloatingService", "executeSwipe: gesture not completed (cancelled or rejected)")
            }
            count++
            if (config.isInfinite) delay(500)
        }
        finishOperation(config)
    }

    /** 回放录制的手势，并等待手势真正结束（返回是否正常完成） */
    private suspend fun replayGesture(service: ClickAccessibilityService, gesture: RecordedGesture): Boolean {
        val path = Path()
        val firstX = maxOf(gesture.points.first().first, 0f)
        val firstY = maxOf(gesture.points.first().second, 0f)
        path.moveTo(firstX, firstY)
        for (i in 1 until gesture.points.size) {
            val x = maxOf(gesture.points[i].first, 0f)
            val y = maxOf(gesture.points[i].second, 0f)
            path.lineTo(x, y)
        }
        return service.dispatchGesturePathAndAwait(path, gesture.totalDuration)
    }

    /**
     * 循环结束后的收尾。有限次数跑完必须复位运行状态并刷新通知，
     * 否则界面会一直认为还在运行（启动按钮保持禁用、通知停在旧状态）。
     */
    private fun finishOperation(config: AppConfig) {
        AppConfig.running = false
        AppConfig.operationActive = false
        if (config.isInfinite) {
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun shouldContinue(config: AppConfig, count: Int): Boolean {
        return AppConfig.running && (config.isInfinite || count < config.repeatCount)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        overlayOpWatcher?.let {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            try {
                appOps?.stopWatchingMode(it)
            } catch (e: Exception) {
                android.util.Log.w("FloatingService", "stopWatchingMode failed", e)
            }
        }
        overlayOpWatcher = null
        AppConfig.running = false
        AppConfig.operationActive = false
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
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP_SERVICE = 1
        private const val ACTION_STOP = "com.yjc.click.action.STOP"
        private const val BALL_SIZE_DP = 60
    }
}
