package com.yjc.click.ui.program

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt

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

    // 第一次进节点图（也就是第一次点开一个程序）弹一次使用说明，之后不再弹。
    NodeGraphGuideEffect()

    // 新建节点菜单已经整体删掉：取块改走底部积木托盘（docs/node-tray-spec.md §1）
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    // 空画布提示：碰一下屏幕就撤掉，不再挡着看
    var hintDismissed by remember(programId) { mutableStateOf(false) }

    // ---------------------------------------------------------------- 底部积木托盘

    // 展开/收起跨会话记在 prefs 里（默认展开）
    var trayExpanded by remember(programId) {
        mutableStateOf(
            runCatching { com.yjc.click.program.ProgramStore.isTrayExpanded(context) }.getOrDefault(true),
        )
    }
    // 托盘尺寸 = 屏高/屏宽的 1/4：用户口径是**整块屏**（本机竖屏 3200px → 800px；横屏取屏宽）。
    // 取 Configuration 的屏幕尺寸（dp × density）算长边：根 Box 会被宿主裁掉系统栏那一条
    // （模拟器横屏 root.width=2274 ⇒ 568，比 600 小；竖屏 root.height=2337 + 状态栏 63 才对），
    // 而 `LocalWindowInfo.containerSize` 在本工程的 Compose 版本上不存在，所以直接取屏幕长边。
    val density = LocalDensity.current
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val horizontal = rootSize.width > 0 && rootSize.height > 0 && rootSize.width > rootSize.height
    // 窗口长边（= 托盘尺寸的分母）：取 **Display.getSize** 的「应用区」——它排除了 3 键导航条，
    // 而 `getRealMetrics` 是物理整屏（2400）、Compose 根 Box 横屏会报 2400 宽（含被裁部分），
    // 两者都会让托盘伸进导航栏、被裁掉一条（验收里 474 那个数就是这么来的）。
    // 手势导航的手机上应用区就是整屏 ⇒ 仍得 800px，与既有口径一致。
    val windowLongSidePx = remember {
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getSize(point)
        maxOf(point.x, point.y).toFloat()
    }
    val longSidePx = if (windowLongSidePx > 0f) windowLongSidePx else maxOf(rootSize.width, rootSize.height).toFloat()
    // 展开尺寸：竖屏 = 屏高 1/4（本机 800px）；横屏 = 屏宽 1/4（模拟器 2400 ⇒ 600px = 228.6dp）
    val trayExpandedSize = with(density) { (longSidePx * TrayMetrics.EXPANDED_FRACTION).toDp() }
    val traySize = if (trayExpanded) trayExpandedSize else TrayMetrics.COLLAPSED_HEIGHT
    // 托盘的**实时**尺寸（拖动/回弹过程中逐帧变化）：参数面板贴着它走，不能等档位切换才跳
    var trayLiveSize by remember { mutableStateOf(traySize) }
    // 横屏参数栏：展开/收起 + 进场/退场。工具条右端、画布可用区、参数栏自身宽度全部由下面
    // 这两个**当前值**（不是动画目标值）算出来 ⇒ 同一帧一起动，不会「工具条先到、参数栏后到」。
    // 横屏参数栏不再有「拖柄 / 收起档」：它和竖屏一样，只选中一个节点时出现、关掉就整块滑出去。
    // 唯一保留的动画是宽度弹簧（开 137.5dp / 关 0dp），工具条右端和画布可用区都读这**同一个值**
    // ⇒ 三者同一帧一起动，关掉后也不会在托盘旁边留下一块「给参数栏预留」的空白。
    // 长宽对调：宽 = 竖屏那份的高度 361px ≈ 137.5dp（模拟器 1080×2400 / 2.625）。
    val panelExpandedWidth = 137.5.dp
    // 参数栏右侧要「伸到托盘底下」的深度：正好等于托盘圆角半径（28dp）。
    // 不伸进去的话，参数栏的直角边和托盘的圆角之间会露一条画布色的月牙缝（用户明确指出的丑点）。
    val panelTuck = 28.dp
    val panelLiveWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (state.selectedIds.size == 1) panelExpandedWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "panelWidth",
    )


    // 横屏时系统导航栏在屏幕右侧占一条（模拟器 48dp），而 overlay 的「右沿」是屏幕右沿（2400），
    // 托盘自己内部已让过这条（可见右沿 2274）。参数栏/工具条对着 overlay 右沿对齐时必须同样让掉它，
    // 否则会统一往右偏 navRight，压到托盘身上。竖屏 navEnd = 0，一行不影响。
    val navEndPx = WindowInsets.navigationBars.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
    val navEndDp = with(density) { navEndPx.toDp() }

    // 根容器右沿 / 托盘左沿（都是根坐标实测值）。
    // 托盘自己内部还会让一次系统手势区（navInset），而 insets 在不同机器上报的值并不一样
    // （模拟器 3 键导航 48dp、手机手势 16dp），用「屏宽 - 托盘宽 - 估算 inset」去反推工具条右端，
    // 真机上就会差一条 ⇒ 工具条右角被托盘盖住（用户报的「跑到托盘下面」）。
    // 改成直接用两者的实测边界相减，任何 inset 组合都对得上。
    var rootRightPx by remember { mutableStateOf(0f) }
    var trayLeftPx by remember { mutableStateOf(0f) }
    val trayInsetDp = with(density) {
        if (rootRightPx > 0f && trayLeftPx > 0f) (rootRightPx - trayLeftPx).toDp() else trayLiveSize + navEndDp
    }

    // 正在从托盘里拖的块 + 手指位置（根坐标）：影子块跟着手指走
    var dragType by remember { mutableStateOf<ProgramNodeType?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    // 画布（托盘之上那块可视区）在根坐标里的位置与大小：落点换算要用实测值
    var canvasTopLeft by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    /** 根坐标 → 画布坐标。影子和真正落下的节点靠这一条对齐（spec §5）。 */
    fun toCanvasPoint(root: Offset): Offset = state.toCanvas(root - canvasTopLeft, density.density)

    /** 把一个块放到画布上；[root] 是根坐标下的落点，也是卡片的**中心**（影块就是这么跟手的）。 */
    fun placeBlock(type: ProgramNodeType, root: Offset) {
        val p = toCanvasPoint(root)
        // 以落点为中心放卡片：影块是居中的，落地后位置才对得上（spec §5，容差 ≤ 一个卡片对角）
        state.addNode(
            newNode(
                type,
                p.x - NodeGraphMetrics.CARD_W / 2f,
                p.y - NodeGraphMetrics.CARD_H / 2f,
            ),
            connectFrom = null,
        )
    }

    /** 点一下 chip：落在画布可视区（托盘之上）的中央。 */
    fun tapPlaceBlock(type: ProgramNodeType) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        placeBlock(
            type,
            Offset(
                canvasTopLeft.x + canvasSize.width / 2f,
                canvasTopLeft.y + canvasSize.height / 2f,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .onGloballyPositioned { rootRightPx = it.boundsInRoot().right }
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 状态栏那一条：显式铺一层和画布同色的底，不让上层容器的底色（surfaceVariant）透出来。
        // 画布/点阵是否真的钻到状态栏下面另说，至少这一条和画布颜色一致，看起来就是「透明」。
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surface),
        )
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
        // 画布只铺「托盘之外」那一块：竖屏让出底部托盘，横屏让出右侧侧栏。
        // 托盘区域天然不会把手势漏给画布（不会边拖块边平移），空提示也按可视区居中。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (horizontal) {
                        // 横屏：让出「托盘 + 参数栏」的实时宽度。参数栏关掉时 panelLiveWidth 归 0
                        // ⇒ 画布（含点阵背景）一直铺到托盘左沿，不会残留一条给参数栏的空带。
                        Modifier.padding(end = trayInsetDp + panelLiveWidth)
                    } else {
                        // 竖屏：保持原来的**离散**避让口径（档位切换才变），一个像素都不变
                        Modifier.padding(bottom = traySize)
                    },
                )
                .onGloballyPositioned {
                    canvasTopLeft = it.boundsInRoot().topLeft
                    canvasSize = it.size
                },
        ) {
            NodeGraphCanvas(
                state = state,
                onConnect = { fromId, toId -> state.connect(fromId, toId) },
                onConnectFailed = { _, reason -> state.toast = reason },
            )

            // 空画布提示（新程序就是空画布：一个节点都没有）
            if (!hintDismissed && state.program.nodes.isEmpty()) {
                NodeGraphEmptyHint(
                    modifier = Modifier.align(Alignment.Center).width(240.dp),
                )
            }
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

        // 底部积木托盘：常驻，展开态占屏高 1/4
        NodeGraphTray(
            expanded = trayExpanded,
            expandedSize = trayExpandedSize,
            running = running,
            horizontal = horizontal,
            onSizeChange = { trayLiveSize = it },
            onBoundsChange = { trayLeftPx = it.left },
            onExpandedChange = { expanded ->
                trayExpanded = expanded
                runCatching { com.yjc.click.program.ProgramStore.setTrayExpanded(context, expanded) }
            },
            onTapBlock = { type -> tapPlaceBlock(type) },
            onDragStart = { type, pos ->
                dragType = type
                dragPos = pos
            },
            onDrag = { pos -> dragPos = pos },
            onDragEnd = { pos ->
                val type = dragType
                dragType = null
                // 手指还在画布可视区里才算数：拖回托盘 / 拖出边界都什么都不创建（spec §5）
                val insideX = pos.x >= canvasTopLeft.x && pos.x <= canvasTopLeft.x + canvasSize.width
                val insideY = pos.y >= canvasTopLeft.y && pos.y <= canvasTopLeft.y + canvasSize.height
                if (type != null && insideX && insideY) placeBlock(type, pos)
            },
            onDragCancel = { dragType = null },
            // 尺寸由托盘自己按档位算（竖屏=高、横屏=宽），这里只管贴底/贴右与层级
            modifier = Modifier
                .align(if (horizontal) Alignment.CenterEnd else Alignment.BottomCenter)
                // 托盘压在参数栏之上（用户口径：参数栏从托盘下面/侧面滑出，被托盘压住一部分）
                .zIndex(8f),
        )

        // 跟手的影子块：画在最上层，不吃触摸
        dragType?.let { type ->
            TrayBlockGhost(
                type = type,
                scale = state.scale,
                modifier = Modifier
                    .zIndex(9f)
                    .offset {
                        IntOffset(
                            (dragPos.x - NodeGraphMetrics.CARD_W * density.density * state.scale / 2f).roundToInt(),
                            (dragPos.y - NodeGraphMetrics.CARD_H * density.density * state.scale / 2f).roundToInt(),
                        )
                    },
            )
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
                modifier = Modifier
                    .zIndex(6f)
                    .align(Alignment.TopCenter)
                    // 顶栏不再压在状态栏上：让出状态栏高度，状态栏那一带露出画布（点阵），背景就是透明的
                    .statusBarsPadding()
                    // 横屏：右端要看得见和左端一样的圆角 ⇒ 卡片右沿与参数栏（或托盘）左沿之间
                    // 留一条缝，缝宽 = 卡片左侧到屏幕的距离（8dp，来自 barModifier 的横向 padding）。
                    // 这里只让出「托盘 + 参数栏弹簧宽 + 导航栏」，卡片自己的 8dp 外边距由 barModifier 提供。
                    .padding(
                        end = if (horizontal) {
                            (trayInsetDp + panelLiveWidth).coerceAtLeast(0.dp)
                        } else {
                            0.dp
                        },
                    ),
            horizontal = horizontal,
        )

        // 底部提示（连线失败原因 / 运行前的校验提示）
        val toast = state.toast
        if (toast != null) {
            NodeGraphToast(
                text = toast,
                modifier = if (horizontal) {
                    // 横屏：让出右侧侧栏
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = trayLiveSize + 16.dp, bottom = 16.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = traySize + if (state.singleSelected != null) 300.dp else 16.dp)
                },
            )
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(2600)
                if (state.toast == toast) state.toast = null
            }
        }

        // 底部参数面板：只选中一个节点时升起（要整体升到托盘之上，不能被托盘盖住）。
        // 进/出场都是动画：自下而上滑入 + 淡入（带轻微回弹），向下滑出 + 淡出（不会一帧消失）；
        // 底部内边距跟着托盘的**实时高度**走 ⇒ 托盘拖动/回弹的全过程面板都贴在它的上沿。
        val single = state.singleSelected
        // 出场动画期间 single 已经是 null，这里记住最后一个节点，保证滑出的那几帧还有内容可画
        var lastParamsNode by remember { mutableStateOf<ProgramNode?>(null) }
        LaunchedEffect(single?.id) {
            if (single != null) lastParamsNode = single
        }
        if (horizontal) {
            // ============ 横屏参数栏：长宽对调、无拖柄 ============
            // 和竖屏同一套手感：选中节点时横向滑入（带轻微回弹）+ 淡入，关掉时滑出 + 淡出。
            // 没有拖柄、没有「收起档」——关掉就是整块退场（用户明确要求「不要小把手，要回弹动画」）。
            AnimatedVisibility(
                visible = single != null,
                enter = slideInHorizontally(
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
                    initialOffsetX = { fullWidth -> fullWidth },
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = slideOutHorizontally(
                    animationSpec = tween(durationMillis = 200),
                    targetOffsetX = { fullWidth -> fullWidth },
                ) + fadeOut(animationSpec = tween(durationMillis = 160)),
                modifier = Modifier
                    .zIndex(7f)
                    .align(Alignment.CenterEnd)
                    // 右侧那条 28dp 补条压在托盘底下 ⇒ 这里要先让出补条再让托盘，才会正好被盖住
                    .padding(end = (trayInsetDp - panelTuck).coerceAtLeast(0.dp)),
            ) {
                val node = single ?: lastParamsNode
                if (node != null) {
                    Row(modifier = Modifier.width(panelExpandedWidth + panelTuck).fillMaxHeight()) {
                        NodeGraphParamsPanel(
                            state = state,
                            node = node,
                            onClose = { state.selectedIds = emptySet() },
                            horizontal = true,
                            collapsed = false,
                            contentEndInset = 0.dp,
                            modifier = Modifier.width(panelExpandedWidth).fillMaxHeight(),
                        )
                        // 补条：只负责把面板底色延伸到托盘底下，不承载任何内容
                        Box(
                            modifier = Modifier
                                .width(panelTuck)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        } else {
            // ============ 竖屏参数栏：外观一字不动 ============
            AnimatedVisibility(
                visible = single != null,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
                    initialOffsetY = { fullHeight -> fullHeight },
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 200),
                    targetOffsetY = { fullHeight -> fullHeight },
                ) + fadeOut(animationSpec = tween(durationMillis = 160)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val node = single ?: lastParamsNode
                if (node != null) {
                    NodeGraphParamsPanel(
                        state = state,
                        node = node,
                        onClose = { state.selectedIds = emptySet() },
                        modifier = Modifier.padding(bottom = trayLiveSize),
                    )
                }
            }
        }
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

    // 删除确认（开始节点也可以删；这里只确认真正的删除动作）
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
                    // 开始节点也允许删除（用户明确要求）；返回 false 只可能是选中的节点已经不存在了。
                    state.deleteSelected()
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
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 四角都留圆角：横屏工具条右端现在是「紧贴参数栏/托盘左沿」，要看到完整的圆角，
    // 否则会被看成「右边被裁掉」（用户明确提出过）。
    val barShape = RoundedCornerShape(16.dp)
    val barModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 8.dp)
        .shadow(4.dp, barShape)
        .clip(barShape)
        .background(surface)
        .padding(horizontal = 4.dp)
    // 六颗按钮（撤销/重做/复制/粘贴/删除/运行）：两种布局共用，避免复制粘贴两份。
    val buttons: @Composable () -> Unit = {
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
    if (horizontal) {
        // 横屏：单 Row —— 返回 + 标题（只占内容宽）+ 弹性空隙 + 按钮组贴右。
        // 不要用「Box 里两段 align」那种写法：标题行的 weight(1f) 会把父 Row 撑满、按钮组被挤出可视区。
        Row(
            modifier = barModifier,
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRename() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    buttons()
                }
            }
        }
    } else {
        // 竖屏：保持原来的单 Row 外观（标题 weight(1f, fill = false) 只占内容宽，按钮紧跟其后）
        Row(
            modifier = barModifier,
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
                    // fill = false：标题只占自身内容宽，余量留给右侧按钮，保证「运行 ▶」不被工具条裁掉。
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRename() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                buttons()
            }
        }
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
    horizontal: Boolean = false,
    collapsed: Boolean = false,
    // 横屏：面板本体右端要伸到托盘底下 28dp，但**内容**不能跟着钻进去（会被托盘遮住）。
    // 由调用方传 panelTuck，这里把内容右内边距补上同样的值。
    contentEndInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    // 竖屏：底部面板（顶部圆角、高度上限 320dp）；横屏：长宽对调（占满可用高，宽由调用方给）。
    val shape = if (horizontal) {
        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    }
    Column(
        modifier = modifier
            .then(
                if (horizontal) {
                    // 横屏：宽度由调用方给（weight），这里必须 fillMaxWidth，否则内容按 wrap 被挤窄
                    Modifier.fillMaxHeight().fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                },
            )
            .shadow(8.dp, shape)
            .clip(shape)
            .background(surface),
    ) {
        // 收起态只剩一条窄条（拖柄在屏幕那一侧），内容整块不画
        if (!collapsed) {
        // 标题行：类型名 + 步骤 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp + contentEndInset, top = 10.dp, bottom = 4.dp),
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
                .then(if (horizontal) Modifier.weight(1f) else Modifier.heightIn(max = 260.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (horizontal) 8.dp else 16.dp)
                // 横屏再补一条右内边距 = 面板塞进托盘的那 28dp，保证控件不被托盘遮住
                .padding(end = contentEndInset)
                // 底部留白 ≥8dp（原 16dp 不够：竖屏延时节点的数字框底边会和面板下沿齐平，
                // 验收要求至少 8dp 且视觉上不贴边）⇒ 提到 24dp；横屏两行布局不动。
                .padding(bottom = 24.dp),
        ) {
            // 横屏窄栏：10 个色点竖排一列（一行放不下，硬塞会被裁）
            if (horizontal) {
                ColorColumn(node = node) { color ->
                    state.updateNode(node.id) { it.copy(color = color) }
                }
            } else {
                ColorRow(node = node) { color ->
                    state.updateNode(node.id) { it.copy(color = color) }
                }
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
                        vertical = horizontal,
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
                        vertical = horizontal,
                    )
                    Spacer(Modifier.height(6.dp))
                    CountRow(
                        label = stringResource(R.string.node_graph_repeat),
                        value = node.repeatCount,
                        onValue = { v -> state.updateNode(node.id) { it.copy(repeatCount = v) } },
                        vertical = horizontal,
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
                        vertical = horizontal,
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
        // 10 个色点 + 9 个间距必须整体塞进面板可用宽：固定 28dp 时横屏窄面板（~314dp）会溢出被裁，
        // 这里按可用宽反算点径（上限 28dp），保证一行完整、面板不出现横向滚动。
        BoxWithConstraints {
            val gap = 10.dp
            val dot = (((maxWidth - gap * (PROGRAM_COLORS.size - 1)) / PROGRAM_COLORS.size))
                .coerceIn(16.dp, 28.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PROGRAM_COLORS.forEach { color ->
                    val selected = color == node.color
                    Box(
                        modifier = Modifier
                            .size(dot)
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
}

/** 横屏窄栏颜色列：10 个色点竖排一列（点径按可用宽夹在 16~28dp，放不下由外层滚动兜底）。 */
@Composable
private fun ColorColumn(node: ProgramNode, onPick: (Int) -> Unit) {
    Column {
        ParamSectionTitle(stringResource(R.string.node_graph_color))
        BoxWithConstraints {
            val gap = 6.dp
            val dot = maxWidth.coerceIn(16.dp, 28.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PROGRAM_COLORS.forEach { color ->
                    val selected = color == node.color
                    Box(
                        modifier = Modifier
                            .size(dot)
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
private fun CountRow(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    vertical: Boolean = false,
) {
    var live by remember(value) {
        mutableStateOf(value.coerceIn(0, 99).toFloat())
    }
    if (vertical) {
        // 横屏竖放：栏内滑块一律竖向，数值放在滑块下面（用户口径）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ClickText(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = live,
                    onValueChange = { live = it },
                    onValueChangeFinished = { onValue(live.toInt()) },
                    valueRange = 0f..20f,
                    steps = 19,
                    modifier = Modifier.width(160.dp).graphicsLayer { rotationZ = -90f },
                )
            }
            ClickText(
                text = live.toInt().toString(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
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
private fun DurationRow(
    title: String,
    valueMs: Long,
    onValue: (Long) -> Unit,
    vertical: Boolean = false,
) {
    var live by remember(valueMs) {
        mutableStateOf(valueMs.coerceIn(0L, 30_000L).toFloat())
    }
    Column {
        if (title.isNotEmpty()) {
            ParamSectionTitle(title)
        }
        if (vertical) {
            // 横屏竖放：滑块转 -90°（旋转后左端落在底部 ⇒ 向上拖 = 数值变大），长度吃可用高 200dp、
            // 粗细 48dp（= 旋转前的 Slider 高度）；数字框另起一行固定 96dp，四边完整。
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = live,
                    onValueChange = { live = it },
                    onValueChangeFinished = { onValue(live.toInt().toLong()) },
                    valueRange = 0f..30_000f,
                    modifier = Modifier
                        .width(200.dp)
                        .graphicsLayer { rotationZ = -90f },
                )
            }
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "",
                value = live.toInt(),
                onValue = {
                    live = it.coerceIn(0, 30_000).toFloat()
                    onValue(it.toLong())
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
        BoxWithConstraints {
            // 横屏面板被收窄（可用宽 ~328dp）时，滑块（Material 自带最小宽）+ 86dp 数字框挤爆一行，
            // 数字框右边会被面板裁掉；可用宽 < 360dp 改成两行：滑块整行 + 数字框单独一行，四边完整。
            val narrow = maxWidth < 360.dp
            if (narrow) {
                Column {
                    Slider(
                        value = live,
                        onValueChange = { live = it },
                        onValueChangeFinished = { onValue(live.toInt().toLong()) },
                        valueRange = 0f..30_000f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField(
                        label = "",
                        value = live.toInt(),
                        onValue = {
                            live = it.coerceIn(0, 30_000).toFloat()
                            onValue(it.toLong())
                        },
                        modifier = Modifier.width(96.dp),
                    )
                }
            } else {
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
