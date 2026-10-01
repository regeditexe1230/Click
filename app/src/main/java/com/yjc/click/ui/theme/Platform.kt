package com.yjc.click.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 与旧实现里的 TextView 等价的文本：
 * - 显式指定字号（旧布局里每个 TextView 都直接设 textSize）
 * - 字间距 0（Compose 的 M3 bodyLarge 带 0.5sp 字间距，不覆盖会与旧渲染不同）
 * - 行高固定 1.455em 且关闭 includeFontPadding，对齐旧版 TextView 的单行盒高
 *   （实测 16sp→62px、14sp→54px；开启 includeFontPadding 会额外叠加字体留白使整行偏高 3px）
 * - 常规字重（旧实现用 Typeface.DEFAULT 覆盖了所有字体，连 textStyle="bold" 也被抹平）
 */
@Composable
fun ClickText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    /** 行高比例覆盖：多行文本块的旧版高度规律不同（字体留白整块只加一次），可按需覆盖 */
    lineHeightRatio: Float = LINE_HEIGHT_RATIO,
) {
    val customFamily = LocalClickFontFamily.current
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        // 行高显式指定：Compose 的 M3 bodyLarge 带 0.5sp 字间距与固定行高，
        // 不覆盖会与旧 TextView 渲染不同；关闭 includeFontPadding 才对得上旧版单行盒高。
        lineHeight = fontSize * lineHeightRatio,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = customFamily ?: FontFamily.Default,
        textAlign = textAlign,
        style = LocalTextStyle.current.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

/**
 * 行高比例：实测对齐旧版 TextView 的单行盒高（16sp→62px、14sp→54px，配合 includeFontPadding=false）。
 * 设 1.455 时设置页五行 bounds 与重构前完全一致（top/height 均相同）。
 * 多行文本块（旧版字体留白整块只加一次）另按需覆盖，见 [ClickText] 的 lineHeightRatio。
 */
private const val LINE_HEIGHT_RATIO = 1.455f

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
