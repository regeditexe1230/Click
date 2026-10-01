package com.yjc.click.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 平台字体 (ascent + descent) / em，用于推算旧版 TextView 的行盒高度。
 * 取值来自旧版实测（12sp→48px、14sp→54px、15sp→56px，配合下方 4dp 行距）。
 */
private const val FONT_LINE_RATIO = 1.1718f

/** 旧版主题给每个 TextView 附加的 lineSpacingExtra（4dp，实测 10.5px @ density 2.625） */
private val LINE_SPACING_EXTRA = 4.dp

/**
 * 旧版 TextView 的 android:textSize 是通过 getDimensionPixelSize() 读入的，
 * 会把 sp 四舍五入成**整数像素**（14sp→37px、12sp→32px）；Compose 使用精确浮点值（36.75/31.5px），
 * 全角汉字前进量恰好是 1em，于是每个汉字都比旧版少 1px 并逐字累积，整行文字越到后面越偏。
 * 这里按旧版规则取整后再转回 sp，保证字宽与前进量完全一致。
 */
fun Density.platformFontSize(fontSize: TextUnit): TextUnit =
    fontSize.toPx().roundToInt().toSp()

/**
 * 旧版单行行盒高度 = 字体度量 (ascent+descent) + lineSpacingExtra，并取整（实测 12sp→48px、
 * 14sp→54px、15sp→56px，多行按每行累加）。Compose 对设置的 lineHeight 向上取整，
 * 因此减去一个极小量，确保落回旧版的整数值。
 */
fun Density.platformLineHeight(fontSize: TextUnit): TextUnit {
    // 字号同样按旧版规则取整后再推算行盒（旧版行盒是按取整后的字号算出来的）
    val px = fontSize.toPx().roundToInt()
    val target = FONT_LINE_RATIO * px + LINE_SPACING_EXTRA.toPx()
    return (target.roundToInt() - 0.01f).toSp()
}

/**
 * 与旧实现里的 TextView 等价的文本：
 * - 显式指定字号（旧布局里每个 TextView 都直接设 textSize），并按旧版规则取整成整数像素
 * - 字间距 0（Compose 的 M3 bodyLarge 带 0.5sp 字间距，不覆盖会与旧渲染不同）
 * - 行高按旧版字体度量 + 4dp 行距推算，关闭 includeFontPadding
 * - 常规字重（旧实现用 Typeface.DEFAULT 覆盖了所有字体，连 textStyle="bold" 也被抹平）
 */
@Composable
fun ClickText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val customFamily = LocalClickFontFamily.current
    val density = LocalDensity.current
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = with(density) { density.platformFontSize(fontSize) },
        lineHeight = TextUnit.Unspecified,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = customFamily ?: FontFamily.Default,
        textAlign = textAlign,
        style = LocalTextStyle.current.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        ),
    )
}

/**
 * 把 Android 原生 Interpolator 直接当 Compose Easing 使用。
 *
 * 旧实现的展开/收起动画用的是 OvershootInterpolator(0.6f) / AccelerateInterpolator(2f)
 * 这类平台插值器，Compose 没有等价实现，直接桥接可以保证动画曲线逐帧一致。
 */
class PlatformEasing(
    private val interpolator: android.view.animation.Interpolator,
) : Easing {
    override fun transform(fraction: Float): Float = interpolator.getInterpolation(fraction)
}
