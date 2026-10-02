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

/** 旧版主题给每个 TextView 附加的 lineSpacingExtra（4dp） */
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
 */
fun Density.platformFontSize(fontSize: TextUnit): TextUnit =
    fontSize.toPx().roundToInt().toSp()

/** 当前生效的字型：自定义字体优先，否则平台默认字体（与旧版 FontManager 铺下去的一致） */
/**
 * 旧版单行盒高实测标定表（取整后的 px 字号 → 盒高 px）。
 *
 * 在模拟器上用旧版真实渲染逐字号量得：12sp(32px)→48、13sp(34px)→50、14sp(37px)→54、
 * 15sp(39px)→56、16sp(42px)→62。运行时探测在个别字体环境下会比旧版少几个像素，
 * 因此已标定的字号以标定值为准（见 [platformLineBoxPx]）。
 */
private val CALIBRATED_LINE_BOX_PX = mapOf(32 to 48, 34 to 50, 37 to 54, 39 to 56, 42 to 62)
private fun currentPlatformTypeface(): android.graphics.Typeface =
    com.yjc.click.FontManager.currentTypeface ?: android.graphics.Typeface.DEFAULT

/**
 * 旧版 TextView 的**单行行盒高度（px）**。
 *
 * 两个来源取较大者：
 * 1. [CALIBRATED_LINE_BOX_PX]：在模拟器上用**旧版真实渲染**逐字号量出来的值（最可靠）；
 * 2. 运行时探测：现造一个真正的 [android.widget.TextView]（旧版用的就是它）按当前设备的
 *    font_scale / 密度 / 系统字体量一次——用于标定表覆盖不到的字号或别的设备
 *    （例如手机 OEM 字体行高比例与模拟器 Roboto 不同）。
 *
 * 探测结果按 (取整后的 px 字号, 字型) 缓存在进程级 map 里：`remember` 只在同一次组合里有效，
 * 而页面（例如首页）每次切回来都会重新组合一次，30 个文本十来个字号就要全部重量一遍，
 * 实测这正是切页那一帧变长的一部分。
 */
private val lineBoxProbeCache = HashMap<Pair<Int, android.graphics.Typeface>, Int>()

@Composable
fun platformLineBoxPx(fontSize: TextUnit): Int {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = with(density) { fontSize.toPx().roundToInt() }.coerceAtLeast(1)
    val typeface = currentPlatformTypeface()
    val measured = androidx.compose.runtime.remember(px, typeface) {
        lineBoxProbeCache.getOrPut(px to typeface) {
            val probe = android.widget.TextView(context)
            probe.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px.toFloat())
            probe.typeface = typeface
            probe.includeFontPadding = true
            probe.text = "测"
            probe.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            probe.measuredHeight
        }
    }
    // 标定值是在模拟器（fontScale = 1.0）上量出来的：只有当前设备字体缩放同为 1.0 时才用它，
    // 否则（例如手机 font_scale = 0.81、OEM 字体行高比例不同）一律用运行时真机探测值。
    val calibrated = if (density.fontScale == 1f) CALIBRATED_LINE_BOX_PX[px] else null
    return calibrated ?: measured
}

/**
 * 旧版单行行盒高度：见 [platformLineBoxPx]（多行按每行累加）。
 * Compose 对设置的 lineHeight 向上取整，因此减去一个极小量，确保落回旧版的整数值。
 */
@Composable
fun platformLineHeight(fontSize: TextUnit): TextUnit {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { (platformLineBoxPx(fontSize) - 0.01f).toSp() }
}

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
    val lineBoxPx = platformLineBoxPx(fontSize)
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
        lineHeight = platformLineHeight(fontSize),
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
