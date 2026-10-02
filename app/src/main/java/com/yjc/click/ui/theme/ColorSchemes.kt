package com.yjc.click.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import com.yjc.click.R

/**
 * 固定配色下可选的几种颜色。
 * [seed] 为 null 表示沿用重构前的固定紫色板（逐像素不变），其余用 Material 算法从种子色生成。
 * [overlayStyle] 是给 View 侧（弹窗/悬浮窗）用的 theme overlay，0 表示不需要。
 */
enum class ClickColor(val key: String, val seed: Int?, val overlayStyle: Int, val labelRes: Int) {
    PURPLE("purple", null, 0, R.string.color_purple),
    BLUE("blue", 0xFF2196F3.toInt(), R.style.ThemeOverlay_Click_Color_Blue, R.string.color_blue),
    RED("red", 0xFFF44336.toInt(), R.style.ThemeOverlay_Click_Color_Red, R.string.color_red),
    YELLOW("yellow", 0xFFFFC400.toInt(), R.style.ThemeOverlay_Click_Color_Yellow, R.string.color_yellow),
    GREEN("green", 0xFF4CAF50.toInt(), R.style.ThemeOverlay_Click_Color_Green, R.string.color_green);

    /** 颜色圆圈里显示的颜色：用鲜艳的种子色，暗色下的 primary 是浅色调，当"色卡"不直观 */
    val swatch: Int get() = seed ?: 0xFF6750A4.toInt()

    companion object {
        fun fromKey(key: String?): ClickColor = entries.firstOrNull { it.key == key } ?: PURPLE
    }
}

/** 当前该用哪套配色：动态取色（12+）> 自选颜色 > 默认紫的旧固定色板 */
fun schemeFor(context: Context, color: ClickColor, dark: Boolean, dynamicColor: Boolean): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    color.seed == null -> if (dark) ClickDarkColors else ClickLightColors
    else -> seededScheme(color.seed, dark)
}

/** 角色名 → ARGB，供 Compose 配色和 XML 资源生成共用（键名与 values/colors_theme_*.xml 对应） */
fun seededRoles(seed: Int, dark: Boolean): Map<String, Int> {
    val hct = Hct.fromInt(seed)
    val primary = TonalPalette.fromHueAndChroma(hct.hue, 36.0)
    val secondary = TonalPalette.fromHueAndChroma(hct.hue, 16.0)
    val tertiary = TonalPalette.fromHueAndChroma(hct.hue + 60.0, 24.0)
    val neutral = TonalPalette.fromHueAndChroma(hct.hue, 6.0)
    val variant = TonalPalette.fromHueAndChroma(hct.hue, 8.0)

    // 括号里是 (浅色色调, 深色色调)，取自 M3 规范的角色-色调映射
    fun p(palette: TonalPalette, light: Int, darkTone: Int) = palette.tone(if (dark) darkTone else light)

    return mapOf(
        "primary" to p(primary, 40, 80),
        "onPrimary" to p(primary, 100, 20),
        "primaryContainer" to p(primary, 90, 30),
        "onPrimaryContainer" to p(primary, 10, 90),
        "secondary" to p(secondary, 40, 80),
        "onSecondary" to p(secondary, 100, 20),
        "secondaryContainer" to p(secondary, 90, 30),
        "onSecondaryContainer" to p(secondary, 10, 90),
        "tertiary" to p(tertiary, 40, 80),
        "onTertiary" to p(tertiary, 100, 20),
        "tertiaryContainer" to p(tertiary, 90, 30),
        "onTertiaryContainer" to p(tertiary, 10, 90),
        "background" to p(neutral, 99, 10),
        "onBackground" to p(neutral, 10, 90),
        "surface" to p(neutral, 99, 10),
        "onSurface" to p(neutral, 10, 90),
        "surfaceVariant" to p(variant, 90, 30),
        "onSurfaceVariant" to p(variant, 30, 80),
        "outline" to p(variant, 50, 60),
        "outlineVariant" to p(variant, 80, 30),
        // M3 的 surface container 系列（弹窗背景等用得到）
        "surfaceContainerLowest" to p(neutral, 100, 4),
        "surfaceContainerLow" to p(neutral, 96, 10),
        "surfaceContainer" to p(neutral, 94, 12),
        "surfaceContainerHigh" to p(neutral, 92, 17),
        "surfaceContainerHighest" to p(neutral, 90, 22),
        "surfaceDim" to p(neutral, 87, 6),
        "surfaceBright" to p(neutral, 98, 24),
    )
}

/** 用 Material 官方算法（HCT 色调板）从种子色生成整套 M3 配色 */
fun seededScheme(seed: Int, dark: Boolean): ColorScheme {
    val r = seededRoles(seed, dark)
    fun c(name: String) = Color(r.getValue(name))
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c("primary"), onPrimary = c("onPrimary"),
        primaryContainer = c("primaryContainer"), onPrimaryContainer = c("onPrimaryContainer"),
        secondary = c("secondary"), onSecondary = c("onSecondary"),
        secondaryContainer = c("secondaryContainer"), onSecondaryContainer = c("onSecondaryContainer"),
        tertiary = c("tertiary"), onTertiary = c("onTertiary"),
        tertiaryContainer = c("tertiaryContainer"), onTertiaryContainer = c("onTertiaryContainer"),
        background = c("background"), onBackground = c("onBackground"),
        surface = c("surface"), onSurface = c("onSurface"),
        surfaceVariant = c("surfaceVariant"), onSurfaceVariant = c("onSurfaceVariant"),
        outline = c("outline"), outlineVariant = c("outlineVariant"),
        surfaceContainerLowest = c("surfaceContainerLowest"),
        surfaceContainerLow = c("surfaceContainerLow"),
        surfaceContainer = c("surfaceContainer"),
        surfaceContainerHigh = c("surfaceContainerHigh"),
        surfaceContainerHighest = c("surfaceContainerHighest"),
        surfaceDim = c("surfaceDim"),
        surfaceBright = c("surfaceBright"),
    )
}
