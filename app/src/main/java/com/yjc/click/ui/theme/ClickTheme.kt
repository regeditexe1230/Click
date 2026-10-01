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

/**
 * 该页面是否按"整数像素字号"渲染文本。
 *
 * 旧版首页的 TextView 被 FontManager 遍历覆盖成了 `Typeface.DEFAULT`，该字型在当前系统上的
 * 全角字前进量是整数像素（实测 14sp→37px、12sp→32px）；而设置页位于 Fragment 树，
 * 被覆盖的时机更晚（实测仍是主题默认字体，14sp→36.75px）。为保持与重构前逐像素一致，
 * 首页需要按取整后的字号渲染，设置页保持浮点字号。
 */
val LocalIntegerFontAdvance = staticCompositionLocalOf { false }

@Composable
fun ClickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontFamily: FontFamily? = null,
    integerFontAdvance: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 排版全部使用常规字重：旧实现用 Typeface.DEFAULT 覆盖了所有 TextView 的字体，
    // 连 XML 里 textStyle="bold" 的标题实际渲染也是常规字重，这里保持一致。
    // 字型：旧版 FontManager 会把自定义字体（或默认字体）铺到**所有** TextView 上，
    // 包括顶栏标题、底栏标签、按钮文字，因此 M3 排版也要跟着换，否则换字体会只换一部分。
    val density = LocalDensity.current
    val typography = remember(density, integerFontAdvance, fontFamily) {
        val base = Typography()
            .regularWeights()
            .platformFamily(fontFamily ?: PlatformDefaultFontFamily)
        if (integerFontAdvance) base.integerPixelSizes(density) else base
    }
    CompositionLocalProvider(
        LocalClickFontFamily provides fontFamily,
        LocalIntegerFontAdvance provides integerFontAdvance,
    ) {
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

/** 把 M3 默认排版统一换成旧版 FontManager 实际铺下去的字型（自定义字体或平台默认字体） */
private fun Typography.platformFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.platformFamily(family),
    displayMedium = displayMedium.platformFamily(family),
    displaySmall = displaySmall.platformFamily(family),
    headlineLarge = headlineLarge.platformFamily(family),
    headlineMedium = headlineMedium.platformFamily(family),
    headlineSmall = headlineSmall.platformFamily(family),
    titleLarge = titleLarge.platformFamily(family),
    titleMedium = titleMedium.platformFamily(family),
    titleSmall = titleSmall.platformFamily(family),
    bodyLarge = bodyLarge.platformFamily(family),
    bodyMedium = bodyMedium.platformFamily(family),
    bodySmall = bodySmall.platformFamily(family),
    labelLarge = labelLarge.platformFamily(family),
    labelMedium = labelMedium.platformFamily(family),
    labelSmall = labelSmall.platformFamily(family),
)

private fun TextStyle.platformFamily(family: FontFamily): TextStyle =
    copy(fontFamily = family)

/** 首页：字号按旧版 `getDimensionPixelSize()` 规则取整成整数像素（见 LocalIntegerFontAdvance） */
private fun Typography.integerPixelSizes(density: Density): Typography = copy(
    displayLarge = displayLarge.integerPixelSize(density),
    displayMedium = displayMedium.integerPixelSize(density),
    displaySmall = displaySmall.integerPixelSize(density),
    headlineLarge = headlineLarge.integerPixelSize(density),
    headlineMedium = headlineMedium.integerPixelSize(density),
    headlineSmall = headlineSmall.integerPixelSize(density),
    titleLarge = titleLarge.integerPixelSize(density),
    titleMedium = titleMedium.integerPixelSize(density),
    titleSmall = titleSmall.integerPixelSize(density),
    bodyLarge = bodyLarge.integerPixelSize(density),
    bodyMedium = bodyMedium.integerPixelSize(density),
    bodySmall = bodySmall.integerPixelSize(density),
    labelLarge = labelLarge.integerPixelSize(density),
    labelMedium = labelMedium.integerPixelSize(density),
    labelSmall = labelSmall.integerPixelSize(density),
)

private fun TextStyle.integerPixelSize(density: Density): TextStyle =
    if (fontSize.isSp) copy(fontSize = density.platformFontSize(fontSize)) else this
