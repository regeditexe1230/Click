package com.yjc.click.ui.program

import androidx.annotation.StringRes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.program.ProgramNode
import com.yjc.click.program.ProgramNodeType
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * 底部积木托盘的尺寸口径（spec §8）。
 *
 * 形态是 **MD3 可滑动的底部面板**：顶部一条拖柄，下面一行 Tab 分类，再下面是该分类的块卡片。
 * 展开 = 屏高的 [EXPANDED_FRACTION]（本机 3200px → 800px），收起 = 只剩拖柄；
 * 两档之间**跟手拖动、松手按 50% 阈值吸附**，没有中间停靠态。
 */
internal object TrayMetrics {
    /** 展开态占屏高的比例（spec §8.1：本机 3200px → 800px）。 */
    const val EXPANDED_FRACTION = 0.25f

    /** 收起态高度 = 拖柄区（上下各 22dp 内边距，同时也是手势栏的安全边距）。 */
    val COLLAPSED_HEIGHT = 64.dp

    /** MD3 DragHandle：32 × 6dp 圆角横杠。 */
    val HANDLE_W = 32.dp
    val HANDLE_H = 6.dp

    /** 面板底部让给系统手势区的内边距下限（真机 navigationBars 一般 16dp）。 */
    val MIN_BOTTOM_GUARD = 16.dp

    /** MD3 TabRow 的高度；收起态剩余高度不到这一行时，Tab 和块列表一律不画。 */
    val TAB_ROW_HEIGHT = 48.dp

    /** 面板顶部圆角（MD3 large bottom sheet）。 */
    val TOP_RADIUS = 28.dp

    /** 块列表的左右内边距与块间距（间距要够端口圆点互不压住）。 */
    val LIST_PAD_H = 16.dp
    val LIST_PAD_V = 12.dp
    val CHIP_GAP = 16.dp

    /**
     * 块卡片**左右**各留的端口余量。
     *
     * 端口圆心正好贴在 132×84dp 盒子的左右边缘上（[portCenters]），而 `alpha < 1` 时
     * `Modifier.graphicsLayer { alpha }` 会建一层离屏层、离屏层按**自己的 bounds 裁剪** ⇒
     * 不留余量时外半个圆点会被切掉（用户报的「事件栏里那个开始节点右边被裁了一部分」）。
     * 取值必须 ≥ `PORT_RADIUS_MIN_DP`(5.5dp) + 半个描边(≈0.75dp)，这里给 7dp 留一点余量；
     * 端口圆心在卡片竖直范围内（`portSideY` 的 margin=14dp），所以**上下不需要余量**。
     */
    val PORT_PAD = 7.dp

    /** 松手吸附阈值（spec §8.1：50%）。 */
    const val SNAP_THRESHOLD = 0.5f

}

/** 托盘的三个分类（spec §2）。 */
internal enum class TrayCategory(@param:StringRes val labelRes: Int) {
    EVENT(R.string.node_tray_category_event),
    ACTION(R.string.node_tray_category_action),
    CONTROL(R.string.node_tray_category_control),
}

/** 每个分类里的块，按展示顺序（spec §2：事件 1 个 / 动作 2 个 / 控制 3 个）。 */
internal val CATEGORY_BLOCKS: Map<TrayCategory, List<ProgramNodeType>> = mapOf(
    TrayCategory.EVENT to listOf(ProgramNodeType.START),
    TrayCategory.ACTION to listOf(ProgramNodeType.CLICK, ProgramNodeType.SWIPE),
    TrayCategory.CONTROL to listOf(ProgramNodeType.DELAY, ProgramNodeType.WAIT, ProgramNodeType.LOOP),
)

/** 托盘里一共几种块（spec §7 第 2 条的自测口径）。 */
internal fun trayBlockCount(): Int = CATEGORY_BLOCKS.values.sumOf { it.size }

/**
 * 托盘里那块预览用的节点：参数取该类型的默认值，卡片本身就是画布上的 [NodeCard]
 * （spec §8.2 —— 用户原话「托盘中节点预览就应该是节点在画布上的样子」）。
 */
private val TRAY_BLOCK_NODES: Map<ProgramNodeType, ProgramNode> =
    ProgramNodeType.entries.associateWith { type -> newNode(type, 0f, 0f) }

/**
 * 底部积木托盘（MD3 可滑动底部面板）。
 *
 * **只有拖柄区域能上下拖**（spec §8.1）：`BottomSheetScaffold` 那种整面板拖动会和
 * 「长按块卡片向上拖出」抢手势，所以纵向拖动只挂在拖柄上。
 *
 * 横屏（[horizontal] = true）时整体转成**屏幕右侧的侧栏**：展开 = 向左变宽到窗口宽 1/4，
 * 收起 = 只剩一条竖拖柄；分类 chip 竖排一列、块竖向排列；交互口径与竖屏完全一致。
 *
 * @param expanded 当前停靠档（持久化在 prefs 里，由调用方负责）
 * @param expandedSize 展开档的尺寸：竖屏 = 展开高（屏高 1/4），横屏 = 展开宽（窗口宽 1/4）
 * @param horizontal 横屏侧栏模式（false = 现在这套底部托盘，一个像素都不变）
 * @param running 程序运行中：托盘可见但块不可拖、不可新建
 * @param onSizeChange 面板实时尺寸回调（竖屏=高、横屏=宽；拖动/回弹的每一帧都会回调）
 */
@Composable
internal fun NodeGraphTray(
    expanded: Boolean,
    expandedSize: Dp,
    running: Boolean,
    horizontal: Boolean = false,
    onSizeChange: (Dp) -> Unit = {},
    onBoundsChange: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onExpandedChange: (Boolean) -> Unit,
    onTapBlock: (ProgramNodeType) -> Unit,
    onDragStart: (ProgramNodeType, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 让给系统手势区：竖屏看底部 inset（本机 [0,3144][1440,3200] ⇒ 56px = 16dp），
    // 横屏手势条在侧边 ⇒ 看右侧 inset。收起条与块列表都不许压进手势区
    // （否则从屏幕边缘上滑/侧滑会被拖柄抢走 = 用户报的「容易误触」）。
    val navInsetPx = with(density) {
        val raw = if (horizontal) {
            // getRight 需要布局方向（RTL 时右侧是 start）
            WindowInsets.navigationBars.getRight(this, LocalLayoutDirection.current)
        } else {
            WindowInsets.navigationBars.getBottom(this)
        }
        maxOf(raw.toFloat(), TrayMetrics.MIN_BOTTOM_GUARD.toPx())
    }
    val navInsetDp = with(density) { navInsetPx.toDp() }
    val collapsedPx = with(density) { TrayMetrics.COLLAPSED_HEIGHT.toPx() }
    // 竖屏 expandedSize = 展开高；横屏 = 展开宽（都是窗口尺寸的 1/4，由调用方算好）
    val expandedPx = with(density) { expandedSize.toPx() }
    val span = (expandedPx - collapsedPx).coerceAtLeast(1f)

    // live：面板当前处在 0（收起）..1（展开）之间的位置；拖动直接改它，松手后动画吸附
    var live by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    // 每次拖动结束都自增：即使档位没变（原地弹回、或手势被取消），也要让下面的吸附动画重跑一遍，
    // 否则面板会停在手指松开那一刻的高度（真机实测收起态比 48dp 高出 8dp）。
    var settleTick by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf(TrayCategory.EVENT) }
    // 每块卡片在根坐标里的左上角：长按拖出时要算手指所在的根坐标
    val blockOrigins = remember { mutableMapOf<ProgramNodeType, Offset>() }

    // 外部档位（prefs / 画布衬垫）变了就吸过去；手指正在拖时不抢
    LaunchedEffect(expanded, dragging, settleTick) {
        if (dragging) return@LaunchedEffect
        val target = if (expanded) 1f else 0f
        if (live != target) {
            animate(
                initialValue = live,
                targetValue = target,
                // MD3 弹簧：dampingRatio 0.75 ⇒ 轻微过冲、一次就归位（不是生硬停住）。
                // 只有「松手后的收尾」走弹簧；手指拖动时 live 由手势直接写（1:1 跟手）。
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
            ) { value, _ -> live = value }
        }
    }

    // 面板当前的实时尺寸（收起尺寸 + 展开跨度 × live）：手指拖动和弹簧回弹的每一帧都回传给屏幕，
    // 参数面板据此跟着一起动，始终贴在托盘内侧边缘（竖屏=上沿、横屏=左沿）。
    val liveSize = with(density) { (collapsedPx + span * live).toDp() }
    LaunchedEffect(liveSize) { onSizeChange(liveSize) }

    // 竖屏是底部面板（顶部圆角）；横屏是右侧侧栏（靠右，所以圆角在左侧）
    val shape = if (horizontal) {
        RoundedCornerShape(topStart = TrayMetrics.TOP_RADIUS, bottomStart = TrayMetrics.TOP_RADIUS)
    } else {
        RoundedCornerShape(topStart = TrayMetrics.TOP_RADIUS, topEnd = TrayMetrics.TOP_RADIUS)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        // 和画布明确分层：圆角 + 阴影 + 提亮的 surface（修掉 v1「与画布同色无分界」）
        shadowElevation = 8.dp,
        // 系统栏那一条让到面板**外面**：托盘外沿必须贴住窗口内沿（3 键导航下窗口只有 2274px，
        // 面板若再按整屏算就会伸进导航栏、被裁掉一条）。尺寸本身 = 窗口长边/4，见 NodeGraphScreen。
        modifier = if (horizontal) {
            modifier.padding(end = navInsetDp).fillMaxHeight().width(liveSize)
                .onGloballyPositioned { onBoundsChange(it.boundsInRoot()) }
        } else {
            modifier.padding(bottom = navInsetDp).fillMaxWidth().height(liveSize)
                .onGloballyPositioned { onBoundsChange(it.boundsInRoot()) }
        },
    ) {
        if (horizontal) {
            // ================= 横屏：屏幕右侧侧栏 =================
            // 拖柄在**左沿**：手势从竖向改成横向（口径不变：向左拖 = 展开变宽）
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(TrayMetrics.COLLAPSED_HEIGHT)
                        .fillMaxHeight()
                        .pointerInput(span, expanded) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                var lastX = down.position.x
                                var moved = 0f
                                var isDragging = false
                                var lifted = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        lifted = true
                                        break
                                    }
                                    if (change.isConsumed) break
                                    val dx = change.position.x - lastX
                                    lastX = change.position.x
                                    moved += kotlin.math.abs(dx)
                                    if (!isDragging && moved > slop) {
                                        isDragging = true
                                        dragging = true
                                    }
                                    if (isDragging) {
                                        change.consume()
                                        live = (live - dx / span).coerceIn(0f, 1f)
                                    }
                                }
                                if (!isDragging) {
                                    if (lifted) onExpandedChange(!expanded)
                                    return@awaitEachGesture
                                }
                                dragging = false
                                settleTick++
                                if (lifted) {
                                    val target = live >= TrayMetrics.SNAP_THRESHOLD
                                    if (target != expanded) onExpandedChange(target)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 横屏的拖柄竖杠（32×6dp 转 90°）
                    Box(
                        modifier = Modifier
                            .size(TrayMetrics.HANDLE_H, TrayMetrics.HANDLE_W)
                            .clip(RoundedCornerShape(TrayMetrics.HANDLE_H / 2))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }

                val restPxSide = span * live
                if (restPxSide > with(density) { TrayMetrics.TAB_ROW_HEIGHT.toPx() }) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = TrayMetrics.LIST_PAD_H, vertical = TrayMetrics.LIST_PAD_V),
                    ) {
                        // 分类 chip：MD3 侧栏风格，竖排一列
                        TrayCategory.entries.forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(stringResource(c.labelRes), fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (c != TrayCategory.entries.last()) {
                                Box(modifier = Modifier.height(TrayMetrics.CHIP_GAP / 2))
                            }
                        }
                        Box(modifier = Modifier.height(TrayMetrics.CHIP_GAP))

                        // 块：竖向排列（每块一行），超出面板高度时面板内部竖向滚动
                        val blocks = CATEGORY_BLOCKS.getValue(category)
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            val blockW = NodeGraphMetrics.CARD_W.dp + TrayMetrics.PORT_PAD * 2
                            // 横屏单列：一块一整行，按托盘可用宽等比缩放（用户口径：要完整卡片，
                            // 宁可小一点；两列会把卡片压到 0.5x，只剩文字，被明确否掉了）。
                            val colGap = TrayMetrics.CHIP_GAP / 2
                            val colW = maxWidth
                            val scale = if (blockW > colW && colW > 0.dp) {
                                (colW / blockW).coerceIn(0.2f, 1f)
                            } else {
                                1f
                            }
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(
                                    TrayMetrics.CHIP_GAP * scale,
                                    Alignment.Top,
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                blocks.forEach { type ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        TrayBlockItem(
                                            type = type,
                                            running = running,
                                            scale = scale,
                                            blockW = blockW,
                                            blockOrigins = blockOrigins,
                                            onTapBlock = onTapBlock,
                                            onDragStart = onDragStart,
                                            onDrag = onDrag,
                                            onDragEnd = onDragEnd,
                                            onDragCancel = onDragCancel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------------- 拖柄（唯一可上下拖的区域） ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrayMetrics.COLLAPSED_HEIGHT)
                    // 拖柄手势手写成一个事件循环（不用 detectVerticalDragGestures + detectTapGestures 两个
                    // pointerInput：后声明的那个会先拿到事件并把 down 消费掉，导致上下滑永远起不来）。
                    // 判据只看累计位移是否超过 touchSlop：超过 ⇒ 跟手拖；没超过就抬手 ⇒ 点一下切换档位。
                    .pointerInput(span, expanded) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            var lastY = down.position.y
                            var moved = 0f
                            var isDragging = false
                            var lifted = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    lifted = true
                                    break
                                }
                                if (change.isConsumed) break
                                val dy = change.position.y - lastY
                                lastY = change.position.y
                                moved += kotlin.math.abs(dy)
                                if (!isDragging && moved > slop) {
                                    isDragging = true
                                    dragging = true
                                }
                                if (isDragging) {
                                    change.consume()
                                    live = (live - dy / span).coerceIn(0f, 1f)
                                }
                            }
                            if (!isDragging) {
                                // 阈值内抬手 = 点一下：展开/收起（不改取块手势，只是省得拖）
                                if (lifted) onExpandedChange(!expanded)
                                return@awaitEachGesture
                            }
                            dragging = false
                            // 手势被打断（没抬手）也自增：吸附动画会把面板拉回当前档位，
                            // 不会停在中途高度。
                            settleTick++
                            if (lifted) {
                                val target = live >= TrayMetrics.SNAP_THRESHOLD
                                if (target != expanded) onExpandedChange(target)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(TrayMetrics.HANDLE_W, TrayMetrics.HANDLE_H)
                        .clip(RoundedCornerShape(TrayMetrics.HANDLE_H / 2))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            // ---------------- 分类 Tab + 块列表（收起态一律不画） ----------------
            // 收起态面板只有 280px，而拖柄本身占 224px：以前 Tab/块列表照样被布局在面板里，
            // 卡片会被画到拖柄上面（截图证据 n1.png），既违规格「收起只剩拖柄」，
            // 又把拖柄的上下滑手势挡住了（落在卡片上 ⇒ 连滑 5 次全失败）。
            // 现在只有真的展开了（剩余高度 > 一行 Tab）才画这两块。
            val restPx = span * live
            if (restPx > with(density) { TrayMetrics.TAB_ROW_HEIGHT.toPx() }) {
                TabRow(
                    selectedTabIndex = category.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    TrayCategory.entries.forEach { c ->
                        Tab(
                            selected = category == c,
                            onClick = { category = c },
                            text = { Text(stringResource(c.labelRes), fontSize = 13.sp) },
                        )
                    }
                }

                // ---------------- 当前分类的块（就是画布上的节点卡片） ----------------
                // 卡片按分类**等比缩放到整行都能放下**：以前 3 个块（控制：延时/等待/循环）总宽 1512px
                // 超过屏宽 1440px，第三个块被行尾裁掉 72px（连「第 0 步」都断了），这就是用户报的
                // 「托盘上节点显示不全」。缩放只动尺寸、不动渲染源（还是同一个 NodeCard）。
                val blocks = CATEGORY_BLOCKS.getValue(category)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                // 每个块的布局宽 = 卡片宽 + 左右各 PORT_PAD（端口余量也要算进行宽，否则整行越界）
                val blockW = NodeGraphMetrics.CARD_W.dp + TrayMetrics.PORT_PAD * 2
                val n = blocks.size
                // 可见间距口径：卡片在布局盒内左右各留 PORT_PAD×scale（缩放以盒中心为原点 ⇒ 两侧对称），
                // 所以「布局间距 gap」对应的可见间距 = gap + 2×PORT_PAD×scale。
                val gapBase = TrayMetrics.CHIP_GAP + TrayMetrics.PORT_PAD * 2
                // scale 与可用宽互相依赖 ⇒ 闭式解：把 (n+1) 个可见间距都按 gapBase 计，反解 scale。
                val needW = blockW * n +
                    TrayMetrics.CHIP_GAP * (n - 1).coerceAtLeast(0) +
                    gapBase * 2
                val scale = if (needW > maxWidth && needW > 0.dp) {
                    (maxWidth / needW).coerceIn(0.2f, 1f)
                } else {
                    1f
                }
                // 放得下时把剩余宽**均分**给 (n+1) 个可见间距：用户报的「三个节点左右间距不同」就出在这里 ——
                // 以前行内 padding 是 LIST_PAD_H（不随 scale 缩放），卡与卡却是 CHIP_GAP×scale，两套口径 ⇒
                // 边缘间距与中间间距不相等。现在三者统一由 gapVis 决定（居中排布 ⇒ 左右余量自动均分）。
                val gapVis = if (scale < 1f) {
                    gapBase * scale
                } else {
                    ((maxWidth - NodeGraphMetrics.CARD_W.dp * scale * n) / (n + 1))
                        .coerceAtLeast(gapBase * scale)
                }
                val layoutGap = (gapVis - TrayMetrics.PORT_PAD * 2 * scale).coerceAtLeast(0.dp)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = TrayMetrics.LIST_PAD_V),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = layoutGap,
                        alignment = Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    blocks.forEach { type ->
                        val dim = if (running) 0.45f else 1f
                        Box(
                            modifier = Modifier
                                .width(blockW * scale)
                                .height(NodeGraphMetrics.CARD_H.dp * scale)
                                .onGloballyPositioned { blockOrigins[type] = it.boundsInRoot().topLeft }
                                .pointerInput(type, running) {
                                    if (running) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val origin = blockOrigins[type] ?: Offset.Zero
                                        // 阈值内 · 三种结局要分开：
                                        //   抬手（没怎么动）→ 点一下，块落到画布中央；
                                        //   手指移动超过 touchSlop 或父级（列表滚动）把事件吃掉 → 让给滚动，不拖节点；
                                        //   按住不动满阈值 → 拖出去。小屏托盘要靠滑动才能看全，所以「快滑 = 滚动」优先。
                                        var tapped = false
                                        var cancelled = false
                                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                                if (ch == null || !ch.pressed) {
                                                    tapped = true
                                                    return@withTimeoutOrNull
                                                }
                                                if (ev.changes.any { it.isConsumed } ||
                                                    (ch.position - down.position).getDistance() >
                                                    viewConfiguration.touchSlop
                                                ) {
                                                    cancelled = true
                                                    return@withTimeoutOrNull
                                                }
                                            }
                                        }
                                        if (tapped) {
                                            // 阈值内抬手 = 点一下：落到画布可视区中央
                                            onTapBlock(type)
                                            return@awaitEachGesture
                                        }
                                        if (cancelled) return@awaitEachGesture
                                        // 按住不动满阈值 = 拖出去：影子块从手指处开始跟手
                                        var pos = origin + down.position
                                        onDragStart(type, pos)
                                        val completed = drag(down.id) { change ->
                                            change.consume()
                                            pos = origin + change.position
                                            onDrag(pos)
                                        }
                                        if (completed) onDragEnd(pos) else onDragCancel()
                                    }
                                },
                            // 卡片在布局盒里**居中**：内层按 1× 量（requiredSize 顶掉父级小约束），
                            // 再以盒中心为原点等比缩放 ⇒ 盒内左右各留 PORT_PAD×scale 的对称余量，
                            // 「可见间距」才严格等于 gapVis（否则余量全偏一侧，边缘间距与中间间距不等）。
                            contentAlignment = Alignment.Center,
                        ) {
                            // 先按 1× 自然尺寸量一遍（requiredSize 顶掉父级的小约束，否则卡片会被压扁），
                            // 再整体等比缩放绘制 ⇒ 图形与画布节点完全同形，只是小了一圈。
                            Box(
                                modifier = Modifier
                                    .requiredSize(blockW, NodeGraphMetrics.CARD_H.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            ) {
                                TrayBlockFace(type = type, alpha = dim)
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}

/**
 * 块卡片：**和画布上完全同一个 Composable**（[NodeCard]）加上同样的端口圆点（spec §8.2）。 * 端口只保留外观，在托盘里不参与任何交互（不可点、不可连线）。
 */
@Composable
internal fun TrayBlockFace(type: ProgramNodeType, alpha: Float = 1f) {
    val node = TRAY_BLOCK_NODES.getValue(type)
    val ringColor = MaterialTheme.colorScheme.surface
    val accent = programColor(node.color)
    val density = LocalDensity.current.density

    // 自然尺寸的盒子左右各留 [TrayMetrics.PORT_PAD]：padding 落在 graphicsLayer 之内，
    // 离屏层的 bounds 就包含这段余量，贴在卡片边缘上的端口圆点不会再被裁成半圆。
    Box(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = TrayMetrics.PORT_PAD),
    ) {
        NodeCard(
            node = node,
            stepIndex = null,
            selected = false,
            connectTarget = false,
            shakeProgress = 0f,
        )
        // 端口圆点：半径/描边与画布同一套常量（画布单位就是 dp@1×）。
        // matchParentSize 跟随的是**去掉 padding 的内容区** ⇒ 原点仍是卡片左上角，端口坐标不用加偏移。
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = max(NodeGraphMetrics.PORT_RADIUS_PX, NodeGraphMetrics.PORT_RADIUS_MIN_DP * density)
            val ringWidth = max(NodeGraphMetrics.PORT_RING_PX, 1.5f * density)
            portCenters(node).forEach { center ->
                val c = Offset(center.x * density, center.y * density)
                drawCircle(color = accent, radius = radius - ringWidth / 2f, center = c)
                drawCircle(color = ringColor, radius = radius, center = c, style = Stroke(width = ringWidth))
            }
        }
    }
}

/**
 * 长按拖出时的跟手影子块：与托盘预览、落地节点同一个 Composable（spec §8.2），
 * 尺寸随画布当前缩放 [scale]，所以拖动过程和落地结果完全同形。
 */
@Composable
internal fun TrayBlockGhost(type: ProgramNodeType, scale: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // 与托盘预览同一尺寸口径（含左右端口余量），保证影子块 / 预览 / 落地节点三者同形
            .width(NodeGraphMetrics.CARD_W.dp + TrayMetrics.PORT_PAD * 2)
            .height(NodeGraphMetrics.CARD_H.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        TrayBlockFace(type = type, alpha = 0.92f)
    }
}

/**
 * 托盘里的一个块（竖屏行内 / 横屏列内共用）：外观 = [TrayBlockFace]，
 * 点一下 = 落到画布可视区中央，长按 = 拖出去跟手；运行中不可操作、已有开始块时开始块压暗。
 *
 * [blockW] 已含左右端口余量，[scale] 只影响绘制尺寸，不影响落点换算。
 */
@Composable
private fun TrayBlockItem(
    type: ProgramNodeType,
    running: Boolean,
    scale: Float,
    blockW: Dp,
    blockOrigins: MutableMap<ProgramNodeType, Offset>,
    onTapBlock: (ProgramNodeType) -> Unit,
    onDragStart: (ProgramNodeType, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
) {
    val dim = if (running) 0.45f else 1f
    Box(
        modifier = Modifier
            .width(blockW * scale)
            .height(NodeGraphMetrics.CARD_H.dp * scale)
            .onGloballyPositioned { blockOrigins[type] = it.boundsInRoot().topLeft }
            .pointerInput(type, running) {
                if (running) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val origin = blockOrigins[type] ?: Offset.Zero
                    // 阈值内三种结局分开：抬手 = 点一下；移动超 touchSlop / 被列表滚动吃掉 = 让给滚动；
                    // 按住不动满阈值 = 拖出去。小屏托盘要靠滑动看全，所以「快滑 = 滚动」优先。
                    var tapped = false
                    var cancelled = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) {
                                tapped = true
                                return@withTimeoutOrNull
                            }
                            if (ev.changes.any { it.isConsumed } ||
                                (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                        }
                    }
                    if (tapped) {
                        // 阈值内抬手 = 点一下：落到画布可视区中央
                        onTapBlock(type)
                        return@awaitEachGesture
                    }
                    if (cancelled) return@awaitEachGesture
                    // 按住不动满阈值 = 拖出去：影子块从手指处开始跟手
                    var pos = origin + down.position
                    onDragStart(type, pos)
                    val completed = drag(down.id) { change ->
                        change.consume()
                        pos = origin + change.position
                        onDrag(pos)
                    }
                    if (completed) onDragEnd(pos) else onDragCancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(blockW, NodeGraphMetrics.CARD_H.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            TrayBlockFace(type = type, alpha = dim)
        }
    }
}
