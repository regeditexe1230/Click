// 画布用到了 Foundation 的实验 API 与 ComposeUi 的 testTagsAsResourceId（都需要显式 opt-in）
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.yjc.click.ui.program

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.yjc.click.program.ProgramNode
import com.yjc.click.program.ProgramNodeType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 画布上的手势在做什么。 */
private enum class Gesture { NONE, PAN, NODE, MARQUEE, PORT }

/** 拖线悬停到的节点 + 这次连接是否合法（合法=源色，非法=红）。 */
private data class DropTarget(val nodeId: String, val ok: Boolean)

/** 连线失败时的红色（与卡片里的 ErrorRed 保持一致）。 */
private val GraphErrorRed = Color(0xFFD32F2F)

/**
 * 无限画布。
 *
 * 坐标换算（关键）：
 * - 画布单位是 **dp@1×**（见 [NodeGraphMetrics]）：`屏幕像素 = 画布单位 × density × scale + translation`；
 * - 内容整层套 `graphicsLayer { scaleX = scale; scaleY = scale; translationX/Y = translation }`，
 *   transformOrigin 固定左上角；
 * - 反过来 `画布点 = (屏幕点 - translation) / (scale * density)`，见 [toCanvas]；
 * - 单指拖动只累加 translation（每帧增量直接加）；捏合时以双指中心为不动点：
 *   `translation += (1 - applied) * (centroid - translation)`，再 `scale = clamp(scale * zoom)`。
 * - 内容层里的布局一律用 **dp**（`node.x.dp`，不要再 `.toDp()`：那等于按像素算，真机上会错开 density 倍）；
 *   内容层里的绘制用**画布坐标 × density** 得到层内像素，线宽 / 端口半径按 `1 / scale` 反向补偿，
 *   这样屏幕上看起来的粗细和圆点大小都不随缩放变化。
 */
@Composable
internal fun NodeGraphCanvas(
    state: NodeGraphState,
    /** 松手落在另一个节点上且校验通过：连上这条线。 */
    onConnect: (fromId: String, toId: String) -> Unit,
    /** 校验不通过：目标节点变红抖一下 + 底部弹一行提示。 */
    onConnectFailed: (toId: String, reason: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // pointerInput 的 lambda 不该因为回调变化而重启，用 latest 兜住最新的回调
    val latest = rememberUpdatedState(onConnect to onConnectFailed)
    // 连线失败原因是资源 id，画布这边翻成文案（模型层不持有资源）
    val context = LocalContext.current

    // 拖线状态
    var portFrom by remember { mutableStateOf<String?>(null) }
    var portPoint by remember { mutableStateOf<Offset?>(null) }   // 画布坐标
    var dropTarget by remember { mutableStateOf<DropTarget?>(null) }

    // 框选矩形（画布坐标）
    var marquee by remember { mutableStateOf<Rect?>(null) }

    // 连线失败反馈：哪一个节点在抖
    var errorNode by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }

    val gridDot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    // 连线用中性色（深色下就是白线，浅色下是深灰），不再按分支上色 —— UE/Blender 那样
    val wireColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    // 接线柱外圈：用 onSurface 高对比描边，里面填节点自己的颜色（Blender 的接口就是这个样子）
    val portRing = MaterialTheme.colorScheme.onSurface
    val canvasBg = MaterialTheme.colorScheme.background
    val marqueeFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val marqueeStroke = MaterialTheme.colorScheme.primary

    val steps = remember(state.program) { stepIndices(state.program) }

    fun playShake(nodeId: String) {
        errorNode = nodeId
        scope.launch {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
            errorNode = null
        }
    }

    /**
     * 双指：平移 + 捏合缩放，以双指中心为不动点。
     * 从空白处按下和从节点上按下都会走到这里，所以「捏合」在任何位置都能用。
     */
    fun applyTwoFinger(event: PointerEvent) {
        val zoom = event.calculateZoom()
        val centroid = event.calculateCentroid(useCurrent = true)
        if (zoom != 1f && centroid != Offset.Unspecified) {
            val newScale = (state.scale * zoom).coerceIn(
                NodeGraphMetrics.MIN_SCALE,
                NodeGraphMetrics.MAX_SCALE,
            )
            // 缩放前后，centroid 处的画布点保持不动：translation' = c - (c - t) * (newScale / scale)
            val applied = newScale / state.scale
            val t = state.translation
            state.translation = centroid - (centroid - t) * applied
            state.scale = newScale
        } else {
            val pan = event.calculatePan()
            if (pan != Offset.Zero) {
                state.translation = state.translation + pan
            }
        }
        event.changes.forEach { it.consume() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { testTagsAsResourceId = true }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 屏幕像素 → 画布单位：画布单位是 dp@1×，1 屏幕像素 = 1 / (density * scale) 单位
                    val pxToCanvas = 1f / (state.scale * density.density)
                    val slopPx = NodeGraphMetrics.TAP_SLOP_DP * density.density
                    val slopCanvas = slopPx * pxToCanvas
                    val portTouchCanvas =
                        NodeGraphMetrics.PORT_TOUCH_DP * density.density * pxToCanvas
                    var mode = Gesture.NONE
                    var dragDistance = 0f
                    var dragStarted = false
                    // 按下的那个节点：没拖动就抬手 = 一次单击，把选中收敛到它一个
                    var pressedNodeId: String? = null

                    // ---------- 按下的瞬间就决定这次手势干什么 ----------
                    val downScreen = down.position
                    val downCanvas = state.toCanvas(downScreen, density.density)
                    val hit = hitTest(state.program, downCanvas, padding = slopCanvas)
                    if (hit != null) {
                        pressedNodeId = hit.id
                        // 输出端口（卡片右边缘那个点）附近按下 = 从这个端口拖线出去；
                        // 卡片上其它位置按下 = 直接拖动节点（不用长按）。
                        val port = outputPortCenter(hit)
                        val onPort = (downCanvas - port).getDistance() <= portTouchCanvas
                        mode = if (onPort) Gesture.PORT else Gesture.NODE
                        if (mode == Gesture.PORT) {
                            portFrom = hit.id
                            portPoint = port // 起点吸在端口圆心上
                        } else if (hit.id !in state.selectedIds) {
                            // 点一下节点就选中它：按下即选中，不需要先拖动或长按
                            state.selectedIds = setOf(hit.id)
                        }
                    }

                    if (mode == Gesture.NONE) {
                        // 空白处按住 LONG_PRESS_MS 不动 = 框选；在这之前抬手 / 滑动 /
                        // 落下第二根手指都算平移或缩放。
                        // 不用系统的 awaitLongPressOrCancellation：它固定 500ms，
                        // 跟双指缩放挨得太近，想缩放时手指刚落下一会儿就被判成框选。
                        var boxSelect = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ev.changes.size >= 2) break // 第二根手指 → 缩放
                            if ((ch.position - downScreen).getDistance() > slopPx) break // 动了 → 平移
                            if (ch.uptimeMillis - down.uptimeMillis >= NodeGraphMetrics.LONG_PRESS_MS) {
                                boxSelect = true
                                break
                            }
                        }
                        mode = if (boxSelect) {
                            marquee = Rect(downCanvas, downCanvas)
                            dragDistance += slopPx
                            Gesture.MARQUEE
                        } else {
                            Gesture.PAN
                        }
                    }

                    // ---------- 移动 / 抬手 ----------
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size

                        when (mode) {
                            Gesture.PAN -> {
                                if (pointerCount >= 2) {
                                    applyTwoFinger(event)
                                } else {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) {
                                        state.translation = state.translation + pan
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            Gesture.NODE -> {
                                if (pointerCount >= 2) {
                                    // 手指先落在节点上、随后又加了一根：这一下改成缩放
                                    if (dragStarted) {
                                        dragStarted = false
                                        state.endGesture()
                                    }
                                    applyTwoFinger(event)
                                } else {
                                    val pan = event.calculatePan()
                                    dragDistance += pan.getDistance()
                                    if (!dragStarted && dragDistance > slopPx) {
                                        dragStarted = true
                                        state.beginGesture()
                                    }
                                    if (dragStarted && pan != Offset.Zero) {
                                        // 手指挪了 1 屏幕像素 = 1 / (density * scale) 画布单位
                                        val k = 1f / (state.scale * density.density)
                                        state.moveSelected(pan.x * k, pan.y * k)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            Gesture.MARQUEE -> {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change != null) {
                                    val c = state.toCanvas(change.position, density.density)
                                    marquee = Rect(
                                        minOf(downCanvas.x, c.x),
                                        minOf(downCanvas.y, c.y),
                                        maxOf(downCanvas.x, c.x),
                                        maxOf(downCanvas.y, c.y),
                                    )
                                    change.consume()
                                }
                            }

                            Gesture.PORT -> {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change != null) {
                                    val c = state.toCanvas(change.position, density.density)
                                    portPoint = c
                                    val fromId = portFrom
                                    val target = if (fromId == null) {
                                        null
                                    } else {
                                        hitTest(
                                            program = state.program,
                                            canvasPoint = c,
                                            padding = NodeGraphMetrics.DROP_PADDING_DP / state.scale,
                                        )?.takeIf { it.id != fromId }
                                    }
                                    dropTarget = if (target != null && fromId != null) {
                                        DropTarget(
                                            target.id,
                                            state.program.connectError(fromId, target.id) == null,
                                        )
                                    } else {
                                        null
                                    }
                                    change.consume()
                                }
                            }

                            Gesture.NONE -> Unit
                        }

                        if (event.changes.none { it.pressed }) finished = true
                    }

                    // ---------- 手势收尾 ----------
                    when (mode) {
                        Gesture.PAN -> Unit

                        Gesture.NODE -> if (dragStarted) {
                            state.endGesture()
                        } else {
                            // 没拖动 = 单击：把选中收敛到按下的这个节点（多选时点谁就只留谁）
                            pressedNodeId?.let { state.selectedIds = setOf(it) }
                        }

                        Gesture.MARQUEE -> {
                            val rect = marquee
                            // 长按后没怎么拖动：当成一次「点空白」，保留原选中不动
                            if (rect != null && (rect.width > 4f || rect.height > 4f)) {
                                state.selectedIds = nodesInRect(state.program, rect, 6f)
                                    .map { it.id }
                                    .toSet()
                            }
                            marquee = null
                        }

                        Gesture.PORT -> {
                            val fromId = portFrom
                            val target = dropTarget
                            portFrom = null
                            portPoint = null
                            dropTarget = null
                            val fromNode = if (fromId != null) state.program.node(fromId) else null
                            if (fromId != null && fromNode != null) {
                                if (target != null) {
                                    val err = state.program.connectError(fromId, target.nodeId)
                                    if (err != null) {
                                        // 非法连接：目标节点红一下抖一下 + 底部提示，不连线
                                        playShake(target.nodeId)
                                        latest.value.second(target.nodeId, context.getString(err.res))
                                    } else {
                                        latest.value.first(fromId, target.nodeId)
                                    }
                                }
                                // 落在空白处什么都不做：新建节点的入口已经改成底部积木托盘
                                // （见 docs/node-tray-spec.md §1／§5），这里不再弹菜单、也不新建。
                            }
                        }

                        Gesture.NONE -> {
                            // 空白处点了一下：取消选中
                            if (dragDistance == 0f) state.selectedIds = emptySet()
                        }
                    }
                }
            },
    ) {
        // ---- 网格点（屏幕坐标，用取模让点阵跟着平移滚动）----
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = canvasBg)
            drawGridDots(
                translation = state.translation,
                scale = state.scale,
                stepDp = 40f,
                dotColor = gridDot,
                densityScale = density.density,
            )
        }

        // ---- 内容层：整体缩放 + 平移（transformOrigin 固定左上角，便于坐标换算）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.translation.x
                    translationY = state.translation.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            // 连线画在节点下面
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawEdges(
                    state = state,
                    errorNode = errorNode,
                    errorColor = GraphErrorRed,
                    wireColor = wireColor,
                    densityScale = density.density,
                )
            }

            // 正在拖的那根线（跟着手指）：起点 = 源节点的输出端口
            val from = portFrom
            val to = portPoint
            if (from != null && to != null) {
                val src = state.program.node(from)
                if (src != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawDragLine(
                            start = outputPortCenter(src),
                            end = to,
                            color = programColor(src.color),
                            ok = dropTarget?.ok != false,
                            errorColor = GraphErrorRed,
                            densityScale = density.density,
                            scale = state.scale,
                        )
                    }
                }
            }

            // 节点卡片 + 它自己的接线柱放在**同一个 Box（同一层）**里：
            // 以前端口是在下面那个 fillMaxSize 覆盖层里「遍历所有节点一次性画」的，z 序与所属节点脱钩
            // ⇒ 所有端口恒定压在所有卡片之上（用户报「每个节点的接线柱应该和节点一起，不是始终置于顶层」）。
            // 现在卡片与端口同层、端口紧随卡片之后绘制，后画的节点自然盖住先画节点的端口；
            // 命中判定（PORT_TOUCH_DP 那套按距离命中）在别处、与这一层无关，未动。
            // 注意：内容层的 graphicsLayer 已经负责缩放平移；画布单位就是 dp@1×，
            // 所以这里的偏移**直接给 .dp**（乘 density 由布局完成）。用 .toDp() 的话等于把画布单位
            // 当成像素，真机上卡片会比模型小 density 倍 —— 端口、连线和命中判定就全错位了。
            // 选中的（含正在拖动的：按下即选中，见上面的 pressedNodeId 分支）排到最后绘制 = 最顶层；
            // sortedBy 是**稳定排序** ⇒ 未选中节点之间的先后完全不变。
            // key(node.id) 必须在：列表顺序一变，没有 key 的话 Compose 会按位置复用组合，
            // 卡片内部状态（抖动动画 / testTag / 语义）会跟着位置错位。
            state.program.nodes
                .sortedBy { if (it.id in state.selectedIds) 1 else 0 }
                .forEach { node ->
                    key(node.id) {
                        Box(
                            modifier = Modifier
                                .offset(x = node.x.dp, y = node.y.dp)
                                .size(NodeGraphMetrics.CARD_W.dp, NodeGraphMetrics.CARD_H.dp),
                        ) {
                            NodeCard(
                                node = node,
                                stepIndex = steps[node.id],
                                selected = node.id in state.selectedIds,
                                connectTarget = dropTarget?.nodeId == node.id,
                                shakeProgress = if (errorNode == node.id) shake.value else 0f,
                                modifier = Modifier
                                    .testTag("node_" + node.id)
                                    .semantics { testTagsAsResourceId = true },
                            )
                            // 端口圆点：圆心是**相对本节点**的偏移；圆点允许溢出这个 132×84dp 的盒子，
                            // Compose 默认不裁剪绘制内容（只有 clip / graphicsLayer(clip = true) 才裁）。
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawNodePorts(
                                    node = node,
                                    state = state,
                                    ringColor = portRing,
                                    densityScale = density.density,
                                )
                            }
                        }
                    }
                }

            // 框选矩形（画布坐标 × density → 层内像素）
            val rect = marquee
            if (rect != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val k = density.density
                    val topLeft = Offset(rect.left * k, rect.top * k)
                    val boxSize = Size(rect.width * k, rect.height * k)
                    drawRect(color = marqueeFill, topLeft = topLeft, size = boxSize)
                    drawRect(
                        color = marqueeStroke,
                        topLeft = topLeft,
                        size = boxSize,
                        style = Stroke(width = 1.5f / state.scale),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 绘制

/** 画网格点（屏幕坐标，用取模让点阵随平移滚动）。 */
private fun DrawScope.drawGridDots(
    translation: Offset,
    scale: Float,
    stepDp: Float,
    dotColor: Color,
    densityScale: Float,
) {
    val step = stepDp * scale * densityScale
    if (step < 8f) return
    // 半径跟着缩放走：缩到 0.5x 时 1.6px 的点在 1080p 上基本看不见（Blender 的点阵是随缩放变粗的）
    val dotRadius = (1.8f * scale * densityScale).coerceIn(1.4f, 3.6f)
    // 每 5 格一个稍大的点，层次感接近 Blender/UE
    val majorRadius = dotRadius * 1.7f
    var xi = 0
    var x = translation.x % step
    while (x < size.width) {
        var yi = 0
        var y = translation.y % step
        while (y < size.height) {
            val major = xi % 5 == 0 && yi % 5 == 0
            drawCircle(
                color = dotColor,
                radius = if (major) majorRadius else dotRadius,
                center = Offset(x, y),
            )
            y += step
            yi++
        }
        x += step
        xi++
    }
}

/**
 * 画所有连线：源节点右边缘的**输出端口** → 目标节点左边缘的**输入端口**的贝塞尔曲线，
 * 末端带箭头。同一节点扇出多条线时，只把曲线的控制点上下摊开，两端仍旧落在端口上。
 *
 * 内容层已被 graphicsLayer 缩放，所以这里先把画布坐标乘 [densityScale] 换成层内像素；
 * 线宽与箭头长度再按 `1 / scale` 反向补偿 —— 屏幕上看起来才是恒定粗细。
 */
private fun DrawScope.drawEdges(
    state: NodeGraphState,
    errorNode: String?,
    errorColor: Color,
    wireColor: Color,
    densityScale: Float,
) {
    val graph = state.program
    val scale = state.scale
    val strokeWidth = wireWidthPx(densityScale) / scale
    val arrowLength = arrowLengthPx(densityScale) / scale
    // 线的终点停在端口圆点外沿，箭头才不会被圆点盖住
    val portGap = portRadiusPx(densityScale, selected = false) / scale + 1.5f / scale
    graph.nodes.forEach { from ->
        val outs = graph.outEdges(from.id)
        if (outs.isEmpty()) return@forEach
        val start = outputPortCenter(from)
        val x1 = start.x * densityScale
        val y1 = start.y * densityScale
        outs.forEachIndexed { index, edge ->
            val to = graph.node(edge.toId) ?: return@forEachIndexed
            val end = inputPortCenter(to)
            val x2 = end.x * densityScale
            val y2 = end.y * densityScale
            // 只有一条线时不偏移；多条共用一个端口时按 -1/0/+1… 摊开（只动控制点）
            val spread = if (outs.size > 1) (index - (outs.size - 1) / 2f) * 12f * densityScale else 0f
            val tipX = x2 - portGap
            val tipY = y2
            val dx = max(abs(x2 - x1) * 0.5f, 40f * densityScale)
            val c1 = Offset(x1 + dx, y1 + spread)
            val c2 = Offset(x2 - dx, y2 + spread)
            val path = Path().apply {
                moveTo(x1, y1)
                cubicTo(c1.x, c1.y, c2.x, c2.y, tipX, tipY)
            }
            val color = if (errorNode == edge.toId) errorColor else wireColor
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
            drawArrowHead(tip = Offset(tipX, tipY), toward = c2, color = color, length = arrowLength)
        }
    }
}

/** 末端箭头：方向 = 曲线进入目标时的切线；[length] 是层内像素长度。 */
private fun DrawScope.drawArrowHead(tip: Offset, toward: Offset, color: Color, length: Float) {
    val dx = tip.x - toward.x
    val dy = tip.y - toward.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.01f) return
    val ux = dx / len
    val uy = dy / len
    val size = length
    val half = length * 0.55f
    val baseX = tip.x - ux * size
    val baseY = tip.y - uy * size
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX - uy * half, baseY + ux * half)
        lineTo(baseX + uy * half, baseY - ux * half)
        close()
    }
    drawPath(path, color)
}

/**
 * 接线柱（端口圆点）：手绘在**该节点自己的** Canvas 里（与卡片同层，见节点卡片的绘制处），
 * 所以端口的 z 序跟着节点走、会被后面的节点盖住；不生成 Compose 元素。
 *
 * 每个端口 = 外圈 [ringColor]（onSurface，高对比）+ 内部填充节点自己的颜色，
 * 半径按**屏幕像素**算（层内半径 = 屏幕半径 / scale），所以画布缩到 0.5× 时圆点大小不变、
 * 依旧清清楚楚；选中时按 [NodeGraphMetrics.PORT_SELECTED_SCALE] 放大一点。
 * 坐标用**相对该节点**的偏移（父 Canvas 的原点就是卡片左上角）。
 */
private fun DrawScope.drawNodePorts(
    node: ProgramNode,
    state: NodeGraphState,
    ringColor: Color,
    densityScale: Float,
) {
    val scale = state.scale
    val accent = programColor(node.color)
    val selected = node.id in state.selectedIds
    val radius = portRadiusPx(densityScale, selected) / scale
    val ringWidth = portRingPx(densityScale) / scale
    portCenters(node).forEach { center ->
        val c = Offset((center.x - node.x) * densityScale, (center.y - node.y) * densityScale)
        drawCircle(color = accent, radius = radius - ringWidth / 2f, center = c)
        drawCircle(
            color = ringColor,
            radius = radius,
            center = c,
            style = Stroke(width = ringWidth),
        )
    }
}

/** 连线的屏幕粗细（像素）：细线在高密度屏上太虚，按 dp 兜一个下限。 */
private fun wireWidthPx(density: Float): Float = max(2.5f, 1.3f * density)

/** 箭头长度的屏幕尺寸（像素）。 */
private fun arrowLengthPx(density: Float): Float = max(10f, 3.6f * density)

/** 接线柱外圈描边的屏幕宽度（像素）。 */
private fun portRingPx(density: Float): Float =
    max(NodeGraphMetrics.PORT_RING_PX, 1.5f * density)

/**
 * 接线柱半径（**屏幕像素**，与画布缩放无关）。
 * 基准 8px；高密度屏上 8px 太细，按 5.5dp 兜底，于是真机上就是一个清清楚楚的圆点。
 */
private fun portRadiusPx(density: Float, selected: Boolean): Float {
    val base = max(
        NodeGraphMetrics.PORT_RADIUS_PX,
        NodeGraphMetrics.PORT_RADIUS_MIN_DP * density,
    )
    return if (selected) base * NodeGraphMetrics.PORT_SELECTED_SCALE else base
}

/**
 * 正在拖的那根线：源色（目标非法时红色）虚线，末端一个圆点。
 * 坐标是画布单位，这里乘 [densityScale] 换成层内像素；线宽/虚线/圆点按 `1 / scale` 反向补偿。
 */
private fun DrawScope.drawDragLine(
    start: Offset,
    end: Offset,
    color: Color,
    ok: Boolean,
    errorColor: Color,
    densityScale: Float,
    scale: Float,
) {
    val c = if (ok) color else errorColor
    val sx = start.x * densityScale
    val sy = start.y * densityScale
    val ex = end.x * densityScale
    val ey = end.y * densityScale
    val path = Path().apply {
        val dx = max(abs(ex - sx) * 0.5f, 40f * densityScale)
        moveTo(sx, sy)
        cubicTo(sx + dx, sy, ex - dx, ey, ex, ey)
    }
    // 虚线间隔也按 1/scale 放大，屏幕上才是一致的疏密
    val dash = floatArrayOf(12f / scale, 8f / scale)
    drawPath(
        path = path,
        color = c,
        style = Stroke(
            width = wireWidthPx(densityScale) / scale,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(dash, 0f),
        ),
    )
    drawCircle(
        color = c,
        radius = portRadiusPx(densityScale, selected = false) / scale,
        center = Offset(ex, ey),
    )
}
