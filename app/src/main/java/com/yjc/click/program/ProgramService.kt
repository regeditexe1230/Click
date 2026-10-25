package com.yjc.click.program

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import com.yjc.click.ClickAccessibilityService
import com.yjc.click.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 跑「程序」的前台服务，同时负责屏幕上的悬浮球。
 *
 * 球的生命周期：
 * - 程序没跑：START 节点位置一个紫色开始球（单击 = 开始跑）。
 * - 程序在跑：每条支线一个球，颜色 = 该支线当前步骤节点的 color，停在动作点旁边的
 *   偏移处（[BALL_OFFSET_DP] dp，优先右上，贴边时自动换到另一侧），不压住动作点；
 *   支线结束球就消失。
 *
 * 手势：双击球 = 暂停/继续（所有支线一起）；长按球 = 停止整个程序；
 * 支线停在 WAIT（等用户）时，单击那个球 = 继续这一条支线。
 *
 * 多指：动作派发走 [ProgramRunner] 的聚合窗口，真正的手势由
 * [com.yjc.click.ClickAccessibilityService.dispatchStrokesAndAwait] 派发。
 */
class ProgramService : Service() {

    private lateinit var windowManager: WindowManager

    /** 程序 + 执行引擎。null = 没在跑。 */
    private var runner: ProgramRunner? = null

    /** 当前跑的程序 id（通知/重新开始时用）。 */
    private var programId: String? = null

    /** 支线 id → 它的球。 */
    private val branchBalls = HashMap<String, BallRef>()

    /** 没跑时显示的紫色开始球。 */
    private var startBall: BallRef? = null

    /** 开始球要显示的哪个程序。 */
    private var pendingStartProgramId: String? = null

    /** 通知里显示的当前步骤，内容没变就不重复刷新通知。 */
    private var lastNotificationText: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 球上的窗口/通知操作统一走主线程。 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var active = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        instance = this
        active = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Service 的上下文默认跟**系统语言**走，这里按 App 的 per-app 语言再包一层，
     * 让 Toast 和前台通知的文字跟 App 内选择的语言一致（与 FloatingService 一致）。
     */
    override fun attachBaseContext(newBase: Context) {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 前台服务必须在 5 秒内 startForeground，先做掉再检查权限
                startForeground(NOTIFICATION_ID, buildNotification(null))
                val id = intent.getStringExtra(EXTRA_PROGRAM_ID)
                if (id.isNullOrEmpty()) {
                    Log.e(TAG, "onStartCommand: no program id")
                    stopInternal()
                    return START_NOT_STICKY
                }
                if (!ensurePermissions()) {
                    stopInternal()
                    return START_NOT_STICKY
                }
                beginRun(id)
                return START_STICKY
            }

            ACTION_STOP -> {
                stopInternal()
                return START_NOT_STICKY
            }

            ACTION_SHOW_START_BALL -> {
                // 只挂一个开始球，不跑程序
                startForeground(NOTIFICATION_ID, buildNotification(null))
                val id = intent.getStringExtra(EXTRA_PROGRAM_ID)
                if (id.isNullOrEmpty()) {
                    Log.e(TAG, "onStartCommand: no program id")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                pendingStartProgramId = id
                showStartBallNow()
                // 球要一直挂着等用户点它，服务别自己收摊
                return START_STICKY
            }

            ACTION_HIDE_START_BALL -> {
                pendingStartProgramId = null
                removeStartBall()
                stopSelfSafely()
                return START_NOT_STICKY
            }

            else -> {
                // 系统把服务重新拉起来但没带指令（START_STICKY），没东西可跑就直接收摊
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        // 被系统杀掉/用户清掉任务时也要把球和协程收干净
        stopInternal()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 跑 / 停
    // ------------------------------------------------------------------

    private fun beginRun(id: String) {
        val program = ProgramStore.load(this, id)
        if (program == null) {
            Log.e(TAG, "program not found: $id")
            toast(R.string.program_toast_not_found)
            stopInternal()
            return
        }
        runner?.stop()
        removeStartBall()
        programId = id

        val r = ProgramRunner(program, listener, ::dispatchActions)
        r.startDispatcher()
        r.start()
        runner = r
        Log.d(TAG, "program started: ${program.name} (${program.nodes.size} nodes)")
    }

    /**
     * 真正把手势交出去。
     *
     * 一批里只有 1 个动作 → 单指（复用已有的 dispatchGesturePathAndAwait 那条路）；
     * 多个动作 → 一次多 stroke 的 GestureDescription，也就是多根手指同时按不同位置。
     */
    private suspend fun dispatchActions(actions: List<ProgramRunner.Action>): Boolean {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "accessibility service gone, stopping program")
            mainScope.launch {
                toast(R.string.toast_need_accessibility)
                stopInternal()
            }
            return false
        }
        val strokes = runner?.toStrokes(actions) ?: return false
        if (strokes.isEmpty()) return false
        return if (strokes.size == 1) {
            val (path, _, duration) = strokes.first()
            service.dispatchGesturePathAndAwait(path, duration)
        } else {
            service.dispatchStrokesAndAwait(strokes)
        }
    }

    private val listener = object : ProgramRunner.BranchListener {
        override fun onBranchStart(branchId: String, node: ProgramNode) {
            mainScope.launch { showBranchBall(branchId, node) }
        }

        override fun onBranchStep(branchId: String, node: ProgramNode) {
            mainScope.launch {
                moveBranchBall(branchId, node)
                notifyProgress(node)
            }
        }

        override fun onBranchWaiting(branchId: String, node: ProgramNode) {
            mainScope.launch {
                moveBranchBall(branchId, node)
                branchBalls[branchId]?.view?.waiting = true
                toast(R.string.program_toast_waiting)
            }
        }

        override fun onBranchEnd(branchId: String) {
            mainScope.launch { removeBranchBall(branchId) }
        }

        /** 没有开始块：一句可见的提示（新程序是空画布，用户可能直接点运行）。 */
        override fun onNoStart() {
            mainScope.launch { toast(R.string.program_toast_no_start) }
        }

        override fun onAllDone() {
            mainScope.launch {
                Log.d(TAG, "all branches done")
                stopInternal()
            }
        }
    }

    /** 收摊：取消所有协程、移除所有球、撤掉通知并停止前台服务。 */
    private fun stopInternal() {
        if (!active) return
        active = false
        runner?.stop()
        runner = null
        programId = null
        pendingStartProgramId = null
        branchBalls.keys.toList().forEach { removeBranchBall(it) }
        removeStartBall()
        lastNotificationText = null
        stopForegroundCompat()
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "stopSelf failed", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    /** 一个球：窗口 View + 它的布局参数。 */
    private class BallRef(val view: ProgramBallView, val params: WindowManager.LayoutParams)

    private fun showBranchBall(branchId: String, node: ProgramNode) {
        if (!active) return
        if (!Settings.canDrawOverlays(this)) return
        removeBranchBall(branchId)
        if (branchBalls.size >= MAX_BALLS) {
            Log.w(TAG, "too many balls, skipping $branchId")
            return
        }
        val dp = resources.displayMetrics.density
        val sizePx = (BALL_DIAMETER_DP * dp).roundToInt()
        val view = ProgramBallView(this).apply {
            ballColor = node.color
            icon = node.type.toIcon()
        }
        val position = ballPosition(node, sizePx, dp)
        val params = windowParams(sizePx, position)
        attachBall(view, params, branchId = branchId, draggable = false)
        branchBalls[branchId] = BallRef(view, params)
    }

    private fun moveBranchBall(branchId: String, node: ProgramNode) {
        val ref = branchBalls[branchId] ?: return
        ref.view.ballColor = node.color
        ref.view.icon = node.type.toIcon()
        if (!ref.view.isAttachedToWindow) return
        val dp = resources.displayMetrics.density
        val position = ballPosition(node, ref.params.width, dp)
        ref.params.x = position.x
        ref.params.y = position.y
        updateLayout(ref)
    }

    private fun removeBranchBall(branchId: String) {
        val ref = branchBalls.remove(branchId) ?: return
        detachBall(ref.view)
    }

    /**
     * 球的位置：放在动作点旁边的偏移处，优先右上，贴边时自动换到左上/右下，不压住动作点。
     */
    private fun ballPosition(node: ProgramNode, sizePx: Int, dp: Float): Point {
        val offset = BALL_OFFSET_DP * dp
        val screen = screenSize()
        val half = sizePx / 2f
        var cx = node.pointX + offset
        var cy = node.pointY - offset
        if (cx + half > screen.x) cx = node.pointX - offset
        if (cy - half < 0f) cy = node.pointY + offset
        return Point(
            (cx - half).roundToInt().coerceIn(0, (screen.x - sizePx).coerceAtLeast(0)),
            (cy - half).roundToInt().coerceIn(0, (screen.y - sizePx).coerceAtLeast(0)),
        )
    }

    /**
     * 开始球的屏幕坐标：约定用 START 节点的 [ProgramNode.pointX] / [ProgramNode.pointY]（屏幕像素），
     * 和动作节点用 pointX/pointY 的语义一致；用户还没设过（都是 0）时默认落在屏幕右侧中间。
     * 注意 [ProgramNode.x] / [ProgramNode.y] 是画布坐标，不能拿来当屏幕坐标用。
     */
    private fun startBallPosition(node: ProgramNode, sizePx: Int, dp: Float, screen: Point): Point {
        if (node.pointX == 0 && node.pointY == 0) {
            val margin = (24 * dp).roundToInt()
            return Point(
                (screen.x - sizePx - margin).coerceAtLeast(0),
                ((screen.y - sizePx) / 2).coerceAtLeast(0),
            )
        }
        return Point(
            (node.pointX - sizePx / 2f).roundToInt().coerceIn(0, (screen.x - sizePx).coerceAtLeast(0)),
            (node.pointY - sizePx / 2f).roundToInt().coerceIn(0, (screen.y - sizePx).coerceAtLeast(0)),
        )
    }

    /** 开始球：落在 START 节点约定的屏幕坐标上（见 [startBallPosition]）。 */
    private fun showStartBallNow() {
        if (!active) return
        val programIdNow = pendingStartProgramId ?: return
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.program_toast_need_overlay)
            return
        }
        val program = ProgramStore.load(this, programIdNow)
        if (program == null) {
            toast(R.string.program_toast_not_found)
            return
        }
        val node = program.start
        if (node == null) {
            Log.e(TAG, "showStartBall: no START node")
            toast(R.string.program_toast_no_start)
            return
        }
        removeStartBall()
        val dp = resources.displayMetrics.density
        val sizePx = (BALL_DIAMETER_DP * dp).roundToInt()
        val view = ProgramBallView(this).apply {
            ballColor = node.color
            icon = node.type.toIcon()
        }
        val screen = screenSize()
        val params = windowParams(sizePx, startBallPosition(node, sizePx, dp, screen))
        // 开始球可以拖：主要是把它挪开别挡住要操作的地方（拖走的位置写回 START 节点的 pointX/pointY）
        attachBall(view, params, branchId = null, draggable = true)
        startBall = BallRef(view, params)
    }

    private fun removeStartBall() {
        val ref = startBall ?: return
        startBall = null
        detachBall(ref.view)
    }

    /** 拖完开始球，把它的屏幕位置写回 START 节点的 pointX/pointY（下次出现就在用户放的地方）。 */
    private fun saveStartBallPosition(view: View) {
        val programIdNow = pendingStartProgramId ?: return
        val program = ProgramStore.load(this, programIdNow) ?: return
        val node = program.start ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = (loc[0] + view.width / 2f).roundToInt()
        val cy = (loc[1] + view.height / 2f).roundToInt()
        if (node.pointX == cx && node.pointY == cy) return
        val updated = program.copy(
            nodes = program.nodes.map {
                if (it.id == node.id) it.copy(pointX = cx, pointY = cy) else it
            },
            updatedAt = System.currentTimeMillis(),
        )
        ProgramStore.save(this, updated)
    }

    private fun windowParams(sizePx: Int, position: Point): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = position.x
        params.y = position.y
        return params
    }

    private fun attachBall(view: ProgramBallView, params: WindowManager.LayoutParams, branchId: String?, draggable: Boolean) {
        view.setOnTouchListener(BallTouchListener(branchId, draggable))
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView(ball) failed", e)
        }
    }

    private fun detachBall(view: ProgramBallView) {
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "removeView(ball) failed", e)
        }
    }

    private fun updateLayout(ref: BallRef) {
        try {
            if (ref.view.isAttachedToWindow) windowManager.updateViewLayout(ref.view, ref.params)
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed", e)
        }
    }

    private fun screenSize(): Point {
        val metrics = resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 球的触摸处理：
     * - 单击：开始球 = 开始跑；支线球 = 如果有支线在等用户（WAIT），点一下继续。
     * - 双击：暂停 / 继续（所有支线一起）。
     * - 长按：停止整个程序。
     * - 拖动：只有开始球能拖，拖到别处就从那儿开始。
     */
    private inner class BallTouchListener(
        private val branchId: String?,
        private val draggable: Boolean,
    ) : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var centerX = 0f
        private var centerY = 0f
        private var dragging = false
        private var longPressFired = false
        private var downTime = 0L
        private var lastTapUpTime = 0L

        private val longPressRunnable = Runnable {
            longPressFired = true
            Log.d(TAG, "long press on ball, stopping program")
            // 停止必须在主线程做（要 removeView）
            mainHandler.post { stopInternal() }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTime = System.currentTimeMillis()
                    dragging = false
                    longPressFired = false
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    centerX = loc[0] + view.width / 2f
                    centerY = loc[1] + view.height / 2f
                    view.alpha = 0.7f
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!draggable || longPressFired) return true
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > (DRAG_SLOP_DP * resources.displayMetrics.density)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return true
                        val screen = screenSize()
                        lp.x = (centerX + dx - view.width / 2f).roundToInt()
                            .coerceIn(0, (screen.x - view.width).coerceAtLeast(0))
                        lp.y = (centerY + dy - view.height / 2f).roundToInt()
                            .coerceIn(0, (screen.y - view.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(view, lp)
                        } catch (e: Exception) {
                            Log.e(TAG, "drag updateViewLayout failed", e)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging && branchId == null) saveStartBallPosition(view)
                    if (!longPressFired && !dragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpTime < DOUBLE_TAP_MS) {
                            lastTapUpTime = 0
                            onDoubleTap()
                        } else {
                            lastTapUpTime = now
                            onSingleTap()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }

                else -> false
            }
            return true
        }

        private fun onSingleTap() {
            if (System.currentTimeMillis() - downTime >= LONG_PRESS_MS) return
            if (branchId == null) {
                // 开始球：点一下就开始跑
                pendingStartProgramId?.let { startProgram(it) }
                return
            }
            // 支线球：有人在等用户点击时才响应
            runner?.resumeWait(branchId)
        }

        private fun onDoubleTap() {
            // 开始球只在「没跑」时存在，它上面没有暂停可切（第一次点击已经把它换成支线球了）
            if (branchId == null) return
            val r = runner ?: return
            val paused = r.togglePause()
            branchBalls.values.forEach { it.view.waiting = false }
            toast(if (paused) R.string.program_toast_paused else R.string.program_toast_resumed)
            updateNotification()
        }
    }

    // ------------------------------------------------------------------
    // 权限 / 启动
    // ------------------------------------------------------------------

    private fun ensurePermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.program_toast_need_overlay)
            return false
        }
        if (ClickAccessibilityService.instance == null) {
            toast(R.string.program_toast_need_accessibility)
            return false
        }
        return true
    }

    private fun startProgram(id: String) {
        if (!ensurePermissions()) return
        val intent = Intent(this, ProgramService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_PROGRAM_ID, id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务会被拦，这里退一步：服务已经在跑就直接换程序
            Log.w(TAG, "startForegroundService failed", e)
            if (active) beginRun(id)
        }
    }

    private fun sendCommand(action: String) {
        val intent = Intent(this, ProgramService::class.java).setAction(action)
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand($action) failed", e)
        }
    }

    private fun toast(resId: Int) {
        try {
            Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "toast failed", e)
        }
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.program_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(step: ProgramNode?): Notification {
        val running = runner != null
        val title = if (running) getString(R.string.program_notification_title) else getString(R.string.program_notification_idle)
        val text = if (running) {
            step?.type?.let { rowLabel(it) } ?: getString(R.string.program_notification_text)
        } else {
            getString(R.string.program_notification_idle_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(running)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notification_action_stop), stopIntent())
            .build()
    }

    /** 通知里的「停止」：走服务指令，不能直接 stopSelf（要连同球和协程一起收）。 */
    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, ProgramService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, REQUEST_STOP, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 点通知回到 App。 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, com.yjc.click.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, REQUEST_OPEN, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 支线走到哪个节点就刷新通知，内容没变时不重复发。 */
    private fun notifyProgress(node: ProgramNode) {
        if (runner == null) return
        val text = rowLabel(node.type)
        if (text == lastNotificationText) return
        lastNotificationText = text
        updateNotification(node)
    }

    private fun updateNotification(step: ProgramNode? = null) {
        if (!active) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(step))
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed", e)
        }
    }

    private fun rowLabel(type: ProgramNodeType): String = when (type) {
        ProgramNodeType.START -> getString(R.string.program_notify_step_start)
        ProgramNodeType.CLICK -> getString(R.string.program_notify_step_click)
        ProgramNodeType.SWIPE -> getString(R.string.program_notify_step_swipe)
        ProgramNodeType.DELAY -> getString(R.string.program_notify_step_delay)
        ProgramNodeType.WAIT -> getString(R.string.program_notify_step_wait)
        ProgramNodeType.LOOP -> getString(R.string.program_notify_step_loop)
    }

    companion object {
        private const val TAG = "ProgramService"
        private const val CHANNEL_ID = "program_service_channel"
        private const val NOTIFICATION_ID = 11
        private const val REQUEST_STOP = 11
        private const val REQUEST_OPEN = 12
        private const val ACTION_START = "com.yjc.click.program.action.START"
        private const val ACTION_STOP = "com.yjc.click.program.action.STOP"

        /** 让一个只画开始球的服务实例活下来用的动作。 */
        private const val ACTION_SHOW_START_BALL = "com.yjc.click.program.action.SHOW_START_BALL"
        private const val ACTION_HIDE_START_BALL = "com.yjc.click.program.action.HIDE_START_BALL"
        private const val EXTRA_PROGRAM_ID = "program_id"

        /** 长按多久算「停止整个程序」。 */
        private const val LONG_PRESS_MS = 600L

        /** 两次点击间隔小于它就当双击（暂停/继续）。 */
        private const val DOUBLE_TAP_MS = 300L

        /** 开始球的拖动阈值（dp）。 */
        private const val DRAG_SLOP_DP = 12f

        /** 一次最多画几个球（支线很多时不至于糊满屏幕）。 */
        private const val MAX_BALLS = 12

        /** 当前活着的服务实例（下拉通知、前台服务被系统拉起时用）。 */
        @Volatile
        private var instance: ProgramService? = null

        // ---------------- 对外 API ----------------

        /** 启动程序：缺无障碍或悬浮窗权限就 Toast 提示并返回。 */
        fun start(context: Context, programId: String) {
            val app = context.applicationContext
            if (!Settings.canDrawOverlays(app)) {
                toast(app, R.string.program_toast_need_overlay)
                return
            }
            if (ClickAccessibilityService.instance == null) {
                toast(app, R.string.program_toast_need_accessibility)
                return
            }
            val intent = Intent(app, ProgramService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROGRAM_ID, programId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        /** 停止：取消所有协程、移除所有球、停止前台服务。 */
        fun stop(context: Context) {
            val app = context.applicationContext
            val running = instance
            if (running != null) {
                running.mainHandler.post { running.stopInternal() }
                return
            }
            // 服务还活着但实例没接上（比如刚被系统重启过），走指令兜底
            try {
                app.startService(Intent(app, ProgramService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
        }

        fun isRunning(context: Context): Boolean = instance?.runner != null

        /**
         * 会话是否活着：真在跑程序，或者只挂着一颗待命球。
         *
         * 工具条上的 ▶ / ■ 要按这个切换 —— 只按 [isRunning] 的话，点了 ▶ 只是挂球、
         * runner 还是空的，按钮就一直停在 ▶，既显示不出「已经启动」，也没地方点停止。
         */
        fun isActive(context: Context): Boolean =
            instance?.let { it.runner != null || it.pendingStartProgramId != null } ?: false

        /**
         * 应用回到前台时用：不管是真在跑程序，还是只挂着一颗待命球，都直接收掉。
         *
         * 为什么不用 [isRunning] 判断：只挂待命球的时候 runner 是空的，isRunning 返回 false，
         * 于是球会一直挂在别人界面上面 —— 用户切回本应用时看到球还在，就是这个问题。
         */
        fun dismiss(context: Context) {
            val svc = instance ?: return
            svc.mainHandler.post { svc.stopInternal() }
        }

        /** 只显示 START 节点位置的紫色开始球（不跑），点它 = 开始跑。 */
        fun showStartBall(context: Context, programId: String): Boolean {
            val app = context.applicationContext
            if (!Settings.canDrawOverlays(app)) {
                toast(app, R.string.program_toast_need_overlay)
                return false
            }
            val program = ProgramStore.load(app, programId)
            if (program == null) {
                toast(app, R.string.program_toast_not_found)
                return false
            }
            // 没有开始块就别挂球：直接给一句看得见的提示（新程序是空画布，见 docs/node-tray-spec.md §7）
            if (program.start == null) {
                toast(app, R.string.program_toast_no_start)
                return false
            }
            val running = instance
            val intent = Intent(app, ProgramService::class.java)
                .setAction(ACTION_SHOW_START_BALL)
                .putExtra(EXTRA_PROGRAM_ID, programId)
            if (running != null) {
                running.mainHandler.post {
                    running.pendingStartProgramId = programId
                    running.showStartBallNow()
                }
                return true
            }
            try {
                // 挂球也要扛得住 App 退到后台，所以同样按前台服务起
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "showStartBall failed", e)
                return false
            }
        }

        fun hideStartBall(context: Context) {
            val app = context.applicationContext
            val running = instance
            if (running != null) {
                running.mainHandler.post {
                    running.pendingStartProgramId = null
                    running.removeStartBall()
                    // 这个实例只是为了挂球活着（没跑程序），球收掉就该收摊
                    if (running.runner == null) running.stopInternal()
                }
                return
            }
            try {
                app.startService(Intent(app, ProgramService::class.java).setAction(ACTION_HIDE_START_BALL))
            } catch (e: Exception) {
                Log.e(TAG, "hideStartBall failed", e)
            }
        }

        private fun toast(context: Context, resId: Int) {
            try {
                Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "toast failed", e)
            }
        }
    }
}
