package com.yjc.click.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
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

/**
 * 旧版单行盒高实测标定表（取整后的 px 字号 → 盒高 px）。
 *
 * 平台侧 TextView 的盒高并不严格随字号线性：实测 12sp(32px)→48、13sp(34px)→50、
 * 14sp(37px)→54、15sp(39px)→56、16sp(42px)→62，其中 42px 比线性外推高 2px，
 * 因此已实测的字号直接取标定值，表外字号退回下面的线性模型。
 */
private val CALIBRATED_LINE_BOX_PX = mapOf(32 to 48, 34 to 50, 37 to 54, 39 to 56, 42 to 62)

/** 旧版主题给每个 TextView 附加的 lineSpacingExtra（4dp，实测 10.5px @ density 2.625） */
private val LINE_SPACING_EXTRA = 4.dp

/**
 * 旧实现用 `Typeface.DEFAULT` 覆盖了所有可达 TextView 的字体（见 FontManager.applyFont），
 * 而 Compose 的 FontFamily.Default 解析到的是主题默认字体，两者在 API 34+ 上不是同一个字形实例
 * （实测同一段 14sp 文字的前进量分别是 37px 与 36px）。这里统一用平台默认字型，
 * 与旧版被 FontManager 覆盖过的 TextView 保持一致。
 */
val PlatformDefaultFontFamily: FontFamily = FontFamily(android.graphics.Typeface.DEFAULT)

/**
 * 按旧版 `getDimensionPixelSize()` 的规则把字号取整成整数像素（见 [LocalIntegerFontAdvance]）。
 * 14sp→37px、12sp→32px。
 */
fun Density.platformFontSize(fontSize: TextUnit): TextUnit =
    fontSize.toPx().roundToInt().toSp()

/**
 * 旧版单行行盒高度（px 整数）：优先取实测标定值，否则按 字体度量(ascent+descent) + lineSpacingExtra 推算。
 */
fun Density.platformLineBoxPx(fontSize: TextUnit): Int {
    val px = fontSize.toPx().roundToInt()
    return CALIBRATED_LINE_BOX_PX[px]
        ?: (FONT_LINE_RATIO * px + LINE_SPACING_EXTRA.toPx()).roundToInt()
}

/**
 * 旧版单行行盒高度：优先取实测标定值，否则按 字体度量(ascent+descent) + lineSpacingExtra 线性推算，
 * 均取整（多行按每行累加）。Compose 对设置的 lineHeight 向上取整，
 * 因此减去一个极小量，确保落回旧版的整数值。
 */
fun Density.platformLineHeight(fontSize: TextUnit): TextUnit =
    (platformLineBoxPx(fontSize) - 0.01f).toSp()

/**
 * 把文本块的布局高度对齐成 旧版行盒 × 行数。
 *
 * Compose 的多行段落高度会比 行盒×行数 多出约 1px（实测 2 行时 112.98 → 布局高度 113），
 * 而旧版 TextView 高度正好是 行盒×行数（112）。多出的 1px 会把下方所有元素顶下去，
 * 因此这里按行盒取整修正布局高度（文字本身不裁切，绘制位置不变）。
 */
private fun Modifier.platformBlockHeight(lineBoxPx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lines = if (lineBoxPx <= 0) 1
    else (placeable.height.toFloat() / lineBoxPx).roundToInt().coerceAtLeast(1)
    layout(placeable.width, lines * lineBoxPx) {
        placeable.place(0, 0)
    }
}

/**
 * 与旧实现里的 TextView 等价的文本：
 * - 显式指定字号（旧布局里每个 TextView 都直接设 textSize）
 * - 字间距 0（Compose 的 M3 bodyLarge 带 0.5sp 字间距，不覆盖会与旧渲染不同）
 * - 行高按旧版字体度量 + 4dp 行距推算，关闭 includeFontPadding
 * - 常规字重（旧实现用 Typeface.DEFAULT 覆盖了所有字体，连 textStyle="bold" 也被抹平）
 * - 使用平台默认字型（旧版被 FontManager 覆盖后的字型）
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
    val lineBoxPx = with(density) { density.platformLineBoxPx(fontSize) }
    // 旧版首页（Activity 树，字体被 FontManager 换成 Typeface.DEFAULT）全角字前进量是整数像素：
    // 14sp 实测 37px；而设置页 Fragment 树走主题默认字体，实测 36.75px。
    // 见 LocalIntegerFontAdvance 的说明。
    val renderSize = if (LocalIntegerFontAdvance.current) {
        with(density) { fontSize.toPx().roundToInt().toSp() }
    } else {
        fontSize
    }
    Text(
        text = text,
        modifier = modifier.platformBlockHeight(lineBoxPx),
        color = color,
        fontSize = renderSize,
        lineHeight = with(density) { density.platformLineHeight(fontSize) },
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = customFamily ?: PlatformDefaultFontFamily,
        textAlign = textAlign,
        style = LocalTextStyle.current.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
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
