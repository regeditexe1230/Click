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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yjc.click.ui.home.HomeScreen
import com.yjc.click.ui.shell.MainScaffold
import com.yjc.click.ui.shell.MainTab
import com.yjc.click.ui.theme.AppTheme
import com.yjc.click.ui.theme.ClickTheme
import com.yjc.click.ui.theme.ThemeReveal

class MainActivity : AppCompatActivity() {

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
    private var gesturePresets by mutableStateOf<List<GestureStore.Preset>>(emptyList())
    private var selectedGestureId by mutableStateOf<String?>(null)
    private var manualPresets by mutableStateOf<List<ManualStore.Preset>>(emptyList())
    private var selectedManualId by mutableStateOf<String?>(null)
    // 二级面板的展开状态与「未保存的编辑」都只活在内存里：返回/切页都还在，退出 App 就没了
    private var expandedManualIds by mutableStateOf<Set<String>>(emptySet())
    private var manualDrafts by mutableStateOf<Map<String, ManualStore.Preset>>(emptyMap())
    private var manualUndo by mutableStateOf<Map<String, List<ManualStore.Preset>>>(emptyMap())
    private var manualRedo by mutableStateOf<Map<String, List<ManualStore.Preset>>>(emptyMap())
    private var canStart by mutableStateOf(false)
    private var showStartOverlay by mutableStateOf(false)
    private var canStop by mutableStateOf(false)
    private var canRecord by mutableStateOf(false)
    private var isWarningDialogShowing = false
    /**
     * 是否已经向系统申请过一次通知权限。必须持久化：只有"申请过 + 系统不再允许弹框"
     * 才能判定用户点过「拒绝且不再提示」，重启应用后依然要能判断出来。
     */
    private var notificationPermissionAsked: Boolean
        get() = getPreferences(Context.MODE_PRIVATE)
            .getBoolean("notification_permission_asked", false)
        set(value) {
            getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean("notification_permission_asked", value).apply()
        }
    /** 系统权限弹窗正在显示：这时候别再叠我们自己的说明框 */
    private var notificationRequestInFlight = false
    /** 说明框是否正在显示，避免 onResume 反复触发时叠出好几个 */
    private var notificationDialogShowing = false
    /** 说明框本体：系统权限弹窗晚一步出现、用户又点了「允许」时，要能把它自己收掉 */
    private var notificationDialog: androidx.appcompat.app.AlertDialog? = null
    /** onCreate 之后的第一轮 onResume 跳过通知检查（那一轮交给 onCreate 去申请） */
    private var resumedOnce = false
    /** 「悬浮通知」说明框是否正在显示（点「添加手势」时按需弹，勾过「不再提醒」就不再弹） */
    private var headsUpDialogShowing = false
    /** 「后台弹出界面」说明框是否正在显示（回到前台时按需弹） */
    private var backPopupDialogShowing = false

    /**
     * Android 13+ 通知权限申请：允许了就结束；被拒就弹我们自己的说明框，
     * 点「去开启」跳到系统通知设置，回来后 onResume 还会再查一次（直到允许为止）。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationRequestInFlight = false
        // 允许了也要再看一眼应用级开关：系统权限给了、但用户在设置里把整个应用的通知关掉的情况
        if (!granted || !canPostNotifications()) {
            window.decorView.post { showNotificationPermissionDialog() }
        }
    }

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
        // 主题/深浅色状态从偏好读入（同一份逻辑服务侧也在用）
        AppTheme.loadFrom(this)
        // 应用背景（选中预设里的图片与透明度）也在这里读一次，冷启动时外壳就能画对
        AppBackground.refresh(this)

        // 更新偏好读一次；开了"自动检查更新"就自动查一次，查到新版本自动弹窗（一次进程只弹一次）
        UpdateStore.load(this)
        if (UpdateStore.autoCheck) UpdateManager.check(this, lifecycleScope, auto = true)

        // 外壳（顶栏 + 页面 + 底部导航）全部为 Compose；
        // 设置页仍是 Fragment（内部弹窗依然是 View），由 AndroidView 承载。
        // inset 处理：顶栏补状态栏、底栏补导航栏（旧实现是给 toolbar / bottomNav 分别设 padding），
        // 键盘 inset 由 Column 的 imePadding 承担（旧实现是给 root 设 padding）。
        setContent {
            // 订阅字体变化：设置页里换字体时，已经组合好的顶栏/底栏/首页也要跟着换
            // 只有深浅色影响系统栏图标的明暗；系统栏本身是透明的，背景靠页面自己画，
            // 所以换颜色/动态取色都不需要重新下发（之前订阅了 useDynamicColor，会在
            // 开关切换动画途中多调一次 enableEdgeToEdge，看着就是"卡一下"）。
            LaunchedEffect(AppTheme.isDark) {
                applySystemBarStyle(AppTheme.isDark)
            }
            val fontRevision = FontManager.fontRevision
            val family = androidx.compose.runtime.remember(fontRevision) {
                FontManager.currentTypeface?.let { FontFamily(it) }
            }
            ClickTheme(
                fontFamily = family,
                integerFontAdvance = true,
                darkTheme = AppTheme.isDark,
                dynamicColor = AppTheme.useDynamicColor,
                colorKey = AppTheme.colorKey,
            ) {
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
                    manualPresets = manualPresets,
                    selectedManualId = selectedManualId,
                    expandedManualIds = expandedManualIds,
                    manualDrafts = manualDrafts,
                    manualUndoCounts = manualUndo.mapValues { it.value.size },
                    manualRedoCounts = manualRedo.mapValues { it.value.size },
                    delay = delayText,
                    repeat = repeatText,
                    infinite = infinite,
                    gesturePresets = gesturePresets,
                    selectedGestureId = selectedGestureId,
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
                    onManualSelect = { id -> selectManualPreset(id) },
                    onManualToggle = { id -> toggleManualExpanded(id) },
                    onManualRename = { id -> showManualRenameDialog(id) },
                    onManualDelete = { id -> confirmDeleteManualPreset(id) },
                    onManualAdd = { addManualPreset() },
                    onManualField = { id, field, value -> editManualField(id, field, value) },
                    onManualUndo = { id -> undoManualEdit(id) },
                    onManualRedo = { id -> redoManualEdit(id) },
                    onManualSave = { id -> saveManualPreset(id) },
                    onManualCancel = { id -> cancelManualEdit(id) },
                    onDelayChange = { delayText = it.numericInput(); afterInputChange() },
                    onRepeatChange = { repeatText = it.numericInput(); afterInputChange() },
                    onInfiniteChange = { infinite = it; saveConfig() },
                    onStart = { handleStartButtonClick() },
                    onStop = {
                        AppConfig.running = false
                        stopService(Intent(this, FloatingService::class.java))
                        updateStatus()
                    },
                    onAddGesture = { startGestureRecording() },
                    onGestureSelect = { id -> selectGesturePreset(id) },
                    onGestureRename = { id -> showGestureRenameDialog(id) },
                    onGestureDelete = { id -> confirmDeleteGesturePreset(id) },
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
            // 手势预设也要从磁盘恢复：录好的轨迹不再只活在内存里
            syncGesturePresets()
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
        // 用户自己回到 App = 放弃这次录制：待录制状态和那条「点此开始录制手势」通知都撤掉
        FloatingService.cancelPendingRecording(this)

        // 每次回到前台都查一遍通知权限：用户可能在系统设置里把它撤销了（没重启应用）。
        // 首轮 onResume 交给 onCreate 去申请，这里跳过，避免两个弹窗叠在一起。
        if (resumedOnce) {
            ensureNotificationPermission()
        } else {
            resumedOnce = true
        }
        // 系统权限弹窗有时候比我们的说明框晚一步才出现：用户在系统弹窗里点了「允许」，
        // 这个说明框就该自己消失，不用用户再手动关一次。
        if (notificationDialogShowing && canPostNotifications()) notificationDialog?.dismiss()

        // 上次录完没能自己跳回前台（MIUI/HyperOS 的「后台弹出界面」被关）：回来时说明一次。
        // 那个开关系统不给普通应用读，只能拿「界面回没回来」当判据。
        // 通知权限的弹窗/系统申请框在的时候先让路（标志留着，下一轮 onResume 再弹），免得两个框叠一起。
        if (!notificationDialogShowing && !notificationRequestInFlight &&
            PromptFlags.consumeReturnBlocked(this) && !PromptFlags.isBackPopupMuted(this)
        ) {
            window.decorView.post { showBackPopupDialog() }
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
        // 手势列表先跟磁盘对齐：服务里刚录完的那条会在这里冒出来，并成为当前手势
        syncGesturePresets()
        // 手动参数同理：选中那条的 5 个值就是运行时用的值
        syncManualPresets()

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
    }

    /** 从磁盘读手动参数预设，并把选中那条的 5 个值交给运行时配置（手动参数模式执行时用的就是它） */
    private fun syncManualPresets() {
        val snapshot = ManualStore.load(this)
        manualPresets = snapshot.presets
        selectedManualId = snapshot.selectedId
        val selected = snapshot.selected
        swipeX1 = selected?.x1 ?: ""
        swipeY1 = selected?.y1 ?: ""
        swipeX2 = selected?.x2 ?: ""
        swipeY2 = selected?.y2 ?: ""
        swipeDuration = selected?.duration ?: ""
        // 必须一起写进运行时配置：否则切换预设或保存编辑后，悬浮球执行用的还是上一次的值
        saveConfig()
    }

    private fun selectManualPreset(id: String) {
        if (selectedManualId == id) return
        val snapshot = ManualStore.load(this)
        ManualStore.save(this, ManualStore.Snapshot(snapshot.presets, id))
        updateStatus()
    }

    private fun toggleManualExpanded(id: String) {
        expandedManualIds =
            if (id in expandedManualIds) expandedManualIds - id else expandedManualIds + id
    }

    /** 新增预设：5 个值复制当前选中那条（没有选中就给空值），并展开它方便接着改 */
    private fun addManualPreset() {
        val snapshot = ManualStore.load(this)
        val preset = ManualStore.newPreset(snapshot.presets, snapshot.selected)
        ManualStore.save(this, ManualStore.Snapshot(snapshot.presets + preset, preset.id))
        expandedManualIds = expandedManualIds + preset.id
        updateStatus()
    }

    /** 重命名手动参数预设（清空后确定 = 回到「预设一 / 预设二」这种自动编号名） */
    private fun showManualRenameDialog(id: String) {
        val preset = manualPresets.firstOrNull { it.id == id } ?: return
        RenameDialog.show(this, R.string.rename_preset, ManualStore.displayName(this, preset)) { newName ->
            val snapshot = ManualStore.load(this)
            val updated = snapshot.presets.map { if (it.id == id) it.copy(name = newName) else it }
            ManualStore.save(this, ManualStore.Snapshot(updated, snapshot.selectedId))
            updateStatus()
        }
    }

    /** 删除手动参数预设前先确认（与设置页删背景预设同一套弹窗） */
    private fun confirmDeleteManualPreset(id: String) {
        val preset = manualPresets.firstOrNull { it.id == id } ?: return
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.delete_preset)
            .setMessage(getString(R.string.delete_preset_confirm, ManualStore.displayName(this, preset)))
            .setPositiveButton(R.string.delete) { _, _ -> deleteManualPreset(id) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun deleteManualPreset(id: String) {
        val snapshot = ManualStore.load(this)
        val remaining = snapshot.presets.filterNot { it.id == id }
        // 删掉的正好是选中的那条 → 落到剩下的第一条（都删完了就是没有）
        val selected = snapshot.selectedId
            ?.takeIf { it != id && remaining.any { p -> p.id == it } }
            ?: remaining.firstOrNull()?.id
        ManualStore.save(this, ManualStore.Snapshot(remaining, selected))
        // 这条的编辑草稿和撤销历史一起清掉
        manualDrafts = manualDrafts - id
        manualUndo = manualUndo - id
        manualRedo = manualRedo - id
        expandedManualIds = expandedManualIds - id
        updateStatus()
    }

    /** 面板里改一个字段：把「改之前」的草稿压进撤销栈、清空重做栈（每步一条，最多留 20 步） */
    private fun editManualField(id: String, field: Int, value: String) {
        val stored = manualPresets.firstOrNull { it.id == id } ?: return
        val current = manualDrafts[id] ?: stored
        val next = when (field) {
            0 -> current.copy(x1 = value.numericInput(true))
            1 -> current.copy(y1 = value.numericInput(true))
            2 -> current.copy(x2 = value.numericInput(true))
            3 -> current.copy(y2 = value.numericInput(true))
            else -> current.copy(duration = value.numericInput())
        }
        if (next == current) return
        manualUndo = manualUndo + (id to ((manualUndo[id] ?: emptyList()) + current).takeLast(20))
        manualRedo = manualRedo - id
        manualDrafts = manualDrafts + (id to next)
    }

    private fun undoManualEdit(id: String) {
        val stack = manualUndo[id] ?: return
        if (stack.isEmpty()) return
        val stored = manualPresets.firstOrNull { it.id == id } ?: return
        val current = manualDrafts[id] ?: stored
        manualUndo = manualUndo + (id to stack.dropLast(1))
        manualRedo = manualRedo + (id to ((manualRedo[id] ?: emptyList()) + current))
        manualDrafts = manualDrafts + (id to stack.last())
    }

    private fun redoManualEdit(id: String) {
        val stack = manualRedo[id] ?: return
        if (stack.isEmpty()) return
        val stored = manualPresets.firstOrNull { it.id == id } ?: return
        val current = manualDrafts[id] ?: stored
        manualRedo = manualRedo + (id to stack.dropLast(1))
        manualUndo = manualUndo + (id to ((manualUndo[id] ?: emptyList()) + current))
        manualDrafts = manualDrafts + (id to stack.last())
    }

    /** 保存：写盘 + 让运行时用上新值；面板保持展开、展开状态也留着，撤销历史清空 */
    private fun saveManualPreset(id: String) {
        val draft = manualDrafts[id] ?: return
        val snapshot = ManualStore.load(this)
        if (snapshot.presets.none { it.id == id }) return
        ManualStore.save(
            this,
            ManualStore.Snapshot(ManualStore.withPreset(snapshot.presets, draft), snapshot.selectedId),
        )
        manualDrafts = manualDrafts - id
        manualUndo = manualUndo - id
        manualRedo = manualRedo - id
        updateStatus()
    }

    /** 取消：丢掉内存里的编辑，面板里显示回已保存的值（面板不收起） */
    private fun cancelManualEdit(id: String) {
        manualDrafts = manualDrafts - id
        manualUndo = manualUndo - id
        manualRedo = manualRedo - id
    }

    /** 从磁盘读手势预设列表，并把选中的那条交给运行时配置（执行滑动时用的就是它；没得选就是空手势） */
    private fun syncGesturePresets() {
        val snapshot = GestureStore.load(this)
        gesturePresets = snapshot.presets
        selectedGestureId = snapshot.selectedId
        AppConfig.recordedGesture = snapshot.gesture()
    }

    /** 选中某条手势预设 */
    private fun selectGesturePreset(id: String) {
        val snapshot = GestureStore.load(this)
        if (snapshot.selectedId == id) return
        GestureStore.save(this, GestureStore.Snapshot(snapshot.presets, id))
        updateStatus()
    }

    /** 重命名手势预设：弹窗预填当前名字，清空后确定 = 回到「手势一 / 手势二」这种自动编号名 */
    private fun showGestureRenameDialog(id: String) {
        val preset = gesturePresets.firstOrNull { it.id == id } ?: return
        RenameDialog.show(this, R.string.rename_gesture, GestureStore.displayName(this, preset)) { newName ->
            val snapshot = GestureStore.load(this)
            val updated = snapshot.presets.map { if (it.id == id) it.copy(name = newName) else it }
            GestureStore.save(this, GestureStore.Snapshot(updated, snapshot.selectedId))
            updateStatus()
        }
    }

    /** 删除手势预设前先确认（与设置页删背景预设同一套弹窗） */
    private fun confirmDeleteGesturePreset(id: String) {
        val preset = gesturePresets.firstOrNull { it.id == id } ?: return
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.delete_gesture)
            .setMessage(getString(
                R.string.delete_gesture_confirm,
                GestureStore.displayName(this, preset),
            ))
            .setPositiveButton(R.string.delete) { _, _ -> deleteGesturePreset(id) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun deleteGesturePreset(id: String) {
        val snapshot = GestureStore.load(this)
        val remaining = snapshot.presets.filterNot { it.id == id }
        // 删掉的正好是选中的那条 → 落到剩下的第一条（都删完了就是没有）
        val selected = snapshot.selectedId
            ?.takeIf { it != id && remaining.any { p -> p.id == it } }
            ?: remaining.firstOrNull()?.id
        GestureStore.save(this, GestureStore.Snapshot(remaining, selected))
        updateStatus()
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

    /** 数字输入框过滤：只留数字（坐标允许一个小数点）并限制长度，避免粘进一长串数字后原样存进偏好 */
    private fun String.numericInput(allowDot: Boolean = false, maxLength: Int = 7): String {
        val sb = StringBuilder()
        var hasDot = false
        for (ch in this) {
            if (ch.isDigit()) {
                sb.append(ch)
            } else if (allowDot && ch == '.' && !hasDot) {
                hasDot = true
                sb.append(ch)
            }
            if (sb.length >= maxLength) break
        }
        return sb.toString()
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

    /**
     * 开始手势录制（新版流程，没有遮罩）：
     * 发一条高优先级录制通知 → 立刻退到后台/桌面 → 用户切到目标应用点通知开始录制。
     * 录制时屏幕上会盖一层半透明遮罩来收下这一划，所以需要悬浮窗权限。
     */
    private fun startGestureRecording() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, R.string.toast_need_accessibility_for_record, Toast.LENGTH_LONG).show()
            return
        }
        // 录制靠半透明遮罩收触摸，必须能画在别的应用上层
        if (!hasOverlayPermission()) {
            Toast.makeText(this, R.string.toast_need_overlay_for_record, Toast.LENGTH_LONG).show()
            return
        }
        // 录制通知就是这次的入口，通知被禁用的话整个流程走不通 —— 直接弹窗引导开启，
        // 用户可能是在没重启应用的情况下把权限撤销的，这里 force=true 再问一次
        if (!canPostNotifications()) {
            ensureNotificationPermission(force = true)
            return
        }
        // 悬浮通知（横幅）被关掉的话，录制提示浮不出来 —— 这里弹一次说明框。
        // 放在点「添加手势」的时候检查，而不是每次启动，免得用户还没要录就被拦一下。
        if (checkRecordingHeadsUpBeforeRecord()) return
        startRecordingSession()
    }

    /** 真正开始这次录制：发通知 + 退到后台，等用户切到目标应用去点那条通知 */
    private fun startRecordingSession() {
        if (isFinishing) return
        saveConfig()
        AppConfig.preventExecution = false
        FloatingService.prepareRecording(this)
        Toast.makeText(this, R.string.toast_record_switch_to_app, Toast.LENGTH_SHORT).show()
        // 用户要切到目标应用去滑动，本应用不能挡在前面
        moveTaskToBack(true)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        // 13 以下没有运行时权限，但用户仍可能在通知设置里把整个应用关掉
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
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
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
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
     * Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 运行时权限，被关掉通知的话
     * 录制流程整个走不通（录制就是靠一条通知启动的）。
     *
     * 守门逻辑按用户要求：没有就申请；被拒就弹说明框，点「去开启」跳到系统设置，
     * 回到前台再查一遍 —— 直到用户真的允许为止。每次启动、每次回到前台、
     * 以及点「添加手势」时都会走一遍这里。
     *
     * @param force 点「添加手势」时用：即使本轮已经问过，也要再提示一次
     */
    private fun ensureNotificationPermission(force: Boolean = false) {
        if (canPostNotifications()) return
        if (notificationRequestInFlight) return
        // 只有"运行时权限还没给"这一种情况系统弹窗才有用；权限已经给了、但用户在设置里关掉了
        // 整个应用的通知时，再调系统申请什么都不会发生，只能直接引导去设置页
        val needsRuntime =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        if (needsRuntime) {
            // 用户点过「拒绝且不再提示」：rationale 为 false 且已经申请过，系统不会再弹申请框，
            // 这时候再调 launch 只会立刻回调被拒 —— 直接送到设置页，不再尝试申请
            val cannotAskAgain = notificationPermissionAsked &&
                !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
            if (cannotAskAgain) {
                // 点「添加手势」时直接去设置页；启动时的检查还是先给个说明框，
                // 免得用户一打开应用就被弹到系统设置里
                if (force) openNotificationSettings() else showNotificationPermissionDialog()
                return
            }
            if (force || !notificationPermissionAsked) {
                notificationPermissionAsked = true
                notificationRequestInFlight = true
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // 系统弹窗不会再来（拒绝过两次 / 系统级关闭）→ 只能引导去设置里开
        showNotificationPermissionDialog()
    }

    /** 通知权限被拒时的说明框：只有一个「去开启」，点完跳系统设置 */
    private fun showNotificationPermissionDialog() {
        if (notificationDialogShowing || isFinishing) return
        notificationDialogShowing = true
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.notification_permission_confirm) { d, _ ->
                d.dismiss()
                openNotificationSettings()
            }
            .setOnDismissListener {
                notificationDialogShowing = false
                notificationDialog = null
            }
            .create()
        notificationDialog = dialog
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
                Toast.makeText(applicationContext, R.string.notification_permission_title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 点「添加手势」时再看一眼录制通道：被改成静默/低重要性的话，切到目标应用后
     * 录制提示不会浮到屏幕上，用户会找不到开始录制的入口。
     *
     * 这个开关应用自己改不了（通道重要性只有用户能改），所以只能提示 + 一键跳到该通道的设置页。
     * 用户勾过「不再提醒」就不再检查。
     *
     * @return true = 说明框已经弹出来了，这次先别往下走
     */
    private fun checkRecordingHeadsUpBeforeRecord(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (PromptFlags.isHeadsUpMuted(this)) return false
        if (isFinishing || headsUpDialogShowing) return false
        if (FloatingService.isRecordingHeadsUpEnabled(this)) return false
        showRecordingHeadsUpDialog()
        return true
    }

    /**
     * 「悬浮通知」被关掉时的说明框：「去开启」跳到这条通知通道的设置页；
     * 「取消」= 这次照样录（只是可能不弹横幅，通知栏里那条还在）。
     * 底部一行：左边「不再提醒」勾选框，右边「取消 / 去开启」；勾上（点哪个按钮都算）以后都不再检查。
     */
    private fun showRecordingHeadsUpDialog() {
        headsUpDialogShowing = true
        val row = dontAskRow(R.string.notification_heads_up_confirm)
        fun muteIfChecked() {
            if (row.box.isChecked) PromptFlags.setHeadsUpMuted(this, true)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.notification_heads_up_title)
            .setMessage(R.string.notification_heads_up_message)
            .setView(row.view)
            .setOnCancelListener {
                muteIfChecked()
                startRecordingSession()
            }
            .setOnDismissListener { headsUpDialogShowing = false }
            .create()
        row.cancel.setOnClickListener {
            muteIfChecked()
            dialog.dismiss()
            startRecordingSession()
        }
        row.confirm.setOnClickListener {
            muteIfChecked()
            dialog.dismiss()
            openRecordingChannelSettings()
        }
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    /** 「不再提醒」勾选框 + 取消/确认两个按钮，供弹窗底部那一行用 */
    private class DontAskRow(
        val view: android.view.View,
        val box: android.widget.CheckBox,
        val cancel: android.widget.Button,
        val confirm: android.widget.Button,
    )

    /** 现拼一个底部行：左边勾选框占满剩余宽度，右边两个文字按钮（省得为它单开布局文件） */
    private fun dontAskRow(confirmTextRes: Int): DontAskRow {
        val ctx = AppTheme.viewContext(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, (4 * dp).toInt(), (8 * dp).toInt(), 0)
        }
        val box = dontAskAgainCheckBox().apply { setPadding(0, 0, 0, 0) }
        row.addView(
            box,
            android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        val cancel = rowTextButton(ctx, R.string.cancel)
        val confirm = rowTextButton(ctx, confirmTextRes)
        row.addView(cancel)
        row.addView(confirm)
        return DontAskRow(row, box, cancel, confirm)
    }

    private fun rowTextButton(ctx: android.content.Context, textRes: Int): android.widget.Button {
        return android.widget.Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            setText(textRes)
            isAllCaps = false
        }
    }

    /** 弹窗里那个「不再提醒」勾选框（现拼一个，省得为它单开布局文件） */
    private fun dontAskAgainCheckBox(): android.widget.CheckBox {
        return android.widget.CheckBox(AppTheme.viewContext(this)).apply {
            setText(R.string.dialog_dont_ask_again)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
    }

    /**
     * 上次录制结束时没能自己跳回前台 —— 基本就是 MIUI/HyperOS 的「后台弹出界面」被关了。
     * 系统没给普通应用读这个开关的接口，所以只能说拿「回没回来」当判据，
     * 等用户自己回到应用时说明一次，并给一个直达那个权限页的入口。
     */
    private fun showBackPopupDialog() {
        if (backPopupDialogShowing || isFinishing) return
        backPopupDialogShowing = true
        val row = dontAskRow(R.string.back_popup_confirm)
        fun muteIfChecked() {
            if (row.box.isChecked) PromptFlags.setBackPopupMuted(this, true)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.back_popup_title)
            .setMessage(R.string.back_popup_message)
            .setView(row.view)
            .setOnCancelListener { muteIfChecked() }
            .setOnDismissListener { backPopupDialogShowing = false }
            .create()
        row.cancel.setOnClickListener {
            muteIfChecked()
            dialog.dismiss()
        }
        row.confirm.setOnClickListener {
            muteIfChecked()
            dialog.dismiss()
            openBackPopupPermissionSettings()
        }
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    /** 跳到 MIUI/HyperOS 安全中心里本应用的「其他权限」页；非 MIUI 退回应用详情页 */
    private fun openBackPopupPermissionSettings() {
        try {
            startActivity(
                Intent().setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                ).putExtra("extra_pkgname", packageName)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
                Toast.makeText(applicationContext, R.string.back_popup_title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 跳到「手势录制」这一条通知通道的设置页，比应用通知总页少点几步 */
    private fun openRecordingChannelSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, FloatingService.recordingChannelId())
            )
        } catch (e: Exception) {
            openNotificationSettings()
        }
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
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
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
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(AppTheme.viewContext(this))
            .setTitle(R.string.floating_ball_instructions)
            .setMessage(R.string.floating_ball_instructions_message)
            .setPositiveButton(R.string.ok) { _, _ -> onStart() }
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
        FontManager.applyFontToDialog(dialog)
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
