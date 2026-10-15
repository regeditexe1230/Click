package com.yjc.click.ui.program

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.program.ProgramNode
import com.yjc.click.program.ProgramNodeType
import com.yjc.click.program.WaitMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 卡片里小字的字号。 */
private const val TINY_FONT = 9f

/** 类型图标大小（dp，落在卡片内部的布局里，所以是真正的 dp）。 */
private val ICON_SIZE = 14.dp

/**
 * 一个节点卡片（132 × 84 画布单位，随画布一起缩放）。
 *
 * 结构：左侧一条 8 宽的颜色条 + 右侧内容区。
 * 内容区从上到下：类型图标 + 类型名 + 步骤序号，滑动缩略图 / 参数小字，手势名或坐标。
 *
 * @param stepIndex 图上从开始节点按连线顺序算出的步骤序号；START 传 null（显示「开始」）
 * @param selected 是否被选中（描主题色边）
 * @param connectTarget 是否是拖线时悬停到的合法目标（描源节点色边，更醒目）
 * @param shakeProgress 连线失败时的抖动进度 0..1（左右摇摆并衰减，到 1 时归零）
 */
@Composable
internal fun NodeCard(
    node: ProgramNode,
    stepIndex: Int?,
    selected: Boolean,
    connectTarget: Boolean,
    shakeProgress: Float,
    modifier: Modifier = Modifier,
) {
    val accent = programColor(node.color)
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val radius = RoundedCornerShape(NodeGraphMetrics.CARD_RADIUS.dp)

    // 抖动：sin(4π t) 乘衰减系数，t = 1 时归零，不会留下残余偏移
    val shakeDx = if (shakeProgress > 0f) {
        sin(shakeProgress * 4f * Math.PI.toFloat()) * 10f * (1f - shakeProgress)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .size(
                width = NodeGraphMetrics.CARD_W.dp,
                height = NodeGraphMetrics.CARD_H.dp,
            )
            .offset(x = shakeDx.dp, y = 0.dp)
            .shadow(3.dp, radius)
            .clip(radius)
            .background(surface)
            .then(
                when {
                    connectTarget -> Modifier.border(2.dp, accent, radius)
                    selected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, radius)
                    else -> Modifier
                },
            ),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---- 左侧颜色条 ----
            Box(
                modifier = Modifier
                    .width(NodeGraphMetrics.BAR_W.dp)
                    .fillMaxHeight()
                    .background(accent),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 7.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            ) {
                // ---- 第一行：类型图标 + 类型名 + 步骤序号 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(typeIconRes(node.type)),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                    Spacer(Modifier.width(4.dp))
                    NodeText(
                        text = stringResource(typeNameRes(node.type)),
                        fontSize = 11.sp,
                        color = onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    NodeText(
                        text = if (node.type == ProgramNodeType.START) {
                            stringResource(R.string.node_graph_type_start)
                        } else {
                            stringResource(R.string.node_graph_step, stepIndex ?: 0)
                        },
                        fontSize = 10.sp,
                        color = muted,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.height(2.dp))

                // ---- 中间：滑动缩略图 / 参数小字 ----
                when (node.type) {
                    ProgramNodeType.SWIPE -> {
                        // 40dp 的轨迹缩略图，时长 / 次数的小字放在它右边
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SwipeThumb(
                                node = node,
                                accent = accent,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                ParamLine(stringResource(R.string.node_graph_thumb_ms, node.durationMs), muted)
                                ParamLine("×" + node.repeatCount, muted)
                            }
                        }
                    }

                    ProgramNodeType.CLICK -> {
                        ParamLine("×" + node.repeatCount, muted)
                        ParamLine(node.pointX.toString() + "," + node.pointY.toString(), muted)
                    }

                    ProgramNodeType.DELAY -> {
                        ParamLine(stringResource(R.string.node_graph_thumb_ms, node.delayMs), muted)
                    }

                    ProgramNodeType.WAIT -> {
                        ParamLine(
                            text = if (node.waitMode == WaitMode.TIME) {
                                stringResource(R.string.node_graph_wait_time)
                            } else {
                                stringResource(R.string.node_graph_wait_user)
                            },
                            color = muted,
                        )
                    }

                    ProgramNodeType.LOOP -> {
                        ParamLine(
                            text = if (node.loopCount <= 0) {
                                stringResource(R.string.node_graph_loop_infinite_short)
                            } else {
                                "×" + node.loopCount
                            },
                            color = muted,
                        )
                    }

                    ProgramNodeType.START -> {
                        // 只有一行小字：别贴着标题，在卡片剩余空间里竖直居中
                        Spacer(Modifier.weight(1f))
                        ParamLine(stringResource(R.string.node_graph_start_short), muted)
                    }
                }

                Spacer(Modifier.weight(1f))

                // ---- 末行：手势名（没有就显示坐标）----
                if (node.type == ProgramNodeType.CLICK || node.type == ProgramNodeType.SWIPE) {
                    NodeText(
                        text = gestureOrPoint(node),
                        fontSize = TINY_FONT.sp,
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 手势名优先，为空时显示坐标。 */
private fun gestureOrPoint(node: ProgramNode): String {
    val name = node.gestureName?.trim().orEmpty()
    return if (name.isNotEmpty()) name else node.pointX.toString() + "," + node.pointY.toString()
}

/** 卡片里的一行参数小字。 */
@Composable
private fun ParamLine(text: String, color: Color) {
    NodeText(
        text = text,
        fontSize = TINY_FONT.sp,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(top = 1.dp),
    )
}

/**
 * 卡片专用文本：与 [com.yjc.click.ui.theme.ClickText] 同一套渲染规则（平台默认字型、字间距 0、
 * 常规字重、includeFontPadding = false），但支持 [maxLines] / [overflow]，
 * 长名字在上面也不会把卡片撑破。
 */
@Composable
internal fun NodeText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    fontFamily: FontFamily = FontFamily(Typeface.DEFAULT),
) {
    val density = LocalDensity.current
    val renderSize = with(density) { fontSize.toPx().roundToInt().toSp() }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = renderSize,
        lineHeight = renderSize * 1.15f,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = fontFamily,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        style = LocalTextStyle.current.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

/**
 * 滑动轨迹缩略图：把 [ProgramNode.swipePoints]（一串 [x, y] 屏幕绝对坐标）等比归一化后
 * 画进这个小 Canvas，用节点颜色画折线。
 */
@Composable
internal fun SwipeThumb(node: ProgramNode, accent: Color, modifier: Modifier = Modifier) {
    val points = node.swipePoints()
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    // 没有轨迹点时用起点 → 终点画一条直线示意
    val fallback = listOf(
        listOf(node.pointX.toFloat(), node.pointY.toFloat()),
        listOf(node.endX.toFloat(), node.endY.toFloat()),
    )

    Canvas(modifier = modifier) {
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(4f),
            style = Stroke(width = 1f),
        )
        val list = if (points.isEmpty()) fallback else points
        drawNormalizedLine(list, accent)
    }
}

/** 把一串屏幕绝对坐标等比缩放进当前 Canvas（留 3 的边距，居中）。 */
private fun DrawScope.drawNormalizedLine(raw: List<List<Float>>, color: Color) {
    if (raw.isEmpty()) return
    val w = size.width
    val h = size.height
    if (w <= 4f || h <= 4f) return

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    raw.forEach { p ->
        if (p.size < 2) return@forEach
        minX = min(minX, p[0])
        minY = min(minY, p[1])
        maxX = max(maxX, p[0])
        maxY = max(maxY, p[1])
    }
    if (minX > maxX) return

    val spanX = (maxX - minX).takeIf { it > 0.01f } ?: 1f
    val spanY = (maxY - minY).takeIf { it > 0.01f } ?: 1f
    // 等比（保持轨迹形状），居中
    val k = min((w - 6f) / spanX, (h - 6f) / spanY)
    val dx = (w - spanX * k) / 2f
    val dy = (h - spanY * k) / 2f

    fun map(p: List<Float>) = Offset(dx + (p[0] - minX) * k, dy + (p[1] - minY) * k)

    val pts = raw.filter { it.size >= 2 }.map(::map)
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        drawCircle(color = color, radius = 2.5f, center = pts[0])
        return
    }

    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))

    // 起点空心、终点实心：方向一眼能看出来
    drawCircle(color = color, radius = 2.4f, center = pts.first(), style = Stroke(width = 1.4f))
    drawCircle(color = color, radius = 2.4f, center = pts.last())
}

/** 节点类型对应的图标资源。 */
internal fun typeIconRes(type: ProgramNodeType): Int = when (type) {
    ProgramNodeType.START -> R.drawable.ic_play
    ProgramNodeType.CLICK -> R.drawable.ic_edit
    ProgramNodeType.SWIPE -> R.drawable.ic_swipe
    ProgramNodeType.DELAY -> R.drawable.ic_timer
    ProgramNodeType.WAIT -> R.drawable.ic_lock
    ProgramNodeType.LOOP -> R.drawable.ic_refresh
}
