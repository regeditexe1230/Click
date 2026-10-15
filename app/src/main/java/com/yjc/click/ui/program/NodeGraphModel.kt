package com.yjc.click.ui.program

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.yjc.click.R
import com.yjc.click.program.PROGRAM_COLORS
import com.yjc.click.program.Program
import com.yjc.click.program.EdgeError
import com.yjc.click.program.ProgramEdge
import com.yjc.click.program.ProgramNode
import com.yjc.click.program.ProgramNodeType
import com.yjc.click.program.ProgramStore
import com.yjc.click.program.WaitMode

/**
 * 节点图编辑器的画布尺寸常量（画布单位 = dp@1×）。
 *
 * [ProgramNode.x] / [ProgramNode.y] 与这些常量都在同一套「画布单位」里：**1 画布单位 = 1 dp**。
 * 落到屏幕上还要再过两道换算：`屏幕像素 = 画布单位 × density × 画布缩放 [NodeGraphState.scale]`。
 * 所以节点坐标只能按 `.dp` 去布局、绘制时要乘 density —— 一旦某一处按像素算（例如 `.toDp()`），
 * 卡片、端口、连线和命中判定就会各自错开 density 倍。
 */
internal object NodeGraphMetrics {
    /** 节点卡片宽。 */
    const val CARD_W = 132f

    /** 节点卡片高。 */
    const val CARD_H = 84f

    /** 卡片左侧色条宽。 */
    const val BAR_W = 8f

    /** 卡片圆角。 */
    const val CARD_RADIUS = 12f

    /** 接线柱（端口圆点）半径（屏幕像素）：不随画布缩放变化，缩到 0.5× 也一样大。 */
    const val PORT_RADIUS_PX = 8f

    /** 接线柱半径的屏幕下限（屏幕 dp）：高密度屏上 8px 太细，按 dp 兜一个底。 */
    const val PORT_RADIUS_MIN_DP = 5.5f

    /** 选中时接线柱的放大倍数（8px → 9.6px）。 */
    const val PORT_SELECTED_SCALE = 1.2f

    /** 接线柱描边宽度（屏幕像素）。 */
    const val PORT_RING_PX = 2.5f

    /** 同一侧有多个端口时，竖直排列距离卡片上下边的留白（画布单位 = dp）。 */
    const val PORT_MARGIN = 14f

    /** 端口触摸命中半径（屏幕 dp，不随缩放变化，手指才好按）。 */
    const val PORT_TOUCH_DP = 18f

    /** 画布最小 / 最大缩放。 */
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 3f

    /** 拖动时判定「点了空白处」的位移阈值（屏幕 dp）。 */
    const val TAP_SLOP_DP = 8f

    /** 长按空白处进入框选的时间（毫秒）。 */
    /** 空白处按住多久才算框选（比系统长按 500ms 长，免得和双指缩放抢手势） */
    const val LONG_PRESS_MS = 650L

    /** 拖线落点采样：节点命中判定用的外扩量（屏幕 dp）。 */
    const val DROP_PADDING_DP = 12f
}

/** 画布上的一次手势在做什么。 */
private enum class NodeGraphGesture { NONE, PAN, NODE, MARQUEE, PORT }

/**
 * 节点图编辑器的全部可变状态。
 *
 * 撤销/重做用最简单的「整份 Program 快照栈」：任何一次改动前把旧快照压进 [undoStack]，
 * 撤销就是弹出来当作当前值（并把当前值压进 [redoStack]）。栈上限 50 步。
 */
internal class NodeGraphState(val context: Context, initial: Program) {

    /** 当前程序（画布上看到的一切都由它推导）。撤销 / 重做会整体替换它。 */
    var program: Program by mutableStateOf(initial)

    /** 选中的节点 id（单选 = 1 个；多选 = 多个）。页面内部直接改写。 */
    var selectedIds: Set<String> by mutableStateOf(emptySet())

    /** 剪贴板（只在本页生命周期内有效，粘贴时会重新分配 id）。 */
    var clipboard: List<ProgramNode> by mutableStateOf(emptyList())
        private set

    /** 正在取点的节点 id；null = 不在取点模式。 */
    var pickNodeId: String? by mutableStateOf(null)
        private set

    /** 底部一行提示（连线失败原因 / 不允许删开始节点）。 */
    var toast: String? by mutableStateOf(null)

    // ---- 画布视图 ----

    /** 画布缩放（0.3× ～ 3×）。 */
    var scale: Float by mutableStateOf(1f)

    /**
     * 画布平移（**屏幕像素**）。屏幕点 → 画布点 = `(p - translation) / (scale * density)`，
     * 见 [toCanvas]。
     */
    var translation: Offset by mutableStateOf(Offset.Zero)

    // ---- 撤销 / 重做 ----

    private val undoStack = ArrayDeque<Program>()
    private val redoStack = ArrayDeque<Program>()

    /** 撤销栈里是否已有「本次连续手势」的快照（拖节点 / 拖动数值时避免每一帧都压栈）。 */
    private var undoPushedForGesture = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    init {
        // 传进来的是别人加载好的对象，统一以存储里的最新版本为准（同一页面被重新创建时不会丢改动）
        program = runCatching { ProgramStore.load(context, initial.id) }.getOrNull() ?: initial
    }

    /** 选中集合对应的节点（按 program 里的顺序）。 */
    val selectedNodes: List<ProgramNode> get() = program.nodes.filter { it.id in selectedIds }
    /** 单选的那个节点；多选或没选时为 null。 */
    val singleSelected: ProgramNode? get() = selectedNodes.singleOrNull()

    /** 当前选中的节点（拖线起点等）取第一个。 */
    fun primarySelected(): ProgramNode? = selectedNodes.firstOrNull()

    // ---------------- 自动保存 ----------------

    /**
     * 立刻把当前 program 写回存储（updatedAt 由 ProgramStore 自己盖章）。
     *
     * 页面用「program 一变就防抖 400ms 保存」的方式调用它：
     * 离散操作（加/删/连线/改参数）立刻可见地存下去，拖动过程中也不会每帧写盘。
     * 失败不抛异常 —— 编辑器不该因为存储问题崩掉。
     */
    fun saveNow() {
        runCatching { ProgramStore.save(context, program) }
    }

    // ---------------- 撤销 / 重做 ----------------

    /** 改动前压一份快照（离散操作：加节点、连线、改参数、删除…）。 */
    private fun pushUndo() {
        undoStack.addLast(program)
        while (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        undoPushedForGesture = false
    }

    /** 一次连续手势（拖动节点 / 拖滑块）只压一次快照。 */
    fun beginGesture() {
        if (!undoPushedForGesture) {
            undoStack.addLast(program)
            while (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        }
        undoPushedForGesture = true
    }

    fun endGesture() {
        undoPushedForGesture = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(program)
        if (redoStack.size > 50) redoStack.removeFirst()
        program = prev
        selectedIds = selectedIds.filter { id -> program.nodes.any { it.id == id } }.toSet()
        undoPushedForGesture = false
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(program)
        if (undoStack.size > 50) undoStack.removeFirst()
        program = next
        selectedIds = selectedIds.filter { id -> program.nodes.any { it.id == id } }.toSet()
        undoPushedForGesture = false
    }

    // ---------------- 改动 ----------------

    /** 离散改动：先压快照，再写回存储。 */
    fun perform(block: (Program) -> Program) {
        pushUndo()
        apply(block)
    }

    /** 连续手势中的改动：不压快照（手势开始时已经 [beginGesture] 过一次）。 */
    fun apply(block: (Program) -> Program) {
        program = block(program)
        if (selectedIds.isNotEmpty()) {
            selectedIds = selectedIds.filter { id -> program.nodes.any { it.id == id } }.toSet()
        }
    }

    fun addNode(node: ProgramNode, connectFrom: String?) {
        perform { p ->
            val start = p.start
            val edge = when {
                // 从某个节点的端口拖到空白处新建：直接连上这一条
                connectFrom != null && p.node(connectFrom) != null -> ProgramEdge(connectFrom, node.id)
                // 否则：开始节点长出来的第一条线自动接上，免得用户还要自己连一次
                connectFrom == null && start != null && p.edges.isEmpty() && node.type != ProgramNodeType.START ->
                    ProgramEdge(start.id, node.id)
                else -> null
            }
            p.copy(
                nodes = p.nodes + node,
                edges = if (edge == null) p.edges else p.edges + edge,
            )
        }
        selectedIds = setOf(node.id)
    }

    /** 连线；返回失败原因（null = 成功）。失败时目标节点会红一下，不写回。 */
    fun connect(fromId: String, toId: String): EdgeError? {
        val err = program.connectError(fromId, toId)
        if (err != null) return err
        perform { it.copy(edges = it.edges + ProgramEdge(fromId, toId)) }
        return null
    }

    fun updateNode(id: String, block: (ProgramNode) -> ProgramNode) {
        // 改参数用 perform：每改一次就是一步，撤销能一步步退
        perform { p -> p.copy(nodes = p.nodes.map { if (it.id == id) block(it) else it }) }
    }

    /** 连续拖动中的参数改动（滑块）：不压快照。 */
    fun updateNodeLive(id: String, block: (ProgramNode) -> ProgramNode) {
        apply { p -> p.copy(nodes = p.nodes.map { if (it.id == id) block(it) else it }) }
    }

    /** 拖动选中的节点：把快照之外的位置累积进来（拖动开始时已 beginGesture）。 */
    fun moveSelected(dx: Float, dy: Float) {
        apply { p ->
            p.copy(nodes = p.nodes.map {
                if (it.id in selectedIds) it.copy(x = it.x + dx, y = it.y + dy) else it
            })
        }
    }

    /** 拖某个节点时的实时位置（只动它一个，用于拖线时的跟随）。 */
    fun moveNodeLive(id: String, dx: Float, dy: Float) {
        apply { p -> p.copy(nodes = p.nodes.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }) }
    }

    /** 删除选中的节点（START 永远不删），连带删掉相关连线。返回是否真的删了东西。 */
    fun deleteSelected(): Boolean {
        val ids = selectedIds.filter { id -> program.nodes.firstOrNull { it.id == id }?.type != ProgramNodeType.START }
        if (ids.isEmpty()) return false
        perform { p ->
            p.copy(
                nodes = p.nodes.filterNot { it.id in ids },
                edges = p.edges.filterNot { it.fromId in ids || it.toId in ids },
            )
        }
        selectedIds = emptySet()
        return true
    }

    // ---------------- 复制 / 粘贴 ----------------

    fun copySelected() {
        val nodes = selectedNodes
        if (nodes.isEmpty()) return
        clipboard = nodes
        toast = null
    }

    /**
     * 粘贴：整体偏移 (40, 40)，全部换新 id。
     * LOOP 的 loopTargetId 要跟着重映射：目标也在这次粘贴里就指向新 id，否则置 null（防悬空引用）。
     */
    fun paste() {
        val src = clipboard
        if (src.isEmpty()) return
        val idMap = src.associate { it.id to ProgramNode.newId() }
        val copies = src.map { n ->
            n.copy(
                id = idMap.getValue(n.id),
                x = n.x + 40f,
                y = n.y + 40f,
                loopTargetId = n.loopTargetId?.let { idMap[it] },
            )
        }
        perform { p -> p.copy(nodes = p.nodes + copies) }
        selectedIds = copies.map { it.id }.toSet()
    }

    // ---------------- 取点 ----------------

    fun startPick(id: String) {
        pickNodeId = id
        toast = null
    }

    fun cancelPick() {
        pickNodeId = null
    }

    /**
     * 取点完成：写回屏幕坐标。
     * `end == null` 表示只点了一下（给 CLICK 取点），否则是一次滑动（给 SWIPE 取起终点）。
     */
    fun applyPick(x: Int, y: Int, end: Pair<Int, Int>?) {
        val id = pickNodeId ?: return
        perform { p ->
            p.copy(nodes = p.nodes.map { n ->
                if (n.id != id) return@map n
                if (end == null) {
                    when (n.type) {
                        ProgramNodeType.SWIPE -> n.copy(pointX = x, pointY = y, endX = x, endY = y, path = emptyList())
                        else -> n.copy(pointX = x, pointY = y)
                    }
                } else {
                    n.copy(
                        pointX = x,
                        pointY = y,
                        endX = end.first,
                        endY = end.second,
                        path = emptyList(),
                        type = if (n.type == ProgramNodeType.CLICK) ProgramNodeType.SWIPE else n.type,
                    )
                }
            })
        }
        pickNodeId = null
    }

    // ---------------- 程序名 ----------------

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        perform { it.copy(name = trimmed) }
    }
}

/**
 * 屏幕坐标（像素）→ 画布坐标：`(p - translation) / (scale * density)`。
 *
 * [density] 传 `LocalDensity.current.density`：画布单位是 dp@1×，屏幕上 1 画布单位 = density 像素。
 */
internal fun NodeGraphState.toCanvas(p: Offset, density: Float): Offset =
    (p - translation) / (scale * density)

/** 画布坐标 → 屏幕坐标（像素）：`p * density * scale + translation`。 */
internal fun NodeGraphState.toScreen(p: Offset, density: Float): Offset =
    p * (density * scale) + translation

/** 把 0xAARRGGBB 的 Int 换成 Compose 的 [Color]。 */
internal fun programColor(value: Int): Color = Color(value)

/** 节点类型对应的名字资源。 */
internal fun typeNameRes(type: ProgramNodeType): Int = when (type) {
    ProgramNodeType.START -> R.string.node_graph_type_start
    ProgramNodeType.CLICK -> R.string.node_graph_type_click
    ProgramNodeType.SWIPE -> R.string.node_graph_type_swipe
    ProgramNodeType.DELAY -> R.string.node_graph_type_delay
    ProgramNodeType.WAIT -> R.string.node_graph_type_wait
    ProgramNodeType.LOOP -> R.string.node_graph_type_loop
}

/** 节点类型的默认配色（START 固定主题紫，其余按类型取 [PROGRAM_COLORS] 里固定的一个）。 */
internal fun defaultColorFor(type: ProgramNodeType): Int = when (type) {
    ProgramNodeType.START -> PROGRAM_COLORS[0]
    ProgramNodeType.CLICK -> PROGRAM_COLORS[6]
    ProgramNodeType.SWIPE -> PROGRAM_COLORS[5]
    ProgramNodeType.DELAY -> PROGRAM_COLORS[3]
    ProgramNodeType.WAIT -> PROGRAM_COLORS[2]
    ProgramNodeType.LOOP -> PROGRAM_COLORS[7]
}

/** 新建节点时按类型填的默认参数。 */
internal fun newNode(type: ProgramNodeType, canvasX: Float, canvasY: Float): ProgramNode = ProgramNode(
    id = ProgramNode.newId(),
    type = type,
    x = canvasX,
    y = canvasY,
    color = defaultColorFor(type),
    durationMs = 300,
    delayMs = 1000,
    waitMode = WaitMode.USER,
    waitUntilMs = 0L,
    repeatCount = 1,
    loopCount = 1,
)

/** 节点矩形（画布坐标）。 */
internal fun nodeRect(node: ProgramNode): Rect =
    Rect(node.x, node.y, node.x + NodeGraphMetrics.CARD_W, node.y + NodeGraphMetrics.CARD_H)

// ---------------------------------------------------------------- 接线柱（端口）

/**
 * 输入端口数量：START 没有输入（谁都不能连到开始节点），其余节点各 1 个。
 * LOOP 虽然能回跳，但它也是「一个入线」，多入线由连线校验去挡。
 */
internal fun inputPortCount(node: ProgramNode): Int =
    if (node.type == ProgramNodeType.START) 0 else 1

/** 输出端口数量：目前每个节点只允许一条出线，所以恒为 1。 */
internal fun outputPortCount(node: ProgramNode): Int = 1

/** 节点上的端口总数（输入在前、输出在后，序号从 0 开始）。 */
internal fun portCount(node: ProgramNode): Int = inputPortCount(node) + outputPortCount(node)

/**
 * 第 [index] 个端口的圆心（画布坐标）。端口贴在卡片左右边缘上：
 * 左边是输入、右边是输出；同一侧有多个端口时沿卡片高度**等距排列、整体居中**，
 * 只有一个端口时就落在卡片竖直中线上。
 */
internal fun portCenter(node: ProgramNode, index: Int): Offset {
    val inputs = inputPortCount(node)
    return if (index < inputs) {
        Offset(node.x, portSideY(node, index, inputs))
    } else {
        Offset(
            node.x + NodeGraphMetrics.CARD_W,
            portSideY(node, index - inputs, outputPortCount(node)),
        )
    }
}

/** 输出端口（拖线起点）的圆心。 */
internal fun outputPortCenter(node: ProgramNode): Offset = portCenter(node, inputPortCount(node))

/** 输入端口（连线终点）的圆心；START 没有输入端口，退回卡片左侧中线。 */
internal fun inputPortCenter(node: ProgramNode): Offset =
    if (inputPortCount(node) == 0) {
        Offset(node.x, node.y + NodeGraphMetrics.CARD_H / 2f)
    } else {
        portCenter(node, 0)
    }

/** 节点上全部端口的圆心（顺序与 [portCenter] 一致），绘制时直接用。 */
internal fun portCenters(node: ProgramNode): List<Offset> = List(portCount(node)) { portCenter(node, it) }

/** 同一侧第 [indexInSide] 个端口的 y：单个居中，多个等距。 */
private fun portSideY(node: ProgramNode, indexInSide: Int, countInSide: Int): Float {
    val mid = node.y + NodeGraphMetrics.CARD_H / 2f
    if (countInSide <= 1) return mid
    val margin = NodeGraphMetrics.PORT_MARGIN
    val step = (NodeGraphMetrics.CARD_H - margin * 2f) / (countInSide - 1).toFloat()
    return node.y + margin + step * indexInSide
}

/** 某点（画布坐标）命中的节点，后画的压在上面，所以倒序遍历。 */
internal fun hitTest(program: Program, canvasPoint: Offset, padding: Float = 0f): ProgramNode? {
    for (i in program.nodes.indices.reversed()) {
        val n = program.nodes[i]
        if (nodeRect(n).inflate(padding).contains(canvasPoint)) return n
    }
    return null
}

/** 框选：与矩形相交的节点（框往外扩一点更好点）。 */
internal fun nodesInRect(program: Program, rect: Rect, padding: Float = 0f): List<ProgramNode> {
    val r = if (padding == 0f) rect else rect.inflate(padding)
    return program.nodes.filter { nodeRect(it).overlaps(r) }
}

/**
 * 从开始节点按连线顺序算出的步骤序号（1、2、3…）。
 * 广度优先，每个节点只记第一次到达的序号；START 自己不在结果里（卡片上直接显示「开始」）。
 */
internal fun stepIndices(program: Program): Map<String, Int> {
    val start = program.start ?: return emptyMap()
    val out = HashMap<String, Int>(program.nodes.size)
    val visited = HashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(start.id)
    visited.add(start.id)
    var step = 1
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        for (edge in program.outEdges(id)) {
            if (!visited.add(edge.toId)) continue
            val target = program.node(edge.toId) ?: continue
            if (target.type == ProgramNodeType.START) continue
            out[target.id] = step++
            queue.add(target.id)
        }
    }
    return out
}
