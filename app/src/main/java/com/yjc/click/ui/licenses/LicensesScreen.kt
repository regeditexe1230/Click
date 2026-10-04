package com.yjc.click.ui.licenses

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickText
import com.yjc.click.ui.theme.LocalSectionBackground
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ±10% 滑入滑出用的曲线，和外壳的过渡（以及旧布局那一版）保持一致。 */
private val ExpandEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 行形状与行间距，逐个照搬设置页（SettingsScreen 的 RowTopShape / RowMiddleShape /
 * RowBottomShape / Gap2dp）：一组里首行只圆上角、末行只圆下角、中间行直角，
 * 行与行之间垫 2dp 的页面底色；只有一行的组四个角都圆。
 */
private val RowTopShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val RowBottomShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
private val RowMiddleShape = RoundedCornerShape(0.dp)
private val RowSingleShape = RoundedCornerShape(12.dp)

/** 组与组之间：设置页是卡片自己的 padding(bottom = 16.dp)，这里用同样高的间隔项。 */
private val GroupGap = 16.dp

/**
 * 「开放源代码许可」整页的开关 + 正在看的组件。
 *
 * 没有导航库，所以用进程级状态 + 外壳（MainScaffold）最外层 Box 里的整屏覆盖层实现：
 * 设置页那一行点一下 = [open]（列表页），列表里点一项 = [openDetail]（协议全文页），
 * 返回箭头/系统返回逐层退：详情 → 列表 → 设置页。
 */
object LicensesPage {
    var visible by mutableStateOf(false)
        private set

    /** 非空 = 正在看这个组件的协议全文。 */
    var detail by mutableStateOf<LicenseLibrary?>(null)
        private set

    fun open() {
        detail = null
        visible = true
    }

    fun close() {
        visible = false
        detail = null
    }

    fun openDetail(library: LicenseLibrary) {
        detail = library
    }

    fun closeDetail() {
        detail = null
    }
}

/**
 * 整页内容：列表页（顶栏 + 本应用与第三方组件清单）+ 协议全文页。
 *
 * 列表和详情之间也用外壳那套 expand 过渡（列表向左退出 10%、详情从右 10% 进入，360ms），
 * 顶栏不动，只有内容在滑动——和从设置页进这一页的感觉一致。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LicensesPageContent(onBack: () -> Unit) {
    val detail = LicensesPage.detail
    val appName = stringResource(R.string.app_name)
    val appAuthor = stringResource(R.string.app_developer)
    // 退出动画期间 detail 已经变回 null，用最近一次非空的记录撑住内容
    var shownDetail by remember { mutableStateOf<LicenseLibrary?>(null) }
    if (detail != null) shownDetail = detail

    val listSlide = remember { Animatable(if (detail == null) 0f else 1f) }
    val listAlpha = remember { Animatable(if (detail == null) 1f else 0f) }

    LaunchedEffect(detail != null) {
        if (detail != null) {
            launch { listSlide.animateTo(1f, tween(360, easing = ExpandEasing)) }
            listAlpha.animateTo(0f, tween(126, easing = LinearEasing))
        } else {
            launch { listSlide.animateTo(0f, tween(360, easing = ExpandEasing)) }
            delay(126)
            listAlpha.animateTo(1f, tween(162, easing = LinearEasing))
        }
    }

    // 系统返回逐层退：详情 → 列表 → 设置页
    BackHandler {
        if (detail != null) LicensesPage.closeDetail() else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { testTagsAsResourceId = true },
    ) {
        // 列表页：详情打开时整体左移 10% 并淡出
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .graphicsLayer {
                    translationX = -0.1f * size.width * listSlide.value
                    alpha = listAlpha.value
                },
        ) {
            LicensesTopBar(
                title = stringResource(R.string.about_licenses),
                onBack = onBack,
                backTag = "licenses_back",
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            ) {
                item {
                    SectionTitle(stringResource(R.string.licenses_own))
                }
                item {
                    LicenseRow(
                        iconRes = R.drawable.ic_info,
                        title = appName,
                        desc = appAuthor,
                        value = LicenseId.GPL_3_0.label,
                        shape = RowSingleShape,
                        testTag = "licenses_row_app",
                        onClick = {
                            LicensesPage.openDetail(
                                LicenseLibrary(appName, appAuthor, LicenseId.GPL_3_0),
                            )
                        },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(GroupGap))
                }
                item {
                    SectionTitle(stringResource(R.string.licenses_third_party))
                }
                itemsIndexed(thirdPartyLibraries, key = { _, library -> library.name }) { index, library ->
                    if (index > 0) {
                        Gap2dp()
                    }
                    LicenseRow(
                        iconRes = R.drawable.ic_license,
                        title = library.name,
                        desc = library.author,
                        value = library.license.label,
                        shape = when (index) {
                            0 -> RowTopShape
                            thirdPartyLibraries.lastIndex -> RowBottomShape
                            else -> RowMiddleShape
                        },
                        testTag = "license_row_$index",
                        onClick = { LicensesPage.openDetail(library) },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(GroupGap))
                }
            }
        }

        // 协议全文页：从右侧 10% 滑入并淡入
        AnimatedVisibility(
            visible = detail != null,
            enter = slideInHorizontally(tween(360, easing = ExpandEasing)) { (0.1f * it).roundToInt() } +
                fadeIn(tween(162, delayMillis = 126, easing = LinearEasing)),
            exit = slideOutHorizontally(tween(360, easing = ExpandEasing)) { (0.1f * it).roundToInt() } +
                fadeOut(tween(126, easing = LinearEasing)),
        ) {
            shownDetail?.let { library ->
                LicenseDetailPage(library = library, onBack = { LicensesPage.closeDetail() })
            }
        }
    }
}

/**
 * 和外壳顶栏（MainScaffold 的 TopBar）逐字同一套摆放：96dp 容器、标题 22sp 且距容器顶
 * 44.95dp（1080 宽模拟器上实测 118px）。左边多一个 36dp 的返回箭头盒，它比 22sp 的行盒
 * （约 32dp）高 4dp，所以箭头自己往上抬 2dp，正好和标题那一行居中对齐。
 */
@Composable
private fun LicensesTopBar(title: String, onBack: () -> Unit, backTag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        ClickText(
            text = title,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 50.dp, top = 44.95.dp),
        )
        Box(
            modifier = Modifier
                .padding(start = 8.dp, top = 42.95.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .testTag(backTag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 某个组件的协议全文：顶栏标题用协议名（组件名太长，22sp 放不下），组件名放在下面一行。 */
@Composable
private fun LicenseDetailPage(library: LicenseLibrary, onBack: () -> Unit) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(library.license) {
        LicenseTexts.of(context, library.license).ifEmpty { library.license.label }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            // 空白处的触摸吃掉，别漏到底下的列表上（顶栏/列表都还在组合中）
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        LicensesTopBar(
            title = library.license.label,
            onBack = onBack,
            backTag = "license_detail_back",
        )

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ClickText(
                text = library.name,
                fontSize = 16.sp,
                color = onSurface,
            )
            ClickText(
                text = "${library.author} · ${library.license.label}",
                fontSize = 12.sp,
                color = onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            ClickText(
                text = text,
                fontSize = 13.sp,
                color = onSurfaceVariant,
            )
        }
    }
}

/** 分组标题：与设置页 SectionTitle 同一套（14sp 主色、左缩进 14dp、下留 10dp）。 */
@Composable
private fun SectionTitle(text: String) {
    ClickText(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, bottom = 10.dp),
    )
}

/** 行与行之间 2dp 的页面底色分隔，和设置页的 Gap2dp 一模一样。 */
@Composable
private fun Gap2dp() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.background),
    )
}

/**
 * 组件行：完全照设置页 SettingsRow 的样式——24dp 图标（onSurfaceVariant 着色）、
 * 22dp 间距、标题 16sp、说明 14sp、右侧一列 14sp 的浅色值（这里放协议名）。
 */
@Composable
private fun LicenseRow(
    iconRes: Int,
    title: String,
    desc: String,
    value: String,
    shape: Shape,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LocalSectionBackground.current)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(modifier = Modifier.width(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            ClickText(
                text = title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ClickText(
                text = desc,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ClickText(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
