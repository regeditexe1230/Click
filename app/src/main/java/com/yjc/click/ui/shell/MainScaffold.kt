package com.yjc.click.ui.shell

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yjc.click.AppBackground
import com.yjc.click.ImageLoader
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickText
import com.yjc.click.ui.theme.PlatformEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 底部导航项（与旧 menu/bottom_nav_menu.xml 一致） */
enum class MainTab { HOME, PROGRAM, SETTINGS }

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
            titleAlpha.animateTo(1f, tween(162, easing = LinearEasing))
        }
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

    // 应用背景：选中预设里的图 + 两个透明度（整屏铺满，顶栏/底栏压在上面）
    val backgroundPath = AppBackground.imagePath
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val backgroundImage = remember(backgroundPath, screenW, screenH) {
        backgroundPath?.let { ImageLoader.load(it, screenW, screenH) }
    }
    // 顶栏/底栏底色不透明度：有背景图时跟着黑色遮罩联动（遮罩越重，栏越不透明、字越清楚）
    val barAlpha = if (backgroundPath == null) 1f else 0.6f + 0.4f * (AppBackground.scrimAlpha / 100f)

    Box(modifier = Modifier.fillMaxSize().background(surface)) {
        if (backgroundImage != null) {
            Image(
                bitmap = backgroundImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = AppBackground.imageAlpha / 100f,
                modifier = Modifier.fillMaxSize(),
            )
            if (AppBackground.scrimAlpha > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = AppBackground.scrimAlpha / 100f)),
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
                title = displayedTitle,
                alpha = titleAlpha.value,
                surface = surface,
                barAlpha = barAlpha,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        MainTab.PROGRAM -> Box(Modifier.fillMaxSize()) { programContent() }
                        // 设置页由常驻的 Fragment 宿主呈现（避免重复创建 Fragment）
                        MainTab.SETTINGS -> Box(Modifier.fillMaxSize())
                    }
                }
            }

            BottomBar(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                surface = surface,
                barAlpha = barAlpha,
                translationY = with(LocalDensity.current) { (entrance.value * 80.dp.toPx()).toInt() },
            )
        }
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
private fun TopBar(title: String, alpha: Float, surface: Color, barAlpha: Float = 1f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            // 有背景图时这里是半透明的（不透明度跟黑色遮罩联动），图从状态栏一路透上来
            .background(surface.copy(alpha = barAlpha)),
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

@Composable
private fun Int.px() = with(LocalDensity.current) { this@px.toDp() }

@Composable
private fun BottomBar(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    surface: Color,
    translationY: Int,
    barAlpha: Float = 1f,
) {
    // 旧版 BottomNavigationView 是带 3dp tonalElevation 的 Material 组件：
    // 实测底栏底色 = 表面色叠加 8% primary（浅色 [241,233,247]、深色 [35,32,43]），
    // 与页面背景（[254,247,255] / [20,18,24]）明显不同，这里按同一公式合成。
    // 有背景图时整条半透明（不透明度跟黑色遮罩联动），图从下面透出来。
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f).compositeOver(surface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor.copy(alpha = barAlpha))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(80.dp)
            .offsetY(translationY),
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
