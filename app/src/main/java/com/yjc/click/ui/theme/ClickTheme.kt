package com.yjc.click.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density

/**
 * Compose 侧的主题，取值与 res/values/colors.xml、res/values-night/colors.xml 一一对应，
 * 保证重构过程中配色不发生任何变化。
 */
private val ClickLightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

private val ClickDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
)

/** 设置项行背景（values 与 values-night 都是 #206750A4，即 12.5% 透明度的紫色） */
val SectionBackground = Color(0x206750A4)

/**
 * 自定义字体（设置页"字体"选中的 ttf/otf）。
 *
 * 旧实现是遍历 View 树给每个 TextView 设 typeface，这对 Compose 文本完全无效，
 * 因此改为从主题层下发 FontFamily；为 null 时使用与旧实现等价的默认字体。
 */
val LocalClickFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ClickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    // 排版全部使用常规字重：旧实现用 Typeface.DEFAULT 覆盖了所有 TextView 的字体，
    // 连 XML 里 textStyle="bold" 的标题实际渲染也是常规字重，这里保持一致。
    // 字号同时按旧版规则取整成整数像素（见 Platform.kt 的 platformFontSize）。
    val density = LocalDensity.current
    val typography = remember(density) {
        Typography().regularWeights().platformSizes(density)
    }
    CompositionLocalProvider(LocalClickFontFamily provides fontFamily) {
        MaterialTheme(
            colorScheme = if (darkTheme) ClickDarkColors else ClickLightColors,
            typography = typography,
            content = content,
        )
    }
}

private fun TextStyle.regularWeight() =
    copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)

private fun Typography.regularWeights(): Typography = copy(
    displayLarge = displayLarge.regularWeight(),
    displayMedium = displayMedium.regularWeight(),
    displaySmall = displaySmall.regularWeight(),
    headlineLarge = headlineLarge.regularWeight(),
    headlineMedium = headlineMedium.regularWeight(),
    headlineSmall = headlineSmall.regularWeight(),
    titleLarge = titleLarge.regularWeight(),
    titleMedium = titleMedium.regularWeight(),
    titleSmall = titleSmall.regularWeight(),
    bodyLarge = bodyLarge.regularWeight(),
    bodyMedium = bodyMedium.regularWeight(),
    bodySmall = bodySmall.regularWeight(),
    labelLarge = labelLarge.regularWeight(),
    labelMedium = labelMedium.regularWeight(),
    labelSmall = labelSmall.regularWeight(),
)

/** 把 M3 默认排版的每个字号都按旧版 getDimensionPixelSize() 规则取整成整数像素 */
private fun Typography.platformSizes(density: Density): Typography = copy(
    displayLarge = displayLarge.platformSize(density),
    displayMedium = displayMedium.platformSize(density),
    displaySmall = displaySmall.platformSize(density),
    headlineLarge = headlineLarge.platformSize(density),
    headlineMedium = headlineMedium.platformSize(density),
    headlineSmall = headlineSmall.platformSize(density),
    titleLarge = titleLarge.platformSize(density),
    titleMedium = titleMedium.platformSize(density),
    titleSmall = titleSmall.platformSize(density),
    bodyLarge = bodyLarge.platformSize(density),
    bodyMedium = bodyMedium.platformSize(density),
    bodySmall = bodySmall.platformSize(density),
    labelLarge = labelLarge.platformSize(density),
    labelMedium = labelMedium.platformSize(density),
    labelSmall = labelSmall.platformSize(density),
)

private fun TextStyle.platformSize(density: Density): TextStyle =
    if (fontSize.isSp) copy(fontSize = density.platformFontSize(fontSize)) else this
