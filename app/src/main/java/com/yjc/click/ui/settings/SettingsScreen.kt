package com.yjc.click.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickColor
import com.yjc.click.ui.theme.ClickText
import com.yjc.click.ui.theme.PlatformEasing
import com.yjc.click.ui.theme.LocalSectionBackground
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator

private val ContainerShape = RoundedCornerShape(14.dp)
private val RowTopShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val RowBottomShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
private val RowMiddleShape = RoundedCornerShape(0.dp)

/** 与旧实现一致：展开 400ms OvershootInterpolator(0.6f)，收起 300ms AccelerateInterpolator(2f) */
private val ExpandEasing = PlatformEasing(OvershootInterpolator(0.6f))
private val CollapseEasing = PlatformEasing(AccelerateInterpolator(2f))

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    languageValue: String,
    fontValue: String,
    theme: String,
    colorSchemeExpanded: Boolean,
    colorExpanded: Boolean,
    backgroundExpanded: Boolean,
    dynamicColorChecked: Boolean,
    colorKey: String,
    onLanguageClick: () -> Unit,
    onFontClick: () -> Unit,
    onThemeSelected: (String, Offset) -> Unit,
    onColorSchemeHeaderClick: () -> Unit,
    onColorHeaderClick: () -> Unit,
    onBackgroundHeaderClick: () -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 把 Compose 的 testTag 暴露成 accessibility 的 resource-id，
            // 这样基于 uiautomator 的回归截图脚本（按 id 定位控件）无需改动即可继续工作
            .semantics { testTagsAsResourceId = true }
            .verticalScroll(rememberScrollState()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {

            // ---------------- 文字 ----------------
            SectionTitle(R.string.text_section)
            SettingsCard {
                SettingsRow(
                    iconRes = R.drawable.ic_language,
                    titleRes = R.string.language,
                    descRes = R.string.change_app_language,
                    value = languageValue,
                    shape = RowTopShape,
                    testTag = "settings_language",
                    onClick = onLanguageClick,
                )
                Gap2dp()
                SettingsRow(
                    iconRes = R.drawable.ic_font,
                    titleRes = R.string.font,
                    descRes = R.string.change_app_font,
                    value = fontValue,
                    shape = RowBottomShape,
                    testTag = "settings_font",
                    onClick = onFontClick,
                )
            }

            // ---------------- 个性化 ----------------
            SectionTitle(R.string.personalization_section)
            SettingsCard {
                // 配色方案
                SettingsRow(
                    iconRes = R.drawable.ic_color_scheme,
                    titleRes = R.string.color_scheme,
                    descRes = R.string.color_scheme_desc,
                    value = themeDisplayName(theme),
                    shape = RowTopShape,
                    testTag = "settings_color_scheme_header",
                    onClick = onColorSchemeHeaderClick,
                )
                AnimatedVisibility(
                    visible = colorSchemeExpanded,
                    enter = expandVertically(animationSpec = tween(400, easing = ExpandEasing)),
                    exit = shrinkVertically(animationSpec = tween(300, easing = CollapseEasing)),
                ) {
                    ColorSchemeOptions(theme = theme, onThemeSelected = onThemeSelected)
                }
                Gap2dp()

                // 颜色
                SettingsRow(
                    iconRes = R.drawable.ic_color,
                    titleRes = R.string.color,
                    descRes = R.string.color_desc,
                    value = "",
                    shape = RowMiddleShape,
                    testTag = "settings_color_header",
                    onClick = onColorHeaderClick,
                )
                AnimatedVisibility(
                    visible = colorExpanded,
                    enter = expandVertically(animationSpec = tween(400, easing = ExpandEasing)),
                    exit = shrinkVertically(animationSpec = tween(300, easing = CollapseEasing)),
                ) {
                    ColorOptions(
                        checked = dynamicColorChecked,
                        colorKey = colorKey,
                        onCheckedChange = onDynamicColorChange,
                        onColorSelected = onColorSelected,
                    )
                }
                Gap2dp()

                // 应用背景：展开时头部由"底部圆角"变直角，选项区补上底部圆角（与旧实现一致）
                SettingsRow(
                    iconRes = R.drawable.ic_background,
                    titleRes = R.string.app_background,
                    descRes = R.string.app_background_desc,
                    value = stringResource(R.string.default_value),
                    shape = if (backgroundExpanded) RowMiddleShape else RowBottomShape,
                    testTag = "settings_background_header",
                    onClick = onBackgroundHeaderClick,
                )
                AnimatedVisibility(
                    visible = backgroundExpanded,
                    enter = expandVertically(animationSpec = tween(400, easing = ExpandEasing)),
                    exit = shrinkVertically(animationSpec = tween(300, easing = CollapseEasing)),
                ) {
                    FlatOptions(shape = RowBottomShape, horizontalPadding = 12.dp) { }
                }
            }
        }
    }
}

@Composable
private fun themeDisplayName(theme: String): String = when (theme) {
    "light" -> stringResource(R.string.light_theme)
    "dark" -> stringResource(R.string.dark_theme)
    else -> stringResource(R.string.follow_system)
}

@Composable
private fun SectionTitle(resId: Int) {
    ClickText(
        text = stringResource(resId),
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
    )
}

/** 圆角容器：背景透明，仅做 14dp 裁剪，真正的底色由每一行的背景提供 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(ContainerShape),
        content = content,
    )
}

/** 行与行之间 2dp 的页面背景色分隔（旧布局里是 height=2dp 的 View） */
@Composable
private fun Gap2dp() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.background),
    )
}

/** 1dp 分隔线，颜色 colorOutlineVariant（旧布局里的 View） */
@Composable
private fun InnerDivider(
    topMargin: Int = 0,
    bottomMargin: Int = 0,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topMargin.dp, bottom = bottomMargin.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun SettingsRow(
    iconRes: Int,
    titleRes: Int,
    descRes: Int,
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
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                MaterialTheme.colorScheme.onSurfaceVariant
            ),
        )
        Spacer(modifier = Modifier.width(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            ClickText(stringResource(titleRes), 16.sp, MaterialTheme.colorScheme.onSurface)
            ClickText(
                text = stringResource(descRes),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ClickText(value, 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 可展开区域的外壳：settings_item_flat 背景 */
@Composable
private fun FlatOptions(
    shape: Shape,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LocalSectionBackground.current)
            .padding(start = horizontalPadding, end = horizontalPadding, bottom = 14.dp),
        content = content,
    )
}

@Composable
private fun ColorSchemeOptions(theme: String, onThemeSelected: (String, Offset) -> Unit) {
    FlatOptions(shape = RowMiddleShape, horizontalPadding = 12.dp) {
        InnerDivider(bottomMargin = 12)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            ThemeOption(R.drawable.theme_preview_light, R.string.light_theme, theme == "light") { pos ->
                onThemeSelected("light", pos)
            }
            ThemeOption(R.drawable.theme_preview_dark, R.string.dark_theme, theme == "dark") { pos ->
                onThemeSelected("dark", pos)
            }
            ThemeOption(
                R.drawable.theme_preview_system,
                R.string.follow_system,
                theme == "follow_system",
            ) { pos ->
                onThemeSelected("follow_system", pos)
            }
        }
    }
}

@Composable
private fun RowScope.ThemeOption(
    previewRes: Int,
    labelRes: Int,
    selected: Boolean,
    onSelect: (Offset) -> Unit,
) {
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier = Modifier
            .weight(1f)
            .onGloballyPositioned { originInWindow = it.positionInWindow() }
            // 用 pointerInput 而非 clickable：需要拿到手指按下的具体位置，作为圆形揭示的圆心
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSelect(originInWindow + offset) }
            }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val painter = painterResource(previewRes)
        val intrinsic = painter.intrinsicSize
        val ratio = if (intrinsic.width > 0f && intrinsic.height > 0f) {
            intrinsic.width / intrinsic.height
        } else {
            1f
        }
        Image(
            painter = painter,
            contentDescription = stringResource(labelRes),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            ClickText(
                text = stringResource(labelRes),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun ColorOptions(
    checked: Boolean,
    colorKey: String,
    onCheckedChange: (Boolean) -> Unit,
    onColorSelected: (String) -> Unit,
) {
    FlatOptions(shape = RowMiddleShape, horizontalPadding = 22.dp) {
        InnerDivider(topMargin = 8, bottomMargin = 8)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_dynamic_color),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    MaterialTheme.colorScheme.onSurfaceVariant
                ),
            )
            Spacer(modifier = Modifier.width(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                ClickText(
                    stringResource(R.string.dynamic_color),
                    16.sp,
                    MaterialTheme.colorScheme.onSurface,
                )
                ClickText(
                    text = stringResource(R.string.dynamic_color_desc),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        // 动态取色打开时这排颜色直接收起来（配色由壁纸决定）
        AnimatedVisibility(
            visible = !checked,
            enter = expandVertically(animationSpec = tween(400, easing = ExpandEasing)),
            exit = shrinkVertically(animationSpec = tween(300, easing = CollapseEasing)),
        ) {
            // 收起/展开动画还没停稳时不接受点击（transition 是动画的真实状态）
            val animating = transition.currentState != transition.targetState
            ColorChoices(
                selected = colorKey,
                enabled = !animating,
                onSelect = onColorSelected,
            )
        }
    }
}

/** 一排颜色圆圈：本色圆点，选中的那个外面加一圈描边；[enabled] 为 false 时不响应点击 */
@Composable
private fun ColorChoices(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClickColor.entries.forEach { color ->
            val isSelected = color.key == selected
            val name = stringResource(color.labelRes)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable(enabled = enabled) { onSelect(color.key) }
                    .testTag("settings_color_${color.key}")
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color.swatch)),
                )
            }
            if (color != ClickColor.entries.last()) Spacer(modifier = Modifier.width(12.dp))
        }
    }
}
