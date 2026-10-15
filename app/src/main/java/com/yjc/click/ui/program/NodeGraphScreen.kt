package com.yjc.click.ui.program

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import com.yjc.click.R
import com.yjc.click.program.PROGRAM_COLORS
import com.yjc.click.program.ProgramNode
import com.yjc.click.program.ProgramNodeType
import com.yjc.click.program.WaitMode
import com.yjc.click.ui.theme.ClickText
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 节点图编辑器页面。
 *
 * 这里是「另一个人的程序页」调用的唯一入口，签名固定：
 * ```
 * NodeGraphScreen(programId = ..., onBack = ...)
 * ```
 * 内部按 id 从 [com.yjc.click.program.ProgramStore] 载入程序；载不到就立刻 [onBack] 返回，
 * 不显示任何占位 UI（避免用户看到一屏空白）。
 */
@Composable
fun NodeGraphScreen(
    programId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 载入一次；传 null 表示「这个程序不存在」，直接退回上一页
    val initial = remember(programId) {
        runCatching { com.yjc.click.program.ProgramStore.load(context, programId) }.getOrNull()
    }
    if (initial == null) {
        LaunchedEffect(programId) { onBack() }
        return
    }

    val state = remember(programId) { NodeGraphState(context, initial) }

    // 自动保存：program 一变就写回存储（防抖 400ms，避免拖动节点时每帧写盘）。
    // 撤销 / 重做 / 粘贴 / 参数改动都走同一条路，不需要各自再存一次。
    LaunchedEffect(state.program) {
        kotlinx.coroutines.delay(400)
        state.saveNow()
    }
    // 离开页面时把还没落盘的改动补上
    DisposableEffect(state) {
        onDispose { state.saveNow() }
    }

    // 新建节点菜单：画布落点 + 屏幕落点（菜单要贴着手指弹出）
    var menuAt by remember { mutableStateOf<NewNodeAnchor?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    // 空画布提示：碰一下屏幕就撤掉，不再挡着看
    var hintDismissed by remember(programId) { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 任何一次按下都算「用户已经上手了」：不消费事件，画布照常收到
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(programId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        hintDismissed = true
                    }
                },
        )
        NodeGraphCanvas(
            state = state,
            onRequestNewNode = { canvasPoint, fromId -> menuAt = NewNodeAnchor(canvasPoint, fromId) },
            onConnect = { fromId, toId -> state.connect(fromId, toId) },
            onConnectFailed = { _, reason -> state.toast = reason },
        )

        // 空画布提示（只有开始节点时）
        if (!hintDismissed && state.program.nodes.size <= 1) {
            NodeGraphEmptyHint(
                modifier = Modifier.align(Alignment.Center).width(240.dp),
            )
        }

        // 运行状态：点工具条上的运行按钮 = 放开始球（切到目标应用后点球开始跑），跑起来后按钮变停止
    var running by remember(programId) {
        mutableStateOf(runCatching { com.yjc.click.program.ProgramService.isRunning(context) }.getOrDefault(false))
    }
    LaunchedEffect(programId) {
        while (true) {
            kotlinx.coroutines.delay(600)
            running = runCatching { com.yjc.click.program.ProgramService.isRunning(context) }.getOrDefault(false)
        }
    }

    NodeGraphToolbar(
            title = state.program.name.ifEmpty { stringResource(R.string.node_graph_untitled) },
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            canPaste = state.clipboard.isNotEmpty(),
            canDelete = state.selectedIds.isNotEmpty(),
            running = running,
            onRunToggle = {
                runCatching {
                    if (running) {
                        com.yjc.click.program.ProgramService.stop(context)
                        running = false
                    } else {
                        com.yjc.click.program.ProgramService.showStartBall(context, programId)
                    }
                }
            },
            onBack = onBack,
            onRename = { renameOpen = true },
            onUndo = { state.undo() },
            onRedo = { state.redo() },
            onCopy = { state.copySelected() },
            onPaste = { state.paste() },
            onDelete = { deleteOpen = true },
            modifier = Modifier.zIndex(6f).align(Alignment.TopCenter),
        )

        // 底部提示（连线失败原因 / 不允许删开始节点）
        val toast = state.toast
        if (toast != null) {
            NodeGraphToast(
                text = toast,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (state.singleSelected != null) 300.dp else 96.dp),
            )
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(2600)
                if (state.toast == toast) state.toast = null
            }
        }

        // 底部参数面板：只选中一个节点时升起
        val single = state.singleSelected
        if (single != null) {
            NodeGraphParamsPanel(
                state = state,
                node = single,
                onClose = { state.selectedIds = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // 新建节点菜单
    menuAt?.let { anchor ->
        NewNodeMenu(
            canvasPoint = anchor.canvas,
            onDismiss = { menuAt = null },
            onPick = { type ->
                menuAt = null
                val node = newNode(type, anchor.canvas.x, anchor.canvas.y)
                // 从某个节点的端口拖出来的：建好就自动连上
                state.addNode(node, connectFrom = anchor.fromId)
            },
        )
    }

    // 重命名
    if (renameOpen) {
        RenameDialog(
            initial = state.program.name,
            onDismiss = { renameOpen = false },
            onConfirm = { name ->
                state.rename(name)
                renameOpen = false
            },
        )
    }

    // 删除确认（开始节点永远不会被删掉，这里只确认真正的删除动作）
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = {
                ClickText(
                    stringResource(R.string.node_graph_delete_title),
                    MaterialTheme.typography.headlineSmall.fontSize,
                    MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                ClickText(
                    stringResource(R.string.node_graph_delete_message),
                    MaterialTheme.typography.bodyMedium.fontSize,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    if (!state.deleteSelected()) {
                        state.toast = context.getString(R.string.node_graph_delete_start_locked)
                    }
                }) {
                    ClickText(stringResource(R.string.delete), 14.sp, MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    ClickText(stringResource(R.string.cancel), 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // 取点模式：全屏覆盖层抓触摸
    val pickId = state.pickNodeId
    if (pickId != null) {
        val node = state.program.node(pickId)
        NodeGraphPickOverlay(
            nodeType = node?.type ?: ProgramNodeType.CLICK,
            onPicked = { x, y, endX, endY ->
                if (endX == null || endY == null) {
                    state.applyPick(x, y, null)
                } else {
                    state.applyPick(x, y, endX to endY)
                }
            },
            onCancel = { state.cancelPick() },
        )
    }
}

/** 新建节点菜单的锚点（画布坐标 = 新节点位置）。 */
private data class NewNodeAnchor(val canvas: Offset, val fromId: String)

// ---------------------------------------------------------------- 工具条

/** 顶部工具条：返回 / 程序名（点击重命名）/ 撤销 / 重做 / 复制 / 粘贴 / 删除 / 运行。 */
@Composable
private fun NodeGraphToolbar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    canDelete: Boolean,
    running: Boolean,
    onRunToggle: () -> Unit,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.node_graph_close),
                tint = onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        NodeText(
            text = title,
            fontSize = 15.sp,
            color = onSurface,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRename() }
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
        ToolbarIcon(
            iconRes = R.drawable.ic_undo,
            labelRes = R.string.node_graph_undo,
            enabled = canUndo,
            onClick = onUndo,
        )
        ToolbarIcon(
            iconRes = R.drawable.ic_redo,
            labelRes = R.string.node_graph_redo,
            enabled = canRedo,
            onClick = onRedo,
        )
        // 复制：两个错位叠放的矩形（原来是铅笔图标，用户反馈图标不对）
        ToolbarIcon(
            iconRes = R.drawable.ic_content_copy,
            labelRes = R.string.node_graph_copy,
            enabled = canDelete,
            onClick = onCopy,
        )
        // 粘贴：剪贴板（原来看起来像"下载/导入"）
        ToolbarIcon(
            iconRes = R.drawable.ic_content_paste,
            labelRes = R.string.node_graph_paste,
            enabled = canPaste,
            onClick = onPaste,
        )
        ToolbarIcon(
            iconRes = R.drawable.ic_delete,
            labelRes = R.string.node_graph_delete,
            enabled = canDelete,
            onClick = onDelete,
        )
        ToolbarIcon(
            iconRes = if (running) R.drawable.ic_stop else R.drawable.ic_play,
            labelRes = if (running) R.string.node_graph_stop else R.string.node_graph_run,
            enabled = true,
            onClick = onRunToggle,
        )
    }
}

/** 工具条上的一个图标按钮（禁用时压暗）。 */
@Composable
private fun ToolbarIcon(
    iconRes: Int,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    }
    IconButton(onClick = { if (enabled) onClick() }, enabled = enabled) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(labelRes),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---------------------------------------------------------------- 新建节点菜单

/** 「新建节点」菜单：点击 / 滑动 / 延时 / 等待 / 循环。 */
@Composable
private fun NewNodeMenu(
    canvasPoint: Offset,
    onDismiss: () -> Unit,
    onPick: (ProgramNodeType) -> Unit,
) {
    val types = listOf(
        ProgramNodeType.CLICK,
        ProgramNodeType.SWIPE,
        ProgramNodeType.DELAY,
        ProgramNodeType.WAIT,
        ProgramNodeType.LOOP,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // 点空白关掉菜单
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onDismiss() },
        )
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            modifier = Modifier.width(180.dp),
        ) {
            DropdownMenuItem(
                text = {
                    ClickText(
                        stringResource(R.string.node_graph_new_node),
                        13.sp,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = false,
                onClick = {},
            )
            types.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(programColor(defaultColorFor(type))),
                            )
                            Spacer(Modifier.width(10.dp))
                            ClickText(
                                stringResource(typeNameRes(type)),
                                14.sp,
                                MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    onClick = { onPick(type) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 重命名

/** 重命名程序：输入框弹窗。 */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            ClickText(
                stringResource(R.string.node_graph_rename),
                MaterialTheme.typography.headlineSmall.fontSize,
                MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = {
                    ClickText(
                        stringResource(R.string.rename_field),
                        12.sp,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                ClickText(stringResource(R.string.confirm), 14.sp, MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                ClickText(stringResource(R.string.cancel), 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

// ---------------------------------------------------------------- 取点覆盖层

/**
 * 全屏取点层。
 *
 * 坐标换算：本层铺满整个窗口，所以「窗口坐标」= 本层内坐标；真正的**屏幕坐标**
 * = `LocalView.getLocationOnScreen()` 拿到的窗口偏移 + 层内触摸点（像素）。
 * 点一下 = 取点击坐标；滑一下 = 取滑动起终点（松手即完成）。
 */
@Composable
private fun NodeGraphPickOverlay(
    nodeType: ProgramNodeType,
    onPicked: (x: Int, y: Int, endX: Int?, endY: Int?) -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onPicked)

    var pressPoint by remember { mutableStateOf<Offset?>(null) }
    var currentPoint by remember { mutableStateOf<Offset?>(null) }

    val accent = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    pressPoint = start
                    currentPoint = start
                    var moved = 0f
                    var end = start
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change != null) {
                            moved += (change.position - end).getDistance()
                            end = change.position
                            currentPoint = end
                            change.consume()
                        }
                        if (event.changes.none { it.pressed }) finished = true
                    }
                    pressPoint = null
                    currentPoint = null
                    // 屏幕坐标 = 窗口在屏幕上的偏移 + 窗口内坐标
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val sx = (location[0] + start.x).toInt()
                    val sy = (location[1] + start.y).toInt()
                    val ex = (location[0] + end.x).toInt()
                    val ey = (location[1] + end.y).toInt()
                    if (moved > NodeGraphMetrics.TAP_SLOP_DP * density.density) {
                        latest.value(sx, sy, ex, ey)
                    } else {
                        latest.value(sx, sy, null, null)
                    }
                }
            },
    ) {
        // 顶部说明
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClickText(
                text = stringResource(
                    if (nodeType == ProgramNodeType.SWIPE) {
                        R.string.node_graph_pick_swipe
                    } else {
                        R.string.node_graph_pick_click
                    },
                ),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ClickText(stringResource(R.string.cancel), 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 手指轨迹提示
        val p0 = pressPoint
        val p1 = currentPoint
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (p0 != null && p1 != null && p0 != p1) {
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                }
                drawPath(path, color = accent, style = Stroke(width = 4f))
                drawCircle(color = accent, radius = 12f, center = p0)
                drawCircle(color = accent, radius = 12f, center = p1)
            }
        }
    }
}

// ---------------------------------------------------------------- 参数面板

/**
 * 底部参数面板：选中**单个**节点时升起，任何改动立刻写回存储。
 * 注意所有数值都走「输入框 / 滑块」，改一下就是一步撤销。
 */
@Composable
private fun NodeGraphParamsPanel(
    state: NodeGraphState,
    node: ProgramNode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .shadow(8.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(surface),
    ) {
        // 标题行：类型名 + 步骤 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(programColor(node.color)),
            )
            Spacer(Modifier.width(8.dp))
            ClickText(
                text = stringResource(typeNameRes(node.type)),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.node_graph_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            ColorRow(node = node) { color ->
                state.updateNode(node.id) { it.copy(color = color) }
            }

            when (node.type) {
                ProgramNodeType.START -> {
                    ParamHint(stringResource(R.string.node_graph_start_desc))
                }

                ProgramNodeType.CLICK -> {
                    ParamSectionTitle(stringResource(R.string.node_graph_action_point))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(
                            label = stringResource(R.string.node_graph_point_x),
                            value = node.pointX,
                            onValue = { v -> state.updateNode(node.id) { it.copy(pointX = v) } },
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = stringResource(R.string.node_graph_point_y),
                            value = node.pointY,
                            onValue = { v -> state.updateNode(node.id) { it.copy(pointY = v) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PickButton { state.startPick(node.id) }
                    Spacer(Modifier.height(10.dp))
                    CountRow(
                        label = stringResource(R.string.node_graph_repeat),
                        value = node.repeatCount,
                        onValue = { v -> state.updateNode(node.id) { it.copy(repeatCount = v) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    TextRow(
                        label = stringResource(R.string.node_graph_gesture_name),
                        value = node.gestureName,
                        onValue = { v -> state.updateNode(node.id) { it.copy(gestureName = v) } },
                    )
                }

                ProgramNodeType.SWIPE -> {
                    ParamSectionTitle(stringResource(R.string.node_graph_start_point))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(
                            label = stringResource(R.string.node_graph_point_x),
                            value = node.pointX,
                            onValue = { v -> state.updateNode(node.id) { it.copy(pointX = v) } },
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = stringResource(R.string.node_graph_point_y),
                            value = node.pointY,
                            onValue = { v -> state.updateNode(node.id) { it.copy(pointY = v) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ParamSectionTitle(stringResource(R.string.node_graph_end_point))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(
                            label = stringResource(R.string.node_graph_point_x),
                            value = node.endX,
                            onValue = { v -> state.updateNode(node.id) { it.copy(endX = v) } },
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = stringResource(R.string.node_graph_point_y),
                            value = node.endY,
                            onValue = { v -> state.updateNode(node.id) { it.copy(endY = v) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PickButton { state.startPick(node.id) }
                    Spacer(Modifier.height(10.dp))
                    DurationRow(
                        title = stringResource(R.string.node_graph_duration_ms),
                        valueMs = node.durationMs.toLong(),
                        onValue = { v -> state.updateNode(node.id) { it.copy(durationMs = v.toInt()) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    CountRow(
                        label = stringResource(R.string.node_graph_repeat),
                        value = node.repeatCount,
                        onValue = { v -> state.updateNode(node.id) { it.copy(repeatCount = v) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    TextRow(
                        label = stringResource(R.string.node_graph_gesture_name),
                        value = node.gestureName,
                        onValue = { v -> state.updateNode(node.id) { it.copy(gestureName = v) } },
                    )
                }

                ProgramNodeType.DELAY -> {
                    ParamSectionTitle(stringResource(R.string.node_graph_delay_ms))
                    DurationRow(
                        title = "",
                        valueMs = node.delayMs,
                        onValue = { v -> state.updateNode(node.id) { it.copy(delayMs = v) } },
                    )
                }

                ProgramNodeType.WAIT -> {
                    ParamSectionTitle(stringResource(R.string.node_graph_wait_mode))
                    WaitModeRow(
                        mode = node.waitMode,
                        onPick = { mode ->
                            state.updateNode(node.id) {
                                it.copy(waitMode = mode, waitUntilMs = if (mode == WaitMode.USER) 0L else it.waitUntilMs)
                            }
                        },
                    )
                    if (node.waitMode == WaitMode.TIME) {
                        Spacer(Modifier.height(10.dp))
                        ParamSectionTitle(stringResource(R.string.node_graph_wait_until))
                        ClockRow(
                            valueMs = node.waitUntilMs,
                            onChange = { v -> state.updateNode(node.id) { it.copy(waitUntilMs = v) } },
                        )
                    }
                }

                ProgramNodeType.LOOP -> {
                    ParamSectionTitle(stringResource(R.string.node_graph_loop_target))
                    LoopTargetRow(
                        state = state,
                        node = node,
                    )
                    Spacer(Modifier.height(10.dp))
                    CountRow(
                        label = stringResource(R.string.node_graph_loop_count),
                        value = node.loopCount,
                        onValue = { v -> state.updateNode(node.id) { it.copy(loopCount = v) } },
                    )
                    ParamHint(stringResource(R.string.node_graph_loop_infinite))
                }
            }
        }
    }
}

/** 小节标题。 */
@Composable
private fun ParamSectionTitle(text: String) {
    ClickText(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

/** 一行说明。 */
@Composable
private fun ParamHint(text: String) {
    ClickText(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 颜色行：从 [PROGRAM_COLORS] 里选一个（只有 START 不允许改，其余都能改）。 */
@Composable
private fun ColorRow(node: ProgramNode, onPick: (Int) -> Unit) {
    Column {
        ParamSectionTitle(stringResource(R.string.node_graph_color))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PROGRAM_COLORS.forEach { color ->
                val selected = color == node.color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

/** 「取点」按钮。 */
@Composable
private fun PickButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        ClickText(
            text = stringResource(R.string.node_graph_pick_point),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 数字输入框（失焦 / 合法时才写回，避免中途输入把值清成 0）。 */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = modifier) {
        ClickText(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() || (allowNegative && it == '-') }
                    .take(9)
                text = filtered
                filtered.toIntOrNull()?.let(onValue)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 重复次数 / 循环次数：滑块 + 数字（0 对循环来说代表无限）。 */
@Composable
private fun CountRow(label: String, value: Int, onValue: (Int) -> Unit) {
    var live by remember(value) {
        mutableStateOf(value.coerceIn(0, 99).toFloat())
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClickText(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ClickText(
                text = live.toInt().toString(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onValue(live.toInt()) },
            valueRange = 0f..20f,
            steps = 19,
        )
    }
}

/** 延时 / 时长：滑块（0.1s～30s）+ 数字输入。 */
@Composable
private fun DurationRow(title: String, valueMs: Long, onValue: (Long) -> Unit) {
    var live by remember(valueMs) {
        mutableStateOf(valueMs.coerceIn(0L, 30_000L).toFloat())
    }
    Column {
        if (title.isNotEmpty()) {
            ParamSectionTitle(title)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = live,
                onValueChange = { live = it },
                onValueChangeFinished = { onValue(live.toInt().toLong()) },
                valueRange = 0f..30_000f,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NumberField(
                label = "",
                value = live.toInt(),
                onValue = {
                    live = it.coerceIn(0, 30_000).toFloat()
                    onValue(it.toLong())
                },
                modifier = Modifier.width(86.dp),
            )
        }
    }
}

/** 单行文字输入（手势名）。 */
@Composable
private fun TextRow(label: String, value: String, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column {
        ClickText(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** WAIT 的等待方式：等用户点球 / 等到时刻。 */
@Composable
private fun WaitModeRow(mode: WaitMode, onPick: (WaitMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentChip(
            text = stringResource(R.string.node_graph_wait_user),
            selected = mode == WaitMode.USER,
            onClick = { onPick(WaitMode.USER) },
        )
        SegmentChip(
            text = stringResource(R.string.node_graph_wait_time),
            selected = mode == WaitMode.TIME,
            onClick = { onPick(WaitMode.TIME) },
        )
    }
}

/** 一个可选中的小胶囊。 */
@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        ClickText(text = text, fontSize = 13.sp, color = fg)
    }
}

/** 时刻选择：时 / 分两个数字框，写回「当天毫秒数」[ProgramNode.waitUntilMs]。 */
@Composable
private fun ClockRow(valueMs: Long, onChange: (Long) -> Unit) {
    val total = ((valueMs % 86_400_000L) + 86_400_000L) % 86_400_000L
    var hour by remember(valueMs) { mutableStateOf((total / 3_600_000L).toInt().toString()) }
    var minute by remember(valueMs) { mutableStateOf(((total % 3_600_000L) / 60_000L).toInt().toString()) }

    fun push(h: String, m: String) {
        val hh = (h.toIntOrNull() ?: 0).coerceIn(0, 23)
        val mm = (m.toIntOrNull() ?: 0).coerceIn(0, 59)
        onChange(hh * 3_600_000L + mm * 60_000L)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ClockField(
            label = stringResource(R.string.node_graph_hour),
            value = hour,
            onValue = {
                hour = it
                push(it, minute)
            },
        )
        Spacer(Modifier.width(8.dp))
        ClickText(text = ":", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        ClockField(
            label = stringResource(R.string.node_graph_minute),
            value = minute,
            onValue = {
                minute = it
                push(hour, it)
            },
        )
    }
}

/** 时刻的一个数字框（朴素下划线输入，避免 Material 输入框在窄行里过高）。 */
@Composable
private fun ClockField(label: String, value: String, onValue: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { raw -> onValue(raw.filter { it.isDigit() }.take(2)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ClickText(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** LOOP 的回跳目标下拉：列出图中其它节点（含开始）。 */
@Composable
private fun LoopTargetRow(state: NodeGraphState, node: ProgramNode) {
    var expanded by remember { mutableStateOf(false) }
    val options = state.program.nodes.filter { it.id != node.id }
    val current = options.firstOrNull { it.id == node.loopTargetId }
    val steps = remember(state.program) { stepIndices(state.program) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NodeText(
                text = current?.let { nodeLabel(it, steps[it.id]) }
                    ?: stringResource(R.string.node_graph_new_node),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        ClickText(
                            nodeLabel(option, steps[option.id]),
                            14.sp,
                            MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        state.updateNode(node.id) { it.copy(loopTargetId = option.id) }
                    },
                )
            }
        }
    }
}

/** 下拉里的节点显示名：类型名 + 步骤（或坐标）。 */
@Composable
private fun nodeLabel(node: ProgramNode, step: Int?): String {
    val type = stringResource(typeNameRes(node.type))
    return if (node.type == ProgramNodeType.START) {
        type
    } else if (step != null) {
        type + " · " + stringResource(R.string.node_graph_step, step)
    } else {
        type + " · " + node.x.toInt() + "," + node.y.toInt()
    }
}
