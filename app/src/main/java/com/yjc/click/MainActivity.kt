package com.yjc.click

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import com.yjc.click.ui.home.HomeScreen
import com.yjc.click.ui.shell.MainScaffold
import com.yjc.click.ui.shell.MainTab
import com.yjc.click.ui.theme.AppTheme
import com.yjc.click.ui.theme.ClickTheme
import com.yjc.click.ui.theme.ThemeReveal

class MainActivity : AppCompatActivity() {

    companion object {
        var floatTutorialPending = false
    }

    enum class PermissionStep { NONE, ACCESSIBILITY, OVERLAY }
    private var pendingPermissionStep = PermissionStep.NONE

    // ---- Compose 侧状态（替代原来 findViewById 后直接改 View）----
    private var isSwipeMode by mutableStateOf(false)
    private var isGestureMode by mutableStateOf(false)
    private var fontFamily by mutableStateOf<FontFamily?>(null)
    private var statusTextValue by mutableStateOf("")
    private var swipeX1 by mutableStateOf("")
    private var swipeY1 by mutableStateOf("")
    private var swipeX2 by mutableStateOf("")
    private var swipeY2 by mutableStateOf("")
    private var swipeDuration by mutableStateOf("")
    private var delayText by mutableStateOf("0")
    private var repeatText by mutableStateOf("1")
    private var infinite by mutableStateOf(false)
    private var recordedStatus by mutableStateOf("")
    private var recordedVisible by mutableStateOf(false)
    private var canStart by mutableStateOf(false)
    private var showStartOverlay by mutableStateOf(false)
    private var canStop by mutableStateOf(false)
    private var canRecord by mutableStateOf(false)
    private var isWarningDialogShowing = false
    private var notificationPermissionAsked = false

    /** Android 13+ 通知权限申请（拒绝不影响服务运行，只是前台通知不可见） */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** 当前底部导航页（旧实现用 BottomNavigationView 选中项 + 三个 View 的显隐） */
    private var selectedTab by mutableStateOf(MainTab.HOME)

    /** 首次启动时底栏从底部滑入（旧实现在 onCreate 里对 BottomNavigationView 做 translationY 动画） */
    private var playBottomBarEntrance by mutableStateOf(false)

    /** 设置页 Fragment 是否已按需加载（旧实现首次切到设置页才加载） */
    private var settingsFragmentLoaded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 应用语言设置
        applyLanguage()

        // 初始化字体管理
        FontManager.init(this)

        // 启用edge-to-edge模式
        enableEdgeToEdge()

        // 恢复选中的底部导航项
        val savedTabIndex = savedInstanceState?.getInt("selected_nav_item", 0) ?: 0
        selectedTab = MainTab.entries.getOrElse(savedTabIndex) { MainTab.HOME }
        playBottomBarEntrance = savedInstanceState == null
        run {
            val pref = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("app_theme", "follow_system") ?: "follow_system"
            val sysDark = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            AppTheme.isDark = AppTheme.resolveDark(pref, sysDark)
        }

        // 外壳（顶栏 + 页面 + 底部导航）全部为 Compose；
        // 设置页仍是 Fragment（内部弹窗依然是 View），由 AndroidView 承载。
        // inset 处理：顶栏补状态栏、底栏补导航栏（旧实现是给 toolbar / bottomNav 分别设 padding），
        // 键盘 inset 由 Column 的 imePadding 承担（旧实现是给 root 设 padding）。
        setContent {
            // 订阅字体变化：设置页里换字体时，已经组合好的顶栏/底栏/首页也要跟着换
            // 主题状态一变就重新下发系统栏样式（含全面屏手势条那条的背景/图标明暗）。
            // 放在这里而不是切换处：AppTheme.apply 是异步的（先 PixelCopy 抓图再切），
            // 在调用点读 AppTheme.isDark 会拿到旧值。
            LaunchedEffect(AppTheme.isDark) { applySystemBarStyle(AppTheme.isDark) }
            val fontRevision = FontManager.fontRevision
            val family = androidx.compose.runtime.remember(fontRevision) {
                FontManager.currentTypeface?.let { FontFamily(it) }
            }
            ClickTheme(fontFamily = family, integerFontAdvance = true, darkTheme = AppTheme.isDark) {
                // 配色切换时的"圆形揭示"过渡（圆心 = 点击位置）
                ThemeReveal {
                MainScaffold(
                    selectedTab = selectedTab,
                    onSelectTab = { tab -> selectedTab = tab },
                    title = when (selectedTab) {
                        MainTab.HOME -> "Click"
                        MainTab.PROGRAM -> getString(R.string.nav_program)
                        MainTab.SETTINGS -> getString(R.string.nav_settings)
                    },
                    playEntrance = playBottomBarEntrance,
                    settingsContent = { },
                    programContent = { },
                    homeContent = {
                    HomeScreen(
                    statusText = statusTextValue,
                    isSwipeMode = isSwipeMode,
                    isGestureMode = isGestureMode,
                    swipeX1 = swipeX1,
                    swipeY1 = swipeY1,
                    swipeX2 = swipeX2,
                    swipeY2 = swipeY2,
                    swipeDuration = swipeDuration,
                    delay = delayText,
                    repeat = repeatText,
                    infinite = infinite,
                    recordedStatus = recordedStatus,
                    recordedVisible = recordedVisible,
                    canStart = canStart,
                    showStartOverlay = showStartOverlay,
                    canStop = canStop,
                    canRecord = canRecord,
                    onEnableService = {
                        showWarningDialog {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    onEnableOverlay = { requestOverlayPermission() },
                    onModeSelected = { swipe ->
                        if (swipe != isSwipeMode) {
                            isSwipeMode = swipe
                            saveConfig()
                            if (swipe && !isSwipeConfigValid()) {
                                val hint = if (!isGestureMode) {
                                    getString(R.string.toast_fill_manual_params)
                                } else {
                                    getString(R.string.toast_record_gesture_first)
                                }
                                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                            }
                            updateStatus()
                        }
                    },
                    onSwipeMethodSelected = { gesture ->
                        if (gesture != isGestureMode) {
                            isGestureMode = gesture
                            saveConfig()
                            updateStatus()
                        }
                    },
                    onSwipeX1Change = { swipeX1 = it; afterInputChange() },
                    onSwipeY1Change = { swipeY1 = it; afterInputChange() },
                    onSwipeX2Change = { swipeX2 = it; afterInputChange() },
                    onSwipeY2Change = { swipeY2 = it; afterInputChange() },
                    onSwipeDurationChange = { swipeDuration = it; afterInputChange() },
                    onDelayChange = { delayText = it; afterInputChange() },
                    onRepeatChange = { repeatText = it; afterInputChange() },
                    onInfiniteChange = { infinite = it; saveConfig() },
                    onStart = { handleStartButtonClick() },
                    onStop = {
                        AppConfig.running = false
                        stopService(Intent(this, FloatingService::class.java))
                        updateStatus()
                    },
                    onRecord = { startGestureRecording() },
                    )
                    },
                )
                }
            }
        }

        savedInstanceState?.let {
            pendingPermissionStep = try {
                PermissionStep.valueOf(it.getString("pending_step", "NONE")!!)
            } catch (e: Exception) {
                PermissionStep.NONE
            }
        }

        // 恢复上次保存的配置（仅在全新启动时；旋转/重建交给系统与 savedInstanceState）
        if (savedInstanceState == null) {
            val snapshot = ConfigStore.load(this)
            isSwipeMode = snapshot.isSwipeMode
            isGestureMode = snapshot.isGestureMode
            swipeX1 = snapshot.swipeX1
            swipeY1 = snapshot.swipeY1
            swipeX2 = snapshot.swipeX2
            swipeY2 = snapshot.swipeY2
            swipeDuration = snapshot.swipeDuration
            delayText = snapshot.delay
            repeatText = snapshot.repeat
            infinite = snapshot.infinite
            // 同步一次内存中的运行时配置，避免启动后未改动控件时界面与参数不一致
            saveConfig()
        }

        // 恢复执行模式状态：仅在旋转/重建时由 savedInstanceState 覆盖，全新启动沿用已持久化的模式
        savedInstanceState?.let {
            isSwipeMode = it.getBoolean("isSwipeMode", isSwipeMode)
            isGestureMode = it.getBoolean("isGestureMode", isGestureMode)
        }


        // 主页图标初始为填充状态（默认就是ic_home）

        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_launch_done", false).not()) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
            // 等待布局完全稳定后再显示弹窗
            val decorView = window.decorView
            decorView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    decorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    decorView.postDelayed({
                        showFirstLaunchDialog()
                    }, 100)
                }
            })
        } else {
            // 非首次启动：单独申请通知权限，避免与首次使用说明弹窗叠加
            window.decorView.post { ensureNotificationPermission() }
        }

        // 应用自定义字体
        FontManager.applyFont(window.decorView)
    }

    /**
     * configChanges="uiMode" 让 Activity 不重建，但"跟随系统"时需要跟着系统深浅更新渲染状态。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val pref = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("app_theme", "follow_system") ?: "follow_system"
        if (pref == "follow_system") {
            val sysDark = (android.content.res.Resources.getSystem().configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            // 走和手动切换同一条路：抓旧画面 + 圆形揭示 + 重新下发系统栏样式
            AppTheme.apply(this, "follow_system", sysDark, null)
            applySystemBarStyle(AppTheme.isDark)
        }
    }

    /**
     * 按当前深浅重新下发系统栏样式。
     * enableEdgeToEdge() 只在 onCreate 调用一次，里面的"图标明暗"是按启动时的主题算的，
     * 主题切换后必须再调一次，否则全面屏手势小白条那一条的背景/图标不会跟着变。
     */
    /** 供设置页在切换主题后调用 */
    fun refreshSystemBarStyle() = applySystemBarStyle(AppTheme.isDark)

    private fun applySystemBarStyle(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { dark },
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { dark },
        )
    }
    override fun onResume() {
        super.onResume()
        // 应用字体：Compose 首页通过 ClickTheme 下发 FontFamily，
        // View 部分（顶栏、底部导航、各类对话框）仍走原来的 View 树遍历逻辑
        FontManager.init(this)
        FontManager.applyFont(window.decorView)
        fontFamily = FontManager.currentTypeface?.let { FontFamily(it) }
        AppConfig.preventExecution = true
        AppConfig.running = false
        stopService(Intent(this, FloatingService::class.java))

        if (intent.getBooleanExtra("show_float_tutorial", false)) {
            intent.removeExtra("show_float_tutorial")
            showFloatTutorialDialog {
                AppConfig.running = true
                AppConfig.preventExecution = false
                startFloatingService()
            }
            updateStatus()
            return
        }

        if (pendingPermissionStep == PermissionStep.ACCESSIBILITY) {
            if (isServiceEnabled()) {
                pendingPermissionStep = PermissionStep.NONE
                updateStatus()
                if (!hasOverlayPermission()) {
                    Toast.makeText(applicationContext, R.string.toast_enable_overlay, Toast.LENGTH_SHORT).show()
                    openOverlaySettings()
                    return
                }
            }
        } else if (pendingPermissionStep == PermissionStep.OVERLAY) {
            if (hasOverlayPermission()) {
                pendingPermissionStep = PermissionStep.NONE
            }
        }
        updateStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_step", pendingPermissionStep.name)
        outState.putBoolean("isSwipeMode", isSwipeMode)
        outState.putBoolean("isGestureMode", isGestureMode)
        // 保存当前选中的底部导航项
        outState.putInt("selected_nav_item", selectedTab.ordinal)
    }

    override fun onPause() {
        super.onPause()
        AppConfig.preventExecution = false
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val overlayGranted = hasOverlayPermission()

        val accessibilityStatus = getString(if (serviceEnabled) R.string.status_enabled else R.string.status_disabled)
        val overlayStatus = getString(if (overlayGranted) R.string.status_enabled else R.string.status_disabled)

        statusTextValue = buildString {
            append(getString(R.string.status_accessibility, accessibilityStatus))
            append("\n")
            append(getString(R.string.status_overlay, overlayStatus))
        }

        val ready = serviceEnabled && overlayGranted && isSwipeConfigValid()
        val startable = ready && !AppConfig.running
        canStart = startable
        showStartOverlay = !startable
        canStop = AppConfig.running
        canRecord = !AppConfig.running

        val gesture = AppConfig.recordedGesture
        if (gesture.points.size >= 2) {
            recordedStatus = getString(R.string.status_gesture_recorded, gesture.points.size, gesture.totalDuration)
            recordedVisible = true
        } else if (isSwipeMode && isGestureMode) {
            recordedStatus = getString(R.string.status_gesture_not_recorded)
            recordedVisible = true
        } else {
            recordedVisible = false
        }
    }

    private fun isSwipeConfigValid(): Boolean {
        if (!isSwipeMode) return true
        return if (!isGestureMode) {
            swipeX1.isNotEmpty() &&
            swipeY1.isNotEmpty() &&
            swipeX2.isNotEmpty() &&
            swipeY2.isNotEmpty() &&
            // 时长必须为正数：填 0 会让 StrokeDescription 抛 IllegalArgumentException，
            // 滑动会在协程里静默失败（用户端表现为"点了没反应"）
            (swipeDuration.toLongOrNull() ?: 0L) > 0L
        } else {
            AppConfig.recordedGesture.points.size >= 2
        }
    }

    /** Compose 输入框内容变化后的统一处理（等同于旧版 onTextChanged 的 saveConfig + updateStatus） */
    private fun afterInputChange() {
        saveConfig()
        updateStatus()
    }

    /** 请求悬浮窗权限（旧版 btnEnableOverlay 的点击逻辑） */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_enable_overlay, Toast.LENGTH_SHORT).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                Toast.makeText(this, R.string.toast_overlay_enabled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 开始手势录制（旧版 btnRecordGesture 的点击逻辑） */
    private fun startGestureRecording() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, R.string.toast_need_overlay_for_record, Toast.LENGTH_LONG).show()
            return
        }
        if (!isServiceEnabled()) {
            Toast.makeText(this, R.string.toast_need_accessibility_for_record, Toast.LENGTH_LONG).show()
            return
        }
        saveConfig()
        val floatPrefs = getPreferences(Context.MODE_PRIVATE)
        if (floatPrefs.getBoolean("float_tutorial_done", false).not()) {
            floatPrefs.edit().putBoolean("float_tutorial_done", true).apply()
            floatTutorialPending = true
        }
        AppConfig.recordRequested = true
        AppConfig.preventExecution = false
        if (!AppConfig.running) {
            AppConfig.running = true
            startFloatingService()
        }
        Toast.makeText(this, R.string.toast_swipe_to_record, Toast.LENGTH_SHORT).show()
    }

    private fun saveConfig() {
        val mode = if (isSwipeMode) Mode.SWIPE else Mode.CLICK
        val swipeMethod = if (!isGestureMode) SwipeMethod.MANUAL else SwipeMethod.GESTURE
        AppConfig.current = AppConfig(
            mode = mode,
            swipeMethod = swipeMethod,
            swipeX1 = swipeX1.toFloatOrNull() ?: 0f,
            swipeY1 = swipeY1.toFloatOrNull() ?: 0f,
            swipeX2 = swipeX2.toFloatOrNull() ?: 0f,
            swipeY2 = swipeY2.toFloatOrNull() ?: 0f,
            swipeDuration = swipeDuration.toLongOrNull() ?: 0L,
            delayMs = delayText.toLongOrNull() ?: 0L,
            repeatCount = if (infinite) -1 else (repeatText.toIntOrNull() ?: 1)
        )
        ConfigStore.save(
            this,
            ConfigStore.Snapshot(
                isSwipeMode = isSwipeMode,
                isGestureMode = isGestureMode,
                swipeX1 = swipeX1,
                swipeY1 = swipeY1,
                swipeX2 = swipeX2,
                swipeY2 = swipeY2,
                swipeDuration = swipeDuration,
                delay = delayText,
                repeat = repeatText,
                infinite = infinite
            )
        )
    }

    private fun handleStartButtonClick() {
        if (AppConfig.running) return

        val serviceEnabled = isServiceEnabled()
        val overlayGranted = hasOverlayPermission()

        if (serviceEnabled && overlayGranted) {
            if (!isSwipeConfigValid()) {
                val hint = if (!isGestureMode) getString(R.string.toast_fill_manual_params) else getString(R.string.toast_record_gesture_first)
                Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                return
            }
            val floatPrefs = getPreferences(Context.MODE_PRIVATE)
            if (floatPrefs.getBoolean("float_tutorial_done", false).not()) {
                floatPrefs.edit().putBoolean("float_tutorial_done", true).apply()
                saveConfig()
                showFloatTutorialDialog {
                    AppConfig.running = true
                    AppConfig.preventExecution = false
                    startFloatingService()
                }
                return
            }
            saveConfig()
            AppConfig.running = true
            AppConfig.preventExecution = false
            startFloatingService()
            return
        }

        when (pendingPermissionStep) {
            PermissionStep.ACCESSIBILITY -> {
                if (!serviceEnabled) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return
                }
                pendingPermissionStep = PermissionStep.NONE
            }
            PermissionStep.OVERLAY -> {
                if (!overlayGranted) {
                    openOverlaySettings()
                    return
                }
                pendingPermissionStep = PermissionStep.NONE
            }
            PermissionStep.NONE -> {}
        }

        if (!serviceEnabled) {
            showWarningDialog {
                pendingPermissionStep = PermissionStep.ACCESSIBILITY
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        if (!overlayGranted) {
            Toast.makeText(this, R.string.toast_enable_overlay, Toast.LENGTH_SHORT).show()
            openOverlaySettings()
        }
    }

    private fun decodeWarning(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            177.toByte(), 229.toByte(), 244.toByte(), 158.toByte(), 203.toByte(), 205.toByte(), 219.toByte(), 158.toByte(), 151.toByte(), 214.toByte(), 158.toByte(), 148.toByte(),
            198.toByte(), 228.toByte(), 229.toByte(), 167.toByte(), 194.toByte(), 220.toByte(), 133.toByte(), 171.toByte(), 159.toByte(), 215.toByte(), 139.toByte(), 189.toByte(),
            87.toByte(), 71.toByte(), 45.toByte(), 34.toByte(), 54.toByte(), 27.toByte(), 142.toByte(), 219.toByte(), 184.toByte(), 216.toByte(), 142.toByte(), 141.toByte(),
            219.toByte(), 190.toByte(), 153.toByte(), 188.toByte(), 244.toByte(), 212.toByte(), 150.toByte(), 214.toByte(), 239.toByte(), 215.toByte(), 139.toByte(), 189.toByte(),
            213.toByte(), 182.toByte(), 134.toByte(), 202.toByte(), 226.toByte(), 220.toByte(), 166.toByte(), 229.toByte(), 218.toByte(), 133.toByte(), 165.toByte(), 137.toByte(),
            215.toByte(), 136.toByte(), 184.toByte(), 216.toByte(), 147.toByte(), 228.toByte(), 172.toByte(), 238.toByte(), 221.toByte(), 130.toByte(), 222.toByte(), 156.toByte(),
            212.toByte(), 136.toByte(), 134.toByte(), 214.toByte(), 191.toByte(), 129.toByte(), 177.toByte(), 201.toByte(), 254.toByte(), 157.toByte(), 214.toByte(), 249.toByte(),
            214.toByte(), 160.toByte(), 152.toByte(), 214.toByte(), 175.toByte(), 185.toByte(), 200.toByte(), 216.toByte(), 252.toByte(), 165.toByte(), 253.toByte(), 229.toByte(),
            135.toByte(), 136.toByte(), 147.toByte(), 212.toByte(), 144.toByte(), 178.toByte(), 223.toByte(), 146.toByte(), 213.toByte(), 162.toByte(), 236.toByte(), 206.toByte(),
            130.toByte(), 211.toByte(), 155.toByte(), 214.toByte(), 182.toByte(), 190.toByte(), 213.toByte(), 190.toByte(), 166.toByte(), 189.toByte(), 240.toByte(), 203.toByte(),
            159.toByte(), 253.toByte(), 195.toByte(), 218.toByte(), 170.toByte(), 175.toByte(), 215.toByte(), 145.toByte(), 189.toByte(), 200.toByte(), 196.toByte(), 201.toByte(),
            170.toByte(), 224.toByte(), 250.toByte(),
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }

    private fun showWarningDialog(onConfirmed: () -> Unit) {
        if (isWarningDialogShowing) return
        
        isWarningDialogShowing = true
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.security_warning)
            .setMessage(R.string.security_warning_message)
            .setPositiveButton(R.string.confirm) { _, _ -> onConfirmed() }
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.setOnDismissListener { isWarningDialogShowing = false }
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun openOverlaySettings() {
        pendingPermissionStep = PermissionStep.OVERLAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    /**
     * Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 运行时权限，否则通知不会显示。
     * 每个进程只申请一次，避免反复打扰。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionAsked) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionAsked = true
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains("$packageName/$packageName.ClickAccessibilityService") == true
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.toast_floating_started, Toast.LENGTH_SHORT).show()
        updateStatus()
    }



    private fun showFirstLaunchDialog() {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_instructions)
            .setMessage(R.string.usage_instructions_message)
            .setPositiveButton(R.string.ok) { d, _ ->
                d.dismiss()
                ensureNotificationPermission()
            }
            .setCancelable(false)
            .create()
        // 平滑显示，避免闪烁
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun showFloatTutorialDialog(onStart: () -> Unit) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.floating_ball_instructions)
            .setMessage(R.string.floating_ball_instructions_message)
            .setPositiveButton(R.string.ok) { _, _ -> onStart() }
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun decodeFloatTutorialText(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            104.toByte(), 100.toByte(), 166.toByte(), 241.toByte(), 237.toByte(), 134.toByte(), 187.toByte(), 128.toByte(), 213.toByte(), 179.toByte(), 128.toByte(), 214.toByte(), 167.toByte(), 254.toByte(), 162.toByte(), 226.toByte(), 245.toByte(), 140.toByte(), 240.toByte(), 190.toByte(), 212.toByte(), 142.toByte(), 172.toByte(), 212.toByte(), 170.toByte(), 170.toByte(), 188.toByte(), 240.toByte(), 215.toByte(), 158.toByte(), 254.toByte(), 203.toByte(), 57.toByte(), 58.toByte(), 1.toByte(), 30.toByte(), 213.toByte(), 188.toByte(), 167.toByte(), 189.toByte(), 247.toByte(), 204.toByte(), 159.toByte(), 232.toByte(), 207.toByte(), 213.toByte(), 133.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 200.toByte(), 210.toByte(), 220.toByte(), 166.toByte(), 243.toByte(), 194.toByte(), 134.toByte(), 190.toByte(), 131.toByte(), 214.toByte(), 191.toByte(), 156.toByte(), 214.toByte(), 165.toByte(), 207.toByte(), 175.toByte(), 201.toByte(), 209.toByte(), 140.toByte(), 225.toByte(), 159.toByte(), 214.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 173.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 156.toByte(), 229.toByte(), 204.toByte(), 213.toByte(), 187.toByte(), 165.toByte(), 213.toByte(), 185.toByte(), 152.toByte(), 203.toByte(), 209.toByte(), 250.toByte(), 165.toByte(), 250.toByte(), 217.toByte(), 133.toByte(), 186.toByte(), 151.toByte(), 219.toByte(), 145.toByte(), 191.toByte(), 214.toByte(), 189.toByte(), 212.toByte(), 174.toByte(), 254.toByte(), 229.toByte(), 141.toByte(), 249.toByte(), 183.toByte(), 212.toByte(), 142.toByte(), 189.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 159.toByte(), 225.toByte(), 245.toByte(), 214.toByte(), 186.toByte(), 155.toByte(), 214.toByte(), 164.toByte(), 134.toByte(), 200.toByte(), 219.toByte(), 230.toByte(), 165.toByte(), 204.toByte(), 196.toByte(), 132.toByte(), 163.toByte(), 179.toByte(), 215.toByte(), 140.toByte(), 169.toByte(), 213.toByte(), 163.toByte(), 211.toByte(), 163.toByte(), 195.toByte(), 246.toByte(), 140.toByte(), 251.toByte(), 189.toByte(), 223.toByte(), 143.toByte(), 188.toByte(), 215.toByte(), 139.toByte(), 139.toByte(), 189.toByte(), 244.toByte(), 252.toByte(), 156.toByte(), 196.toByte(), 249.toByte(), 215.toByte(), 141.toByte(), 190.toByte(), 58.toByte(), 57.toByte(), 3.toByte(), 0.toByte(), 191.toByte(), 193.toByte(), 213.toByte(), 156.toByte(), 224.toByte(), 203.toByte(), 214.toByte(), 158.toByte(), 191.toByte(), 214.toByte(), 156.toByte(), 165.toByte(), 203.toByte(), 201.toByte(), 196.toByte(), 164.toByte(), 251.toByte(), 211.toByte(), 134.toByte(), 180.toByte(), 139.toByte(), 215.toByte(), 136.toByte(), 179.toByte(), 212.toByte(), 150.toByte(), 210.toByte(), 172.toByte(), 193.toByte(), 213.toByte(), 140.toByte(), 214.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 214.toByte(), 189.toByte(), 157.toByte(), 188.toByte(), 197.toByte(), 236.toByte(), 156.toByte(), 214.toByte(), 227.toByte(), 214.toByte(), 151.toByte(), 184.toByte(), 214.toByte(), 160.toByte(), 189.toByte(), 202.toByte(), 228.toByte(), 214.toByte(), 73.toByte(), 115.toByte(), 94.toByte(), 77.toByte(), 214.toByte(), 191.toByte(), 191.toByte(), 213.toByte(), 180.toByte(), 139.toByte(), 200.toByte(), 219.toByte(), 230.toByte(), 165.toByte(), 204.toByte(), 196.toByte(), 132.toByte(), 163.toByte(), 179.toByte(), 214.toByte(), 191.toByte(), 156.toByte(), 214.toByte(), 180.toByte(), 219.toByte(), 175.toByte(), 194.toByte(), 229.toByte(), 140.toByte(), 240.toByte(), 190.toByte(), 212.toByte(), 142.toByte(), 172.toByte(), 57.toByte(), 58.toByte(), 200.toByte(), 234.toByte(), 226.toByte(), 165.toByte(), 253.toByte(), 229.toByte(), 140.toByte(), 143.toByte(), 170.toByte(), 215.toByte(), 139.toByte(), 189.toByte(), 212.toByte(), 150.toByte(), 217.toByte(), 174.toByte(), 251.toByte(), 211.toByte(), 143.toByte(), 217.toByte(), 167.toByte(), 215.toByte(), 167.toByte(), 152.toByte(), 214.toByte(), 184.toByte(), 169.toByte(), 188.toByte(), 209.toByte(), 221.toByte(), 159.toByte(), 246.toByte(), 207.toByte(), 219.toByte(), 141.toByte(), 156.toByte(), 212.toByte(), 136.toByte(), 134.toByte(), 200.toByte(), 206.toByte(), 252.toByte(), 165.toByte(), 251.toByte(), 198.toByte(), 133.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 176.toByte(), 212.toByte(), 146.toByte(), 195.toByte(), 162.toByte(), 196.toByte(), 211.toByte(), 143.toByte(), 233.toByte(), 155.toByte(), 213.toByte(), 178.toByte(), 172.toByte(), 213.toByte(), 157.toByte(), 140.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 159.toByte(), 235.toByte(), 193.toByte(), 214.toByte(), 148.toByte(), 190.toByte(), 213.toByte(), 188.toByte(), 159.toByte(), 202.toByte(), 226.toByte(), 239.toByte(), 166.toByte(), 255.toByte(), 231.toByte(), 132.toByte(), 177.toByte(), 137.toByte(), 214.toByte(), 183.toByte(), 136.toByte(), 213.toByte(), 190.toByte(), 246.toByte(), 175.toByte(), 201.toByte(), 209.toByte(), 140.toByte(), 225.toByte(), 159.toByte(), 214.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 173.toByte(), 191.toByte(), 198.toByte(), 202.toByte(), 144.toByte(), 248.toByte(), 205.toByte()
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }

    private fun decodeTutorialText(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            176.toByte(), 236.toByte(), 213.toByte(), 159.toByte(), 198.toByte(), 194.toByte(), 215.toByte(), 141.toByte(), 140.toByte(), 215.toByte(), 167.toByte(), 152.toByte(), 193.toByte(), 229.toByte(), 208.toByte(), 73.toByte(), 115.toByte(), 91.toByte(), 77.toByte(), 19.toByte(), 213.toByte(), 143.toByte(), 176.toByte(), 214.toByte(), 160.toByte(), 129.toByte(), 191.toByte(), 221.toByte(), 227.toByte(), 144.toByte(), 240.toByte(), 255.toByte(), 212.toByte(), 146.toByte(), 190.toByte(), 214.toByte(), 175.toByte(), 189.toByte(), 203.toByte(), 211.toByte(), 235.toByte(), 166.toByte(), 235.toByte(), 230.toByte(), 133.toByte(), 177.toByte(), 156.toByte(), 213.toByte(), 133.toByte(), 157.toByte(), 215.toByte(), 132.toByte(), 206.toByte(), 172.toByte(), 222.toByte(), 250.toByte(), 131.toByte(), 250.toByte(), 163.toByte(), 223.toByte(), 143.toByte(), 188.toByte(), 212.toByte(), 178.toByte(), 151.toByte(), 188.toByte(), 205.toByte(), 248.toByte(), 145.toByte(), 215.toByte(), 204.toByte(), 215.toByte(), 139.toByte(), 133.toByte(), 212.toByte(), 139.toByte(), 186.toByte(), 200.toByte(), 207.toByte(), 243.toByte(), 167.toByte(), 193.toByte(), 206.toByte(), 135.toByte(), 139.toByte(), 154.toByte(), 213.toByte(), 188.toByte(), 186.toByte(), 217.toByte(), 188.toByte(), 247.toByte(), 175.toByte(), 206.toByte(), 202.toByte(), 143.toByte(), 236.toByte(), 156.toByte(), 216.toByte(), 132.toByte(), 131.toByte(), 219.toByte(), 141.toByte(), 130.toByte(), 177.toByte(), 228.toByte(), 253.toByte(), 158.toByte(), 215.toByte(), 205.toByte(), 218.toByte(), 145.toByte(), 134.toByte(), 223.toByte(), 143.toByte(), 184.toByte(), 200.toByte(), 206.toByte(), 234.toByte(), 170.toByte(), 227.toByte(), 246.toByte(), 132.toByte(), 145.toByte(), 189.toByte(), 213.toByte(), 173.toByte(), 176.toByte(), 217.toByte(), 183.toByte(), 201.toByte(), 173.toByte(), 215.toByte(), 200.toByte(), 142.toByte(), 217.toByte(), 189.toByte(), 213.toByte(), 157.toByte(), 185.toByte(), 214.toByte(), 189.toByte(), 189.toByte(), 188.toByte(), 228.toByte(), 202.toByte(), 156.toByte(), 239.toByte(), 203.toByte(), 213.toByte(), 172.toByte(), 137.toByte(), 213.toByte(), 187.toByte(), 134.toByte(), 200.toByte(), 246.toByte(), 197.toByte(), 165.toByte(), 213.toByte(), 203.toByte(), 138.toByte(), 180.toByte(), 189.toByte(), 214.toByte(), 160.toByte(), 156.toByte(), 216.toByte(), 147.toByte(), 246.toByte(), 174.toByte(), 248.toByte(), 207.toByte(), 131.toByte(), 255.toByte(), 179.toByte(), 217.toByte(), 180.toByte(), 189.toByte(), 213.toByte(), 166.toByte(), 158.toByte(), 191.toByte(), 196.toByte(), 203.toByte(), 159.toByte(), 247.toByte(), 224.toByte(), 220.toByte(), 140.toByte(), 186.toByte(), 58.toByte(), 57.toByte(), 2.toByte(), 0.toByte(), 121.toByte(), 163.toByte(), 195.toByte(), 240.toByte(), 140.toByte(), 232.toByte(), 154.toByte(), 214.toByte(), 136.toByte(), 161.toByte(), 214.toByte(), 186.toByte(), 134.toByte(), 191.toByte(), 226.toByte(), 226.toByte(), 156.toByte(), 214.toByte(), 236.toByte(), 220.toByte(), 140.toByte(), 169.toByte(), 215.toByte(), 177.toByte(), 137.toByte(), 203.toByte(), 222.toByte(), 241.toByte(), 165.toByte(), 241.toByte(), 252.toByte(), 133.toByte(), 136.toByte(), 161.toByte(), 214.toByte(), 186.toByte(), 155.toByte(), 58.toByte(), 36.toByte(), 106.toByte(), 100.toByte(), 99.toByte(), 158.toByte(), 232.toByte(), 218.toByte(), 214.toByte(), 183.toByte(), 136.toByte(), 214.toByte(), 155.toByte(), 145.toByte(), 203.toByte(), 229.toByte(), 197.toByte(), 172.toByte(), 197.toByte(), 240.toByte(), 139.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 157.toByte(), 215.toByte(), 172.toByte(), 224.toByte(), 175.toByte(), 196.toByte(), 194.toByte(), 143.toByte(), 216.toByte(), 133.toByte(), 214.toByte(), 164.toByte(), 134.toByte(), 209.toByte(), 182.toByte(), 188.toByte(), 177.toByte(), 228.toByte(), 253.toByte(), 158.toByte(), 215.toByte(), 205.toByte(), 218.toByte(), 183.toByte(), 190.toByte(), 213.toByte(), 151.toByte(), 189.toByte(), 200.toByte(), 245.toByte(), 235.toByte(), 165.toByte(), 236.toByte(), 218.toByte(), 129.toByte(), 181.toByte(), 162.toByte(), 214.toByte(), 160.toByte(), 156.toByte(), 213.toByte(), 164.toByte(), 241.toByte(), 172.toByte(), 193.toByte(), 213.toByte(), 140.toByte(), 214.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 220.toByte(), 140.toByte(), 166.toByte(), 190.toByte(), 246.toByte(), 249.toByte(), 159.toByte(), 247.toByte(), 224.toByte(), 218.toByte(), 169.toByte(), 163.toByte(), 212.toByte(), 143.toByte(), 170.toByte(), 203.toByte(), 229.toByte(), 243.toByte(), 166.toByte(), 254.toByte(), 208.toByte(), 133.toByte(), 189.toByte(), 184.toByte(), 213.toByte(), 173.toByte(), 176.toByte(), 216.toByte(), 128.toByte(), 231.toByte(), 173.toByte(), 254.toByte(), 215.toByte(), 131.toByte(), 194.toByte(), 134.toByte(), 223.toByte(), 143.toByte(), 185.toByte(), 57.toByte(), 58.toByte(), 26.toByte(), 119.toByte(), 106.toByte(), 165.toByte(), 194.toByte(), 251.toByte(), 134.toByte(), 185.toByte(), 152.toByte(), 213.toByte(), 152.toByte(), 146.toByte(), 213.toByte(), 146.toByte(), 214.toByte(), 165.toByte(), 255.toByte(), 227.toByte(), 140.toByte(), 234.toByte(), 184.toByte(), 213.toByte(), 185.toByte(), 143.toByte(), 214.toByte(), 141.toByte(), 187.toByte(), 188.toByte(), 194.toByte(), 245.toByte(), 150.toByte(), 214.toByte(), 235.toByte(), 214.toByte(), 172.toByte(), 155.toByte(), 213.toByte(), 130.toByte(), 191.toByte(), 203.toByte(), 224.toByte(), 223.toByte(), 165.toByte(), 194.toByte(), 251.toByte(), 134.toByte(), 185.toByte(), 152.toByte(), 220.toByte(), 140.toByte(), 186.toByte(), 214.toByte(), 166.toByte(), 207.toByte(), 172.toByte(), 202.toByte(), 242.toByte(), 143.toByte(), 233.toByte(), 155.toByte(), 213.toByte(), 188.toByte(), 178.toByte(), 213.toByte(), 165.toByte(), 158.toByte(), 182.toByte(), 246.toByte(), 203.toByte(), 156.toByte(), 203.toByte(), 200.toByte(), 214.toByte(), 182.toByte(), 170.toByte(), 216.toByte(), 134.toByte(), 135.toByte(), 201.toByte(), 219.toByte(), 243.toByte(), 164.toByte(), 194.toByte(), 226.toByte(), 132.toByte(), 177.toByte(), 137.toByte(), 214.toByte(), 173.toByte(), 163.toByte(), 214.toByte(), 142.toByte(), 222.toByte(), 165.toByte(), 255.toByte(), 240.toByte(), 136.toByte(), 229.toByte(), 161.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 188.toByte(), 241.toByte(), 245.toByte(), 159.toByte(), 253.toByte(), 213.toByte(), 209.toByte(), 182.toByte(), 161.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 201.toByte(), 228.toByte(), 228.toByte(), 170.toByte(), 254.toByte(), 231.toByte(), 134.toByte(), 151.toByte(), 189.toByte(), 213.toByte(), 156.toByte(), 146.toByte(), 214.toByte(), 187.toByte(), 233.toByte(), 168.toByte(), 197.toByte(), 235.toByte(), 143.toByte(), 243.toByte(), 156.toByte(), 213.toByte(), 185.toByte(), 152.toByte(), 213.toByte(), 178.toByte(), 130.toByte(), 191.toByte(), 255.toByte(), 237.toByte(), 158.toByte(), 250.toByte(), 224.toByte(), 220.toByte(), 140.toByte(), 187.toByte(), 215.toByte(), 143.toByte(), 138.toByte(), 200.toByte(), 196.toByte(), 201.toByte(), 170.toByte(), 224.toByte(), 250.toByte(), 135.toByte(), 143.toByte(), 170.toByte(), 214.toByte(), 140.toByte(), 138.toByte(), 213.toByte(), 169.toByte(), 227.toByte(), 172.toByte(), 205.toByte(), 241.toByte(), 140.toByte(), 254.toByte(), 176.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 176.toByte(), 235.toByte(), 246.toByte(), 150.toByte(), 214.toByte(), 234.toByte()
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }

    private fun applyLanguage() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val localeCode = prefs.getString("app_locale", "")
        if (!localeCode.isNullOrEmpty()) {
            // 用户手动选择了语言
            val localeListCompat = androidx.core.os.LocaleListCompat.forLanguageTags(localeCode)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeListCompat)
        } else {
            // 跟随系统 - 检查系统语言是否在支持范围内
            val systemLocale = java.util.Locale.getDefault().language
            val supportedLanguages = listOf("zh", "en", "ja", "ko")
            if (systemLocale !in supportedLanguages) {
                // 系统语言不在支持范围内，fallback到英文
                val localeListCompat = androidx.core.os.LocaleListCompat.forLanguageTags("en")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeListCompat)
            }
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
}
