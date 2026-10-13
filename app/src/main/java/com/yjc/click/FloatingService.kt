package com.yjc.click

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.LocaleList
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    /** 录制时盖在屏幕上的半透明遮罩：既提示用户「松手结束」，也收下这一划 */
    private var captureView: View? = null
    private var recordingPending = false
    private var recordingStarting = false
    private var recordingActive = false
    private var recordingIdleJob: Job? = null
    private var returnCheckJob: Job? = null
    /** 会话开始后，等 App 退到后台再把录制通知重发一次（App 在前台时横幅会被系统压掉） */
    private var sessionNotifyJob: Job? = null
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
    private var accessibilityLost = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        createRecordingChannel()
        watchOverlayPermission()
        active = this
    }

    /** 无障碍服务被关掉时，球还留在屏幕上却没反应，这里提示一句并停掉正在执行的循环 */
    private fun handleAccessibilityLost() {
        if (accessibilityLost || !floatingViewReady) return
        accessibilityLost = true
        android.util.Log.w("FloatingService", "accessibility lost, stopping operation")
        Toast.makeText(this, R.string.toast_accessibility_revoked, Toast.LENGTH_LONG).show()
        AppConfig.running = false
        AppConfig.operationActive = false
        job?.cancel()
        updateNotification()
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

        when (intent?.action) {
            // 待录制阶段刻意不进前台：前台服务通知会被系统强制标成常驻，
            // MIUI 这类系统就不给弹横幅了。点通知开始录制时再由
            // PendingIntent.getForegroundService 把服务拉起来并提回前台。
            ACTION_PREPARE_RECORDING -> {
                if (intent.getBooleanExtra(EXTRA_FOREGROUND_FALLBACK, false)) {
                    // 兜底路径（只能用 startForegroundService 起来时）先把 5 秒规则喂饱，
                    // prepareRecordingSession 里会立刻退出前台
                    startForeground(NOTIFICATION_ID, notification)
                }
                prepareRecordingSession()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                startForeground(
                    RECORDING_NOTIFICATION_ID,
                    createRecordingNotification(recording = true, firstPost = false, ongoing = true)
                )
                beginRecordingCapture()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_RECORDING -> {
                cancelRecordingSession()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, notification)

        if (!floatingViewReady || !floatingView.isAttachedToWindow) {
            showFloatingView()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Service 的上下文默认跟**系统语言**走，这里按 App 的 per-app 语言再包一层，
     * 让录制浮层提示语、前台通知和 Toast 的文字跟 App 内选择的语言一致。
     */
    override fun attachBaseContext(newBase: Context) {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /** 录制通知单独一个高优先级渠道：点「添加手势」后要能直接以横幅弹出来点 */
    private fun createRecordingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                getString(R.string.record_notification_channel_name),
                NotificationManager.IMPORTANCE_MAX
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            // 老渠道不再使用：MIUI 那类系统会给渠道单独记一份「悬浮通知」开关，
            // 早期版本用它发过前台服务（常驻）通知，系统就把横幅关了；换新渠道拿回默认值。
            LEGACY_RECORDING_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        }
    }

    private fun createNotification(): Notification {
        // 录制会话期间悬浮球已经隐藏，这条前台通知不能再自称「悬浮球运行中」；
        // 录制本身由高优先级的录制通知负责，这里只说清楚服务还在跑。
        val sessionActive = AppConfig.recordSessionActive
        val title = if (sessionActive) getString(R.string.notification_click_service_running)
        else getString(R.string.notification_title)
        val text = when {
            sessionActive && recordingActive -> getString(R.string.notification_recording)
            sessionActive -> getString(R.string.notification_preparing_recording)
            AppConfig.operationActive && AppConfig.current.isInfinite -> getString(R.string.notification_infinite)
            AppConfig.operationActive -> getString(R.string.notification_running)
            else -> getString(R.string.notification_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notification_action_stop), stopServiceIntent())
            .build()
    }

    /**
     * 系统拦下后台启动界面时的兜底：发一条安静的通知，点一下就能回到 Click。
     * 复用 RECORDING_NOTIFICATION_ID，此刻录制通知已经撤掉，通知栏里仍然只有一条。
     */
    private fun showReturnNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.record_return_title))
            .setContentText(getString(R.string.record_return_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(RETURN_NOTIFICATION_ID, notification)
    }

    private fun warnReturnBlocked() {
        Toast.makeText(this, R.string.toast_return_blocked, Toast.LENGTH_LONG).show()
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
        // 录制会话期间状态栏只留那条录制通知，别再冒出「点击服务」这一条
        if (recordingPending || recordingStarting || recordingActive) return
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

    // ------------------------------------------------------------------
    // 手势录制会话（新流程）
    //
    // 点主页「添加手势」→ App 退到后台 + 发一条高优先级录制通知
    // → 用户切到目标应用、点通知 → 开始录制：屏幕上盖一层半透明遮罩（带操作提示），
    //   这一划由遮罩自己收下来
    // → 松手即停 → 撤遮罩、把刚录的这一划回放给目标应用一次 → 撤通知并切回 Click
    //
    // 为什么必须回放：录制时触摸被遮罩吃掉，目标应用收不到，所以松手后原样补发。
    // ------------------------------------------------------------------

    /** 点「添加手势」后调用：进入待录制状态并发通知 */
    private fun prepareRecordingSession() {
        AppConfig.running = false
        AppConfig.operationActive = false
        AppConfig.preventExecution = false
        job?.cancel()
        hideTargetMarker()
        if (floatingViewReady && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        floatingViewReady = false
        // 悬浮球在跑的话先退出前台、撤掉「点击服务」那条通知：
        // 待录制阶段不能带着前台服务通知（常驻标记会让系统不弹横幅）
        dropForegroundNotification()

        recordingPending = true
        recordingActive = false
        AppConfig.recordSessionActive = true
        // 先把 FGS 通知换成录制通知，但要等 App 退到后台：应用还在前台时系统
        // 不会为它弹横幅，通知只会静静地躺在状态栏里。
        postRecordingNotificationWhenBackground()
        android.util.Log.d("FloatingService", "recording session prepared, waiting for notification tap")
    }

    /** 等本应用退到后台再发录制通知，保证它能以横幅（heads-up）弹出来 */
    private fun postRecordingNotificationWhenBackground() {
        sessionNotifyJob?.cancel()
        sessionNotifyJob = scope.launch {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val deadline = System.currentTimeMillis() + SESSION_NOTIFY_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline && isAppForeground(am)) {
                delay(SESSION_NOTIFY_POLL_MS)
            }
            // 用户中途回到 App（cancelPendingRecording）后不能再补发通知
            if (recordingPending && !recordingActive && AppConfig.recordSessionActive) {
                // 发成普通通知（不是前台服务通知）：系统才不会把它标成常驻，
                // MIUI 等系统这时才会真的弹横幅
                showRecordingNotification(
                    recording = false, firstPost = true, ongoing = false, asForeground = false
                )
                android.util.Log.d(
                    "FloatingService",
                    "recording notification posted (heads-up), foreground=${isAppForeground(am)}"
                )
            }
        }
    }

    /** 自己是不是还在前台（前台发的通知弹不出横幅） */
    private fun isAppForeground(am: ActivityManager?): Boolean {
        // 以 Activity 生命周期为准：部分 ROM（实测小米 8.1/MIUI）后台时仍把本进程
        // 报成 IMPORTANCE_FOREGROUND，只信 importance 会导致永远等不到「已退到后台」。
        return AppForegroundTracker.isForeground
    }

    /** 点通知后调用：盖上遮罩开始抓这一划 */
    private fun beginRecordingCapture() {
        if (recordingActive || recordingStarting) return
        recordingStarting = true
        recordingPending = true
        AppConfig.recordSessionActive = true
        recordingPoints.clear()
        recordingStartTime = 0L

        scope.launch {
            if (!showRecordingOverlay()) {
                Toast.makeText(this@FloatingService, R.string.toast_enable_overlay, Toast.LENGTH_LONG).show()
                recordingStarting = false
                cancelRecordingSession()
                return@launch
            }

            recordingStarting = false
            recordingPending = false
            recordingActive = true
            showRecordingNotification(recording = true, firstPost = false)
            armRecordingIdleTimeout()
            android.util.Log.d("FloatingService", "recording started, overlay shown")
        }
    }

    /** 两种抓取机制共用的落点：DOWN 开始、MOVE 累积、UP 结束 */
    private fun handleRecordedTouch(rawX: Float, rawY: Float, action: Int) {
        if (!recordingActive) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                recordingPoints.clear()
                recordingStartTime = System.currentTimeMillis()
                recordingPoints.add(Pair(rawX, rawY))
                armRecordingIdleTimeout()
            }
            MotionEvent.ACTION_MOVE -> {
                recordingPoints.add(Pair(rawX, rawY))
                armRecordingIdleTimeout()
            }
            MotionEvent.ACTION_UP -> {
                recordingPoints.add(Pair(rawX, rawY))
                finishRecording()
            }
            MotionEvent.ACTION_CANCEL -> cancelRecordingSession()
        }
    }

    /**
     * 安全阀：录制期间系统处于触摸探索态，点按都不生效，
     * 所以闲置超时（没碰到屏幕）必须自动退出，绝不能把用户困在这个状态里。
     */
    private fun armRecordingIdleTimeout() {
        recordingIdleJob?.cancel()
        recordingIdleJob = scope.launch {
            delay(RECORD_IDLE_TIMEOUT_MS)
            if (recordingActive) {
                android.util.Log.w("FloatingService", "recording idle timeout, cancelling")
                Toast.makeText(this@FloatingService, R.string.toast_record_timeout, Toast.LENGTH_LONG).show()
                cancelRecordingSession()
            }
        }
    }

    /** 盖一层半透明遮罩：既提示用户「正在录制、松手结束」，也负责收下这一划 */
    private fun showRecordingOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (captureView != null) return true

        val view = LayoutInflater.from(this).inflate(R.layout.recording_overlay, null)
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
        view.setOnTouchListener { _, event ->
            handleRecordedTouch(event.rawX, event.rawY, event.actionMasked)
            true
        }
        return try {
            windowManager.addView(view, overlayParams)
            captureView = view
            true
        } catch (e: Exception) {
            android.util.Log.e("FloatingService", "addView(recordingOverlay) failed", e)
            false
        }
    }

    /** 关掉抓取：撤掉遮罩 */
    private fun stopCapture() {
        captureView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        captureView = null
    }

    private fun finishRecording() {
        stopCapture()
        sessionNotifyJob?.cancel()
        sessionNotifyJob = null
        recordingIdleJob?.cancel()
        recordingIdleJob = null
        recordingActive = false
        recordingPending = false
        AppConfig.recordSessionActive = false
        // 录制通知先留着，returnToApp() 里连同前台服务一起撤，
        // 免得中途出现「前台服务没有通知」的窗口期

        val points = recordingPoints.toList()
        val totalDuration = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(1L)
        recordingPoints.clear()

        if (points.size < 2) {
            android.util.Log.d("FloatingService", "recording discarded: too few points (${points.size})")
            Toast.makeText(this, R.string.toast_record_too_short, Toast.LENGTH_SHORT).show()
            returnToApp()
            return
        }

        AppConfig.recordedGesture = RecordedGesture(points = points, totalDuration = totalDuration)
        // 落盘成一条预设并选中它：主页「手势录制」里就是这条列表，重启也不会丢
        GestureStore.appendRecorded(this, points, totalDuration)
        android.util.Log.d("FloatingService", "recorded gesture: ${points.size} points, ${totalDuration}ms")

        // 录制时这一划没到目标应用手上，这里原样补发一次，让用户的动作真的生效
        scope.launch {
            delay(RECORD_REPLAY_DELAY_MS)
            val service = ClickAccessibilityService.instance
            val replayed = if (service != null) {
                replayGesture(service, AppConfig.recordedGesture)
            } else {
                false
            }
            android.util.Log.d("FloatingService", "recorded gesture replayed=$replayed")
            returnToApp()
        }
    }

    /** 用户取消（通知上的「取消」，或回到 App）：撤掉一切，但不把 App 拉到前台 */
    private fun cancelRecordingSession() {
        stopCapture()
        sessionNotifyJob?.cancel()
        sessionNotifyJob = null
        recordingIdleJob?.cancel()
        recordingIdleJob = null
        recordingPoints.clear()
        recordingPending = false
        recordingStarting = false
        recordingActive = false
        AppConfig.recordSessionActive = false
        cancelRecordingNotification()
        android.util.Log.d("FloatingService", "recording session cancelled")
        stopSelf()
    }

    /** 造出录制通知：会话期间状态栏只留这一条 */
    private fun createRecordingNotification(
        recording: Boolean,
        firstPost: Boolean,
        ongoing: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!firstPost)
            .addAction(0, getString(R.string.notification_action_cancel), cancelRecordingIntent())
        if (recording) {
            builder.setContentTitle(getString(R.string.record_notification_title_recording))
                .setContentText(getString(R.string.record_notification_text_recording))
        } else {
            builder.setContentTitle(getString(R.string.record_notification_title))
                .setContentText(getString(R.string.record_notification_text_pending))
                .setContentIntent(startRecordingIntent())
        }
        return builder.build()
    }

    /**
     * 发/更新录制通知。asForeground = true 时它同时是前台服务通知（录制中要保证进程活着、
     * 也保证注入的手势不被打断）；待录制阶段必须走普通通知，否则系统会强制把它标成常驻，
     * MIUI 这类系统就不弹横幅了。
     */
    private fun showRecordingNotification(
        recording: Boolean,
        firstPost: Boolean = true,
        ongoing: Boolean = true,
        asForeground: Boolean = true
    ) {
        val notification = createRecordingNotification(recording, firstPost, ongoing)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (asForeground) {
            startForeground(RECORDING_NOTIFICATION_ID, notification)
        } else {
            manager.notify(RECORDING_NOTIFICATION_ID, notification)
        }
        // 顶掉「点击服务」那条静默通知：会话期间状态栏只留这条录制通知
        manager.cancel(NOTIFICATION_ID)
    }

    /** 退出前台状态并撤掉悬浮球那条前台通知 */
    private fun dropForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun cancelRecordingNotification() {
        dropForegroundNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(RECORDING_NOTIFICATION_ID)
    }

    /** 点通知本体 = 开始录制（后台的 App 靠通知这道豁免把服务拉回前台） */
    private fun startRecordingIntent(): PendingIntent {
        val intent = Intent(this, FloatingService::class.java).setAction(ACTION_START_RECORDING)
        return PendingIntent.getForegroundService(
            this, REQUEST_START_RECORDING, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 通知上的「取消」 */
    private fun cancelRecordingIntent(): PendingIntent {
        val intent = Intent(this, FloatingService::class.java).setAction(ACTION_CANCEL_RECORDING)
        return PendingIntent.getService(
            this, REQUEST_CANCEL_RECORDING, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 结束录制会话：关掉悬浮球服务并把 App 叫回前台 */
    private fun returnToApp() {
        stopCapture()
        recordingIdleJob?.cancel()
        recordingIdleJob = null
        recordingPending = false
        recordingActive = false
        AppConfig.recordSessionActive = false
        AppConfig.preventExecution = true
        AppConfig.running = false
        AppConfig.operationActive = false
        // 先拉 App 再退前台：服务一旦退出前台，本应用就成了纯后台应用，
        // Android 10+ 会拦掉后台启动 Activity，界面就回不来
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            android.util.Log.d("FloatingService", "returnToApp: app launched")
        }
        cancelRecordingNotification()
        // 有些系统（如 MIUI/HyperOS，「后台弹出界面」默认关闭）会拦下后台应用启动的界面，
        // startActivity 不报错但界面回不到前台。这里等一小会儿确认一下，没回来就补条通知。
        returnCheckJob?.cancel()
        returnCheckJob = scope.launch {
            delay(RETURN_CHECK_DELAY_MS)
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (!isAppForeground(am)) {
                android.util.Log.w("FloatingService", "returnToApp: still in background, showing fallback")
                // 记一笔：用户下次回到应用时会看到「后台弹出界面」的说明框
                PromptFlags.setReturnBlocked(applicationContext, true)
                showReturnNotification()
                warnReturnBlocked()
            }
            stopSelf()
        }
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
                    android.util.Log.w("FloatingService", "toast: need accessibility")
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
                android.util.Log.d(
                    "FloatingService",
                    "executeSwipe: manual (${config.swipeX1},${config.swipeY1})->(${config.swipeX2},${config.swipeY2}) duration=${config.swipeDuration}"
                )
                service.swipeAndAwait(
                    config.swipeX1, config.swipeY1,
                    config.swipeX2, config.swipeY2,
                    config.swipeDuration
                )
            }
            android.util.Log.d("FloatingService", "executeSwipe: completed=$completed")
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
        if (active === this) active = null
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
        AppConfig.recordSessionActive = false
        stopCapture()
        sessionNotifyJob?.cancel()
        sessionNotifyJob = null
        recordingIdleJob?.cancel()
        recordingIdleJob = null
        cancelRecordingNotification()
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

        /** 录制通知单独一个渠道，保证点「添加手势」后能立刻以横幅出现 */
        private const val RECORDING_CHANNEL_ID = "gesture_recording_channel_v3"

        /** 早期版本用过的渠道 id，创建渠道时顺手删掉 */
        private val LEGACY_RECORDING_CHANNEL_IDS = listOf(
            "gesture_recording_channel", "gesture_recording_channel_v2"
        )
        private const val RECORDING_NOTIFICATION_ID = 2

    /** 界面回不到前台时的兜底通知（录制通知已撤，不与它冲突） */
    private const val RETURN_NOTIFICATION_ID = 3
        private const val REQUEST_START_RECORDING = 2
        private const val REQUEST_CANCEL_RECORDING = 3
        private const val ACTION_PREPARE_RECORDING = "com.yjc.click.action.PREPARE_RECORDING"
        private const val ACTION_START_RECORDING = "com.yjc.click.action.START_RECORDING"
        private const val ACTION_CANCEL_RECORDING = "com.yjc.click.action.CANCEL_RECORDING"

        /** 录制期间系统处于触摸探索态，闲置这么久没动静就自动退出，避免用户被困住 */
        private const val RECORD_IDLE_TIMEOUT_MS = 60_000L

        /** 松手后稍等一下再回放，避免和系统收尾抢时序 */
        private const val RECORD_REPLAY_DELAY_MS = 120L

        /** 等 App 退到后台再发录制通知：最多等这么久，之后即使还在前台也照发 */
        private const val SESSION_NOTIFY_MAX_WAIT_MS = 3_000L
        private const val SESSION_NOTIFY_POLL_MS = 150L

    /** returnToApp 之后等这么久再确认界面是否真的回到前台 */
    private const val RETURN_CHECK_DELAY_MS = 900L

        /** startService 被后台限制时的兜底标记（此时必须先 startForeground 喂饱 5 秒规则） */
        private const val EXTRA_FOREGROUND_FALLBACK = "foreground_fallback"

        /**
         * 主页「添加手势」用：唤醒服务进入待录制态（服务随后发通知并让 App 退到后台）。
         * 这里刻意用 startService 而不是 startForegroundService：待录制通知必须发成
         * 普通通知，系统才不会强加常驻标记、MIUI 这类系统才肯弹横幅。
         */
        fun prepareRecording(context: Context) {
            val intent = Intent(context, FloatingService::class.java)
                .setAction(ACTION_PREPARE_RECORDING)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // 后台启动受限时退一步：至少起前台服务，让用户还能看到通知
                android.util.Log.w("FloatingService", "prepareRecording: startService failed, fallback", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(
                        Intent(context, FloatingService::class.java)
                            .setAction(ACTION_PREPARE_RECORDING)
                            .putExtra(EXTRA_FOREGROUND_FALLBACK, true)
                    )
                }
            }
        }

        @Volatile
        private var active: FloatingService? = null

        /**
         * 用户自己回到 App（没点那条通知就开始录）时调用：把待录制状态作废，
         * 顺手撤掉「点此开始录制手势」那条通知，免得它一直躺在状态栏里。
         * 服务本身的收尾由 MainActivity.onResume 里的 stopService 负责。
         */
        fun cancelPendingRecording(context: Context) {
            if (!AppConfig.recordSessionActive) return
            AppConfig.recordSessionActive = false
            androidx.core.app.NotificationManagerCompat.from(context).cancel(RECORDING_NOTIFICATION_ID)
            android.util.Log.d("FloatingService", "pending recording cancelled (returned to app)")
        }

        /** 录制通知所在的通知通道 id（API 26+ 才有通道概念） */
        fun recordingChannelId(): String = RECORDING_CHANNEL_ID

        /**
         * 录制提示能不能弹横幅（MIUI 叫「悬浮通知」）。
         *
         * API 26+ 就看法定通道的重要性：低于 HIGH 时系统只把通知塞进状态栏，不会浮出来。
         * 用户在通知设置里把「手势录制」调成静默就是这个状态。通道重要性应用自己改不了，
         * 只能提示用户去改，所以这里只负责判断。
         *
         * API 24/25 没有通道，系统也不给应用读这个开关 —— 一律当作可用。
         */
        fun isRecordingHeadsUpEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return true
            val channel = manager.getNotificationChannel(RECORDING_CHANNEL_ID) ?: return true
            return channel.importance >= NotificationManager.IMPORTANCE_HIGH
        }

        /** 无障碍服务被系统关掉时由 ClickAccessibilityService 回调 */
        fun onAccessibilityLost() {
            active?.handleAccessibilityLost()
        }

        /** 无障碍重新开启后允许下一次撤销再提示一次 */
        fun onAccessibilityRestored() {
            active?.accessibilityLost = false
        }
    }
}

