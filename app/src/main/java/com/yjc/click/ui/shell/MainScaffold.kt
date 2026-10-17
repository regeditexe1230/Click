package com.yjc.click.ui.shell

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.yjc.click.AppBackground
import com.yjc.click.ImageLoader
import com.yjc.click.R
import com.yjc.click.UpdateManager
import com.yjc.click.UpdateStore
import com.yjc.click.ui.UpdateDialog
import com.yjc.click.ui.licenses.LicensesPage
import com.yjc.click.ui.licenses.LicensesPageContent
import com.yjc.click.ui.program.LocalProgramFlip
import com.yjc.click.ui.program.ProgramFlipOverlay
import com.yjc.click.ui.program.ProgramFlipState
import com.yjc.click.ui.program.programListTurnstile
import com.yjc.click.ui.theme.AppTheme
import com.yjc.click.ui.theme.ClickText
import com.yjc.click.ui.theme.PlatformEasing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 底部导航项（与旧 menu/bottom_nav_menu.xml 一致） */
enum class MainTab { HOME, PROGRAM, SETTINGS }

/** 整页过渡用的缓动：cubic-bezier(0.2, 0, 0, 1)（进入快、收尾缓） */
private val ExpandEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 程序页磁贴翻转的时长：必须和 ProgramScreen.FLIP_MS 一致，两条栏才和 3D 动画同一条时间轴。 */
/**
 * 两条栏收起/放回的时长。
 * 伸缩档：两条栏的收起/展开与页面动画同一条时间轴。
 * FLIP 档是 600ms，要和 ProgramScreen.FLIP_MS 一致 —— 切回 FLIP 时这里也要改成 600。
 */
private const val BAR_FLIP_MS = 700

/**
 * 应用外壳：旧 activity_main.xml（顶栏 + 页面 + BottomNavigationView）的 Compose 版本。
 *
 * 几何取自旧版实测（density 2.625）：
 * - 顶栏容器 96dp（状态栏 42px + MaterialToolbar 64dp），标题块距栏顶 76px、块高 78px（22sp 标定行盒）
 * - 底部导航 80dp + 系统导航栏 inset；每项宽 120dp，指示器/图标容器 64×32dp（距顶 12dp），
 *   图标 24dp，文字 12sp（标定行盒 48px），文字块距项顶 130px
 *
 * 过渡动画按旧实现逐项还原：
 * - 首次启动底栏从底部滑入（300ms，延迟 100ms，DecelerateInterpolator(2f)）
 * - 切页左右滑动（360ms fast_out_slow_in，±10%）+ 淡入（延迟 126ms/162ms linear）/ 淡出（126ms）
 * - 顶栏标题淡出 126ms → 换字 → 淡入 162ms
 * - 选中图标沿用旧 drawable（animated-selector），形变动画由系统 drawable 播放
 * - 程序图标 1 → 0.7 → 1.1 → 1（每段 100ms）
 * - 开放源代码许可整页：许可页从右侧 10% 滑入、外壳向左滑出 10%（都是 360ms cubic-bezier(0.2,0,0,1)），
 *   两者前后交错淡入/淡出（126ms / 延迟 126ms 的 162ms），底栏同时向下收起 380ms（返回时 420ms 升回）
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainScaffold(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    title: String,
    playEntrance: Boolean,
    homeContent: @Composable () -> Unit,
    programContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    promptContent: @Composable () -> Unit = {},
    /** 程序页打开节点图时为 true：底栏一起收起，把整屏让给画布（含翻转放大动画）。 */
    programFullScreen: Boolean = false,
) {
    val surface = MaterialTheme.colorScheme.surface

    // 底栏入场
    val entrance = remember { Animatable(if (playEntrance) 1f else 0f) }
    LaunchedEffect(playEntrance) {
        if (playEntrance) {
            delay(100)
            entrance.animateTo(0f, tween(300, easing = PlatformEasing(
                android.view.animation.DecelerateInterpolator(2f))))
        }
    }

    // 顶栏标题淡出→换字→淡入
    var displayedTitle by remember { mutableStateOf(title) }
    val titleAlpha = remember { Animatable(1f) }
    LaunchedEffect(title) {
        if (displayedTitle != title) {
            titleAlpha.animateTo(0f, tween(126, easing = LinearEasing))
            displayedTitle = title
        }
        // 连点底栏时上一次的淡入会被取消、alpha 停在半路，收尾的淡入放在判断外面保证一定淡回来
        titleAlpha.animateTo(1f, tween(162, easing = LinearEasing))
    }

    // 设置页（Fragment 宿主）常驻，用 View 的显隐 + 自身滑动/淡入淡出与旧版三视图显隐等价
    val settingsSlide = remember { Animatable(if (selectedTab == MainTab.SETTINGS) 0f else 1f) }
    val settingsAlpha = remember { Animatable(if (selectedTab == MainTab.SETTINGS) 1f else 0f) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.SETTINGS) {
            settingsSlide.snapTo(1f)
            settingsAlpha.snapTo(0f)
            launch { settingsSlide.animateTo(0f, tween(360, easing = FastOutSlowInEasing)) }
            delay(126)
            settingsAlpha.animateTo(1f, tween(162, easing = LinearEasing))
        } else {
            launch { settingsSlide.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
            settingsAlpha.animateTo(0f, tween(126, easing = LinearEasing))
        }
    }

    // 开放源代码许可整页的过渡（参照另一套「expand」节奏）：
    // 打开：许可页从右侧 10% 滑入 + 延迟 126ms 的 162ms 淡入；外壳（顶栏+页面）向左滑出 10% + 126ms 淡出；
    //       底栏向下收起 380ms，把整屏让给许可页。
    // 返回：整体反向——外壳从左侧 10% 滑回 + 延迟 126ms 的 162ms 淡入，底栏 420ms 升回。
    val licensesVisible = LicensesPage.visible
    // 程序页打开节点图时底栏也要收起：翻转放大的画布要铺满整屏
    // 用户：3D 翻转动画和顶栏底栏动画要同步 —— 两条栏的翻转和高度收起都跟 ProgramScreen 的
    // FLIP_MS 用同一条时间轴（600ms FastOutSlowInEasing），不再一个先动一个延迟 360ms。
    val barFlipTop by animateFloatAsState(
        targetValue = if (programFullScreen) 1f else 0f,
        animationSpec = tween(BAR_FLIP_MS, easing = FastOutSlowInEasing),
        label = "barFlipTop",
    )
    val barFlipBottom by animateFloatAsState(
        targetValue = if (programFullScreen) 1f else 0f,
        animationSpec = tween(BAR_FLIP_MS, easing = FastOutSlowInEasing),
        label = "barFlipBottom",
    )

    val shellSlide = remember { Animatable(if (licensesVisible) 1f else 0f) }
    val shellAlpha = remember { Animatable(if (licensesVisible) 0f else 1f) }
    // 许可页那条（380/420ms，原样保留）
    val barRetract = remember { Animatable(if (licensesVisible) 1f else 0f) }
    // 程序页翻转那条：单独一条，才能和 3D 翻转同为 600ms；两条取大值给底栏/顶栏做高度收起
    val flipRetract = remember { Animatable(if (programFullScreen) 1f else 0f) }
    LaunchedEffect(licensesVisible, programFullScreen) {
        launch { shellSlide.animateTo(if (licensesVisible) 1f else 0f, tween(360, easing = ExpandEasing)) }
        launch {
            barRetract.animateTo(
                if (licensesVisible) 1f else 0f,
                tween(if (licensesVisible) 380 else 420, easing = FastOutSlowInEasing),
            )
        }
        if (licensesVisible) {
            shellAlpha.animateTo(0f, tween(126, easing = LinearEasing))
        } else {
            delay(126)
            shellAlpha.animateTo(1f, tween(162, easing = LinearEasing))
        }
    }
    LaunchedEffect(programFullScreen) {
        // 进/出程序页：两条栏都走同一条伸缩动画（与卡片放大的 320ms 同步），不再一边瞬间一边动画
        flipRetract.animateTo(
            if (programFullScreen) 1f else 0f,
            tween(BAR_FLIP_MS, easing = FastOutSlowInEasing),
        )
    }
    val retract = maxOf(barRetract.value, flipRetract.value)

    // 翻转层是**窗口级**的：状态放在外壳这一层（在两条栏和页面之外），
    // 由 MainScaffold 自己渲染成最上面的一层，程序页只负责驱动它。
    // 这样顶栏底栏收起/展开引起的页面重排不会把翻转中的卡片带着一起动（实测会漂 224px），
    // 节点图的揭示窗口也是整个窗口（3200）而不是页面那点高度（2528）。
    val programFlip = remember { ProgramFlipState() }

    /**
     * 页面内容区自己的窗口位置。
     * turnstile 的转轴给的是**窗口坐标**（屏幕左外侧 40px、屏幕中线），而变换是加在页面这一层上的，
     * 图层局部坐标里得把这一层的左上角减掉，轴心才对得上。
     */
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    // 两条栏在动画期间**只做视觉收起，不改布局高度**：布局高度一动，整页就会重排，
    // 列表里其它卡片会跟着上下移动 —— 而用户要的是「只有被点的那张卡片动」。
    // 所以：视觉用的 retract 照旧一路跟着动画走，真正把高度收掉等动画放完（600ms + 40ms 余量）再做。
    var barsCollapsed by remember { mutableStateOf(programFullScreen) }
    LaunchedEffect(programFullScreen) {
        // 布局高度立刻跟着变：等动画放完再收的话，真正的图页会在那一刻突然多出两条栏的高度，
        // 里面的顶栏按钮和托盘都要重排一次 —— 看着就是「最后几帧按钮跳了一下」。
        barsCollapsed = programFullScreen
    }
    // 只有许可页那条（barRetract）才允许立刻动布局高度。程序页那 600ms 里 layoutRetract 必须保持 0，
    // 否则页面照样重排（实测：起飞 1 秒后列表已经被顶上去 12px，再往后就是原来那 224px）。
    val layoutRetract = maxOf(barRetract.value, if (barsCollapsed) 1f else 0f)

    // 应用背景：选中预设里的图 + 两个透明度（整屏铺满，顶栏/底栏压在上面）
    val backgroundPath = AppBackground.imagePath
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val backgroundImage = remember(backgroundPath, screenW, screenH) {
        backgroundPath?.let { ImageLoader.load(it, screenW, screenH) }
    }
    // 顶栏/底栏底色：有背景图时**完全不画**（图从状态栏一路贯到导航栏），
    // 没背景图时保持原来的不透明底色（与旧版一致）
    val barAlpha = if (backgroundPath == null) 1f else 0f

    // 更新弹窗用：作用域挂在外壳上，弹窗关掉（比如交给系统安装器）不会把下载的协程一起取消
    val updateContext = LocalContext.current
    val updateScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surface)
            // turnstile 的转轴在「屏幕中线」、透视系数要乘屏宽，所以窗口尺寸得先交给翻转状态：
            // 动画一起步就能算，不用等覆盖层第一次测量（否则第一帧算不出来会闪一下）
            .onSizeChanged { programFlip.window = it },
    ) {
        if (backgroundImage != null) {
            Image(
                bitmap = backgroundImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = AppBackground.imageAlpha / 100f,
                modifier = Modifier.fillMaxSize(),
            )
            if (AppBackground.scrimAlpha > 0) {
                // 遮罩颜色跟随深浅色：浅色下用白色（把亮图压向背景色，深色文字才看得清），
                // 深色下用黑色
                val scrimColor = if (AppTheme.isDark) Color.Black else Color.White
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrimColor.copy(alpha = AppBackground.scrimAlpha / 100f)),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让外壳里的 testTag（nav_home/nav_program/nav_settings）像旧版 View id 一样
                // 暴露成 accessibility resource-id，供 uiautomator/自动化脚本定位
                .semantics { testTagsAsResourceId = true }
                // edge-to-edge：左右补系统栏/挖孔（旧实现给 root 设 padding），底部补键盘
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            TopBar(
                flip = barFlipTop,
                title = displayedTitle,
                alpha = titleAlpha.value,
                surface = surface,
                barAlpha = barAlpha,
                // 程序页翻出去 / 节点图期间顶栏跟着底栏一起收起（同样收高度 + 上移）
                retract = retract,
                // 布局高度要等动画放完再收（动画期间只做视觉收起）—— 见 barsCollapsed
                layoutRetract = layoutRetract,
                // 许可页打开时顶栏跟着页面一起左移 + 淡出
                modifier = Modifier.graphicsLayer {
                    translationX = -0.1f * size.width * shellSlide.value
                    alpha = shellAlpha.value
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 用户：3D 翻转动画要压在顶栏底栏上面。Column 里后画的盖先画的，底栏本来就画在页面之后，
                    // 所以这里给页面抬一层：翻转中的卡片从两条栏上面飞过去，而不是被底栏压住
                    .zIndex(1f)
                    // 许可页打开时页面左移 10% + 淡出（底栏单独做向下收起）
                    .graphicsLayer {
                        translationX = -0.1f * size.width * shellSlide.value
                        alpha = shellAlpha.value
                    }
                    // turnstile 档：打开节点图时整页跟着图页一起转（列表页 0° → −50°，128ms），
                    // 轴心在窗口坐标里，所以先把这一层自己的窗口左上角量出来交给它换算。
                    // 角度为 0（其他档 / 静止态）时这一层什么都不做，点击命中和没有它一样。
                    .onGloballyPositioned { pageOrigin = it.boundsInRoot().topLeft }
                    .programListTurnstile(programFlip, pageOrigin.x, pageOrigin.y),
            ) {
                SettingsHost(
                    visible = selectedTab == MainTab.SETTINGS,
                    modifier = Modifier
                        .fillMaxSize()
                        .settingsSlideOffset(settingsSlide.value)
                        .alpha(settingsAlpha.value),
                )
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enter = slideInHorizontally(
                            animationSpec = tween(360, easing = FastOutSlowInEasing),
                        ) { width -> if (forward) width / 10 else -width / 10 } +
                                fadeIn(animationSpec = tween(162, delayMillis = 126, easing = LinearEasing))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(360, easing = FastOutSlowInEasing),
                        ) { width -> if (forward) -width / 10 else width / 10 } +
                                fadeOut(animationSpec = tween(126, easing = LinearEasing))
                        enter togetherWith exit
                    },
                    label = "pageTransition",
                ) { tab ->
                    when (tab) {
                        // 页面本身不画底色：应用背景图要能透出来（没设图时下层外壳的 surface 同色）
                        MainTab.HOME -> Box(Modifier.fillMaxSize()) { homeContent() }
                        MainTab.PROGRAM -> Box(Modifier.fillMaxSize()) {
                            // 程序页不自己画翻转层：它驱动 programFlip，画的是最外层那个 ProgramFlipOverlay
                            CompositionLocalProvider(LocalProgramFlip provides programFlip) {
                                programContent()
                            }
                        }
                        // 设置页由常驻的 Fragment 宿主呈现（避免重复创建 Fragment）
                        MainTab.SETTINGS -> Box(Modifier.fillMaxSize())
                    }
                }
            }

            BottomBar(

                flip = barFlipBottom,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                surface = surface,
                barAlpha = barAlpha,
                translationY = with(LocalDensity.current) { (entrance.value * 80.dp.toPx()).toInt() },
                retract = retract,
                // 同上：布局高度等动画放完再收
                layoutRetract = layoutRetract,
            )
        }

        // 开放源代码许可：整页盖在外壳上（不是弹窗）。过渡照「expand」节奏——
        // 从右侧 10% 处滑入（360ms cubic-bezier(0.2,0,0,1)）+ 延迟 126ms 的 162ms 淡入；
        // 返回时向右滑出 10% + 126ms 淡出。外壳位移/淡出与底栏收起见上面的 shellSlide/shellAlpha/barRetract。
        AnimatedVisibility(
            visible = licensesVisible,
            enter = slideInHorizontally(
                animationSpec = tween(360, easing = ExpandEasing)
            ) { width -> (0.1f * width).roundToInt() } + fadeIn(
                animationSpec = tween(162, delayMillis = 126, easing = LinearEasing)
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(360, easing = ExpandEasing)
            ) { width -> (0.1f * width).roundToInt() } + fadeOut(
                animationSpec = tween(126, easing = LinearEasing)
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 许可页只是「盖」在外壳上，空白处的点击/拖动要吃掉，否则会穿透到底下的设置页
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
                LicensesPageContent(onBack = { LicensesPage.close() })
            }
        }

        // 更新弹窗挂在外壳上：启动时的自动检查查到新版本、或在设置页点检查更新，都能弹
        val updateRelease = UpdateStore.available
        if (UpdateStore.dialogVisible && updateRelease != null) {
            UpdateDialog(
                release = updateRelease,
                downloading = UpdateStore.downloading,
                progress = UpdateStore.progress,
                errorRes = UpdateStore.error,
                ignored = UpdateStore.ignoredVersion == updateRelease.versionCode,
                onIgnoreChange = { ignore ->
                    UpdateStore.setIgnoredVersion(
                        updateContext,
                        if (ignore) updateRelease.versionCode else 0,
                    )
                },
                onInstall = { UpdateManager.install(updateContext, updateScope, updateRelease) },
                onCancelDownload = { UpdateManager.cancelDownload(updateContext) },
                onLater = { UpdateStore.dismissDialog() },
            )
        }

        // 说明类弹窗（通知权限 / 悬浮通知 / 后台弹出界面）：和更新弹窗同一套 Compose 样式
        promptContent()

        // ---- 窗口级翻转动画层 ----
        // 画在最上面（两条栏、页面、弹窗之后），所以「只有被点的那张卡片动、图从卡片里长出来」
        // 这件事完全不受页面重排影响：这里在动画期间一动不动。
        ProgramFlipOverlay(programFlip)
    }
}

/**
 * 设置页 Fragment 宿主：与旧布局里的 FragmentContainerView 等价。
 *
 * 与旧实现一致，切到设置页时才把 SettingsFragment 加进来（首次），之后靠 View 显隐切换，
 * Fragment 实例与滚动位置都保留；容器 id 固定，旋转重建后 FragmentManager 能恢复。
 */
@Composable
private fun SettingsHost(visible: Boolean, modifier: Modifier = Modifier) {
    var fragmentAdded by remember { mutableStateOf(false) }
    AndroidView(
        factory = { ctx ->
            androidx.fragment.app.FragmentContainerView(ctx).apply {
                id = R.id.settings_page
                visibility = android.view.View.GONE
            }
        },
        update = { view ->
            view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            if (visible && !fragmentAdded) {
                fragmentAdded = true
                view.post {
                    val fm = (view.context as? androidx.fragment.app.FragmentActivity)
                        ?.supportFragmentManager ?: return@post
                    val existing = fm.findFragmentById(R.id.settings_page)
                    // 旧布局里 FragmentContainerView 在 setContentView 时就存在，旋转/切语言重建后
                    // FragmentManager 能直接把恢复的 Fragment 视图放回去；Compose 的容器要等首帧才创建，
                    // 恢复时找不到容器 → 视图没建出来（设置页整页空白）。
                    // 因此只要恢复出来的 Fragment 没有挂到当前容器上，就重建一次。
                    if (existing == null || existing.view?.parent !== view) {
                        fm.beginTransaction()
                            .replace(R.id.settings_page, com.yjc.click.SettingsFragment())
                            .commit()
                    }
                }
            }
        },
        modifier = modifier,
    )
}

private fun Modifier.settingsSlideOffset(progress: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val offset = (progress * placeable.width / 10f).toInt()
    layout(placeable.width, placeable.height) { placeable.place(offset, 0) }
}

@Composable
private fun TopBar(
    title: String,
    alpha: Float,
    surface: Color,
    barAlpha: Float = 1f,
    retract: Float = 0f,
    layoutRetract: Float = retract,   // 布局高度用的收起量：动画期间保持 0，动画放完才收（避免页面重排）
    flip: Float = 0f,   // 用户：顶栏底栏也要翻
    modifier: Modifier = Modifier,
) {
    // 和底栏一样：收起时必须把高度也压到 0，只做 translationY 会在顶上留一条空档。
    // 但**状态栏那一条要留着**：全压掉的话下面的内容会顶到状态栏里。
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 展开时保持原来的 96dp（顶栏本来就含状态栏那一条，多补会把页面顶下去），
            // 收起时才留下状态栏的高度
            .height((96.dp * (1f - layoutRetract.coerceIn(0f, 1f))).coerceAtLeast(statusInset))
            .clipToBounds(),
    ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            // 有背景图时这里是半透明的（不透明度跟黑色遮罩联动），图从状态栏一路透上来
            .background(surface.copy(alpha = barAlpha))
            // 向上滑出自己的高度（含状态栏），和底栏同时开始、同时结束
            // 用户：两条栏都要「从下面/上面伸缩」，不是从左边缘横扫过去 —— 改成绕水平轴往后倒：
            // 顶栏以顶边为轴、下边缘往后翻（rotationX 负 = 栏体转离观察者），看起来是往屏幕上沿收进去
            .graphicsLayer { translationY = -retract * 96.dp.toPx(); transformOrigin = TransformOrigin(0.5f, 0f); rotationX = -90f * flip }
            .then(modifier),
    ) {
        // 旧布局：96dp 容器内居中一个 MaterialToolbar（64dp，顶部再补状态栏 padding），
        // 实测标题块 bounds = [42,118][167,196]，即距容器顶 118px（44.95dp）
        ClickText(
            text = title,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 44.95.dp).alpha(alpha),
        )
    }
    }
}

@Composable
private fun Int.px() = with(LocalDensity.current) { this@px.toDp() }

@Composable
private fun BottomBar(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    surface: Color,
    translationY: Int,
    barAlpha: Float = 1f,
    retract: Float = 0f,
    layoutRetract: Float = retract,   // 布局高度用的收起量：动画期间保持 0，动画放完才收（避免页面重排）
    flip: Float = 0f,   // 用户：顶栏底栏也要翻
) {
    // 旧版 BottomNavigationView 是带 3dp tonalElevation 的 Material 组件：
    // 实测底栏底色 = 表面色叠加 8% primary（浅色 [241,233,247]、深色 [35,32,43]），
    // 与页面背景（[254,247,255] / [20,18,24]）明显不同，这里按同一公式合成。
    // 有背景图时整条半透明（不透明度跟黑色遮罩联动），图从下面透出来。
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f).compositeOver(surface)
    // 收起时必须把高度也压到 0：只做 translationY 的话，底栏原来占的那条布局位置会留白
    // （打开节点图时画布下方会露出一条底栏高的空档）
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val fullHeight = 80.dp + navInset
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(fullHeight * (1f - layoutRetract.coerceIn(0f, 1f)))
            .clipToBounds(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor.copy(alpha = barAlpha))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(80.dp)
                .offsetY(translationY)
                // 许可页/节点图打开时整条向下滑出自己的高度（含系统导航栏内边距）
                // 用户：底栏要「从下面伸缩」—— 以底边为轴向后倒（rotationX 正 = 栏体转离观察者），
                // 视觉上就是往下沿收进去，而不是原来绕左边缘横扫（那个看起来是从右边过来）
                .graphicsLayer { this.translationY = retract * fullHeight.toPx(); transformOrigin = TransformOrigin(0.5f, 1f); rotationX = 90f * flip },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.Start,
        ) {
            BottomBarItem(
                label = stringResource(R.string.nav_home),
                iconRes = R.drawable.animated_home_icon,
                selected = selectedTab == MainTab.HOME,
                modifier = Modifier.weight(1f).testTag("nav_home"),
                onClick = { onSelectTab(MainTab.HOME) },
            )
            BottomBarItem(
                label = stringResource(R.string.nav_program),
                iconRes = R.drawable.ic_program,
                selected = selectedTab == MainTab.PROGRAM,
                modifier = Modifier.weight(1f).testTag("nav_program"),
                popScale = selectedTab == MainTab.PROGRAM,
                onClick = { onSelectTab(MainTab.PROGRAM) },
            )
            BottomBarItem(
                label = stringResource(R.string.nav_settings),
                iconRes = R.drawable.animated_settings_icon,
                selected = selectedTab == MainTab.SETTINGS,
                modifier = Modifier.weight(1f).testTag("nav_settings"),
                onClick = { onSelectTab(MainTab.SETTINGS) },
            )
        }
    }
}

private fun Modifier.offsetY(px: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, px) }
}

@Composable
private fun BottomBarItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    popScale: Boolean = false,
    onClick: () -> Unit,
) {
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(100, easing = LinearEasing),
        label = "navIndicator",
    )
    // 程序图标：1 → 0.7 → 1.1 → 1（旧 animateProgramIcon，每段 100ms）
    //
    // 不能用 animateFloatAsState(targetValue = if (popScale) 0f else 1f)：keyframes 跑完后
    // TargetBasedAnimation 取的是 target，图标会定格在 scale=0（动画结束图标就没了），
    // 只有切走再切回来（target 变回 1f）才重新出现。这里改成显式 Animatable：
    // 只有"刚选中程序页"那一次播回弹，终值固定 1f。
    val iconScale = remember { Animatable(1f) }
    var programWasSelected by remember { mutableStateOf(popScale) }
    LaunchedEffect(popScale) {
        val justSelected = popScale && !programWasSelected
        programWasSelected = popScale
        if (justSelected) {
            iconScale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 300
                    0.7f at 100
                    1.1f at 200
                    1f at 300
                },
            )
        } else {
            // 切走、以及首帧（旋转恢复时本来就选中程序页）都不播，和旧版一致
            iconScale.snapTo(1f)
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    // 旧版 itemIconTint / itemTextColor 的 SelectedStateList：
    // 图标选中 → colorOnSecondaryContainer、未选 → colorOnSurfaceVariant；
    // 文字选中 → colorOnSurface、未选 → colorOnSurfaceVariant（实测取值一致）
    val iconTint = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(
            MaterialTheme.colorScheme.onSecondaryContainer.toArgb(),
            MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .width(64.dp)
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(indicatorAlpha)
                    .clip(RoundedCornerShape(16.dp))
                    .background(indicatorColor),
            )
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageResource(iconRes)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { view ->
                    view.isSelected = selected
                    view.imageTintList = iconTint
                },
                modifier = Modifier.size(24.dp).scale(if (popScale) iconScale.value else 1f),
            )
        }
        Spacer(modifier = Modifier.height(5.5.dp))
        // 旧版导航标签用的是 M3 labelMedium（12sp、letterSpacing 0.5sp），
        // 直接用 M3 排版而不是 ClickText（后者会清零字间距，实测会窄 2px）
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
