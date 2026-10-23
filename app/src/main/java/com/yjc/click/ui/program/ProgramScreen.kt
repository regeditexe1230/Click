package com.yjc.click.ui.program

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yjc.click.R
import com.yjc.click.program.Program
import com.yjc.click.program.ProgramStore
import com.yjc.click.ui.theme.ClickText
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「程序」页：预设卡片列表 + 卡片放大淡入进出节点图画布。
 *
 * [openProgramId] 由外壳持有：非 null = 现在要展示这个程序的画布。
 * 点卡片时先播翻转动画，翻过 90° 之后才回调 [onOpenProgram]，
 * 所以「正在翻的那张卡片」（[flipProgramId]）和「外部要求打开的程序」（[openProgramId]）
 * 是两份状态：前者驱动覆盖层，后者驱动画布内容。
 */
@Composable
fun ProgramScreen(
    onOpenProgram: (String) -> Unit,
    onCloseProgram: () -> Unit,
    openProgramId: String?,
    /** 翻转动画是否在播：外壳据此让顶栏和底栏同步收起 */
    onAnimationActive: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var programs by remember { mutableStateOf(ProgramStore.list(context)) }

    // 长按菜单 / 重命名 / 删除确认
    var menuTarget by remember { mutableStateOf<Program?>(null) }
    var renameTarget by remember { mutableStateOf<Program?>(null) }
    var deleteTarget by remember { mutableStateOf<Program?>(null) }

    // 拖拽排序：被拖的 id、它原来的下标、手指相对按下点的位移、拖起来时的根坐标
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingFromIndex by remember { mutableIntStateOf(-1) }
    var draggingGrabTop by remember { mutableFloatStateOf(0f) }
    var draggingGrabOffsetY by remember { mutableFloatStateOf(0f) }
    var dragPointerDeltaY by remember { mutableFloatStateOf(0f) }

    // 列表在根坐标里的上边距 + 每张卡片在根坐标里的位置（拖动折算 / 翻转起飞都用它）
    var listTopInRoot by remember { mutableFloatStateOf(0f) }
    val cardFrames = remember { mutableStateMapOf<String, CardFrame>() }
    // 拖动开始时冻结的槽位快照：拖动中卡片会被平移，boundsInRoot 跟着变，
    // 再用它算「该插到第几格」就会自己喂自己 —— 槽位来回跳，看起来就是抽搐
    val dragSlots = remember { mutableStateMapOf<String, CardFrame>() }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    val listState = rememberLazyListState()

    // ---- 翻转动画 ----
    // 动画状态不住在这一页里：它必须挂在**窗口级**（MainScaffold 最外层，见 ProgramFlipOverlay）。
    // 挂在这一页里面的话，顶栏底栏一收（布局高度真的变了）整页就跟着重排，翻转中的卡片也跟着上移
    // （实测上移 224px，用户要的是「只有被点的那张卡片动」）；节点图的可见范围还会被页面高度卡住
    // （2528）而不是整屏（3200）。所以这里只负责**驱动**这个共享对象，画它的是外壳那层。
    val flip = LocalProgramFlip.current ?: remember { ProgramFlipState() }

    // 卡片位置要在它从列表里消失之后还能用，所以点下去时单独留一份快照（只留被点的那张）
    val flipFrames = remember { mutableStateMapOf<String, CardFrame>() }

    // 回调只装一次：flip 是长期存活的对象，页面每次重组都改写它只会白费功夫
    // （装在下面，等 openTile / closeProgram 这些局部函数都声明完了再装）

    // 外部直接要求展示某个程序（点开时外壳清空过、或进程恢复）：没有卡片可翻，进度直接置满 / 归零
    LaunchedEffect(openProgramId) {
        if (openProgramId != null && flip.flipId == null && !flip.animating) {
            flip.openId = openProgramId
            flip.progress.snapTo(1f)
        } else if (openProgramId == null && flip.flipId == null) {
            flip.openId = null
            flip.progress.snapTo(0f)
        }
    }

    // 用户：顶栏底栏要和动画同时动，而且动画压在两条栏上面 ⇒「全屏」从点下去那一刻就算数，
    // 退场动画一开始立刻还回来。翻转那一路由 ProgramFlipState.animateTo() 自己上报，
    // 这里只管「没有卡片可翻、外壳直接要求开图」的情况。
    LaunchedEffect(openProgramId, flip.flipId) {
        if (flip.flipId == null) onAnimationActive(openProgramId != null)
    }

    // 内部点开：卡片自己长大 / 翻出去，放完（进度满）才通知外壳
    fun openTile(program: Program, pressTilt: CardTilt = CardTilt()) {
        if (flip.flipId != null || flip.closing || flip.animating) return
        val frame = cardFrames[program.id]
        if (frame == null || frame.width <= 0f || frame.height <= 0f) {
            // 拿不到卡片位置（理论上不会发生）：直接打开，不演动画
            onOpenProgram(program.id)
            return
        }
        flipFrames[program.id] = frame
        flip.open(program, frame, pressTilt)
    }

    // 返回：把卡片反向翻回来（和进入完全对称），翻完再让外壳把状态清掉
    fun closeProgram() {
        if (flip.animating) return
        flip.close()
    }

    // 回调只装一次：flip 是长期存活的对象，页面每次重组都改写它只会白费功夫
    remember(flip) {
        flip.scope = scope
        flip.onFullScreen = onAnimationActive
        flip.onEntered = { id ->
            if (openProgramId == null) onOpenProgram(id)
        }
        flip.onExited = { onCloseProgram() }
        flip.onBack = { closeProgram() }
        flip.onRename = { renameTarget = it }
        flip.onCopy = { program ->
            ProgramStore.duplicate(context, program.id)
            programs = ProgramStore.list(context)
        }
        flip.onDelete = { deleteTarget = it }
        true
    }

    // 兜底看门狗（只在**进度真的停住**时才兜）：forceSettle() 每 50ms 看一次进度，还在动就继续等，
    // 连续 STUCK_MS（400ms）没有任何变化才 snapTo 到目标值、把收尾该做的事（通知外壳展开 / 关掉）补齐。
    // 这里**不是**「时长 + 余量」的无条件兜底 —— 那种写法会在动画正常放完时也补一枪，
    // 观感就是「点完先愣一下、然后一帧跳过去」，正是验收方量到的那次「单帧巨变」。
    // 宁可瞬间跳完，也绝不把界面卡在「翻了一半」那儿（用户碰到过：进了图点啥都没反应）。
    LaunchedEffect(flip.animating) {
        if (flip.animating) flip.forceSettle()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { windowSize = it },
    ) {
        ProgramListView(
            // turnstile 档**没有**「动画层上那张一模一样的卡片」：整页在一起转，本体要是也藏起来，
            // 退出收尾时会整张卡片（含右下角三个按钮）在一帧里蹦回来 —— 就是用户报的「按钮突然出现」。
            // 只有 EXPAND / FLIP 那两档才会在动画层重画同一张卡片，那时才该藏本体。
            // 慢放抓帧：frame24→25 时 (56,378,1384,720) 这块有 77.66% 像素在一帧内变化，正是这张卡片。
            pageFlippingId = if (FLIP_PROFILE == FlipProfile.FLIP_TURNSTILE || FLIP_PROFILE == FlipProfile.GROW) null else flip.flipId,
            programs = programs,
            listState = listState,
            // 只让被点的磁贴自己翻+放大（磁贴翻转 磁贴那种），列表本身一动不动：
            // 之前这里跟着淡出，看起来像「整个界面在动」
            listAlpha = 1f,
            draggingId = draggingId,
            draggingFromIndex = draggingFromIndex,
            draggingGrabTop = draggingGrabTop,
            dragPointerDeltaY = dragPointerDeltaY,
            listTopInRoot = listTopInRoot,
            onListTopChange = { listTopInRoot = it },
            onCardFrame = { id, frame ->
                cardFrames[id] = frame
                // 转屏之后列表虽然被图页整个盖住，仍然在参与布局，卡片矩形会按新朝向重新上报。
                // 同步给打开着的那一页：退出动画才会缩到「现在这个朝向」的卡片，
                // 而不是进来时那张（竖屏进来、横屏退出时，原来会缩到一个竖屏尺寸的矩形上）。
                // 转屏之后列表会按新方向重新布局，卡片位置也跟着变：退场动画的落点必须是「当下的卡片」，
            // 所以关闭途中也照收 —— 这一层每个帧都从 flip.frame 现算，晚一两帧纠正过来落点才对得上。
            if (flip.flipId == id && (!flip.animating || flip.closing)) flip.frame = frame
            },
            onOpen = { program, tilt -> openTile(program, tilt) },
            onLongPress = { },   // 长按不再弹菜单：只用于“按住拖动排序”
            onRename = { renameTarget = it },
            onCopy = {
                ProgramStore.duplicate(context, it.id)
                // 复制完要刷新列表，否则新程序不出现，看起来像「点了没反应」
                programs = ProgramStore.list(context)
            },
            onDelete = { deleteTarget = it },
            onDragStart = { program, index, grabOffsetY ->
                draggingId = program.id
                draggingFromIndex = index
                draggingGrabOffsetY = grabOffsetY
                draggingGrabTop = cardFrames[program.id]?.top ?: 0f
                dragPointerDeltaY = 0f
                dragSlots.clear()
                dragSlots.putAll(cardFrames)
            },
            onDrag = { deltaY -> dragPointerDeltaY = deltaY },
            onDrop = { deltaY ->
                val from = draggingFromIndex
                val id = draggingId
                draggingId = null
                draggingFromIndex = -1
                // 位移是 0 说明手指没真的移动过（长按后原地松手）：
                // 这时只把卡片放回原处，绝不能按「指针在卡片下半部分」把它算到下一位去。
                if (id != null && from >= 0 && abs(deltaY) > 0.5f) {
                    // 手指在根坐标里的位置 = 拖起来时的位置 + 位移 + 卡片内抓取点
                    val pointerY = draggingGrabTop + deltaY + draggingGrabOffsetY
                    val to = dragTargetIndexAt(pointerY, programs, dragSlots, from)
                    if (to != from) {
                        val ordered = programs.toMutableList()
                        ordered.add(to, ordered.removeAt(from))
                        programs = ordered
                        ProgramStore.reorder(context, ordered)
                    }
                }
            },
            onNewProgram = {
                ProgramStore.create(context)
                programs = ProgramStore.list(context)
            },
            // 拖动中用冻结的槽位，避免自己算自己导致的抖动
            cardFrames = if (draggingId != null) dragSlots else cardFrames,
        )

        // 用户：打开程序后按系统返回要回列表，不能退出 App（原来没注册 BackHandler，返回键直接把 Activity 关了）
        // 进场那 600ms 里 openProgramId 还是空的（翻完才通知外壳），所以判断要把正在翻的卡片也算进来，
        // 否则这 600ms 按返回 = 直接退出应用。
        BackHandler(enabled = openProgramId != null || flip.flipId != null) {
            when {
                // 退场动画已经在跑：吞掉，别叠第二次
                flip.closing -> Unit
                // 进场途中：不吃 BACK。
                // 理由：进场是「用户点了那张卡片」的直接结果，这几百毫秒里他不可能真想返回；而任何一次
                // 多余的 BACK（系统手势残留、重复事件、自动化脚本）都会经 cancelEnter() 把进场**原地倒放** ——
                // 验收量到的「没有动画、直接跳变」（p 从 0.62 一路降到 0、图页角度反而涨回 74.8°）就是它。
                // 所以进场期间直接吞掉，等进场结束（animating=false）后再按才走下面的 closeProgram()。
                flip.animating -> Unit
                else -> closeProgram()
            }
        }

        // ---- 翻转覆盖层（flip 磁贴原地翻转）已提到窗口级 ----
        // 它现在由外壳渲染（MainScaffold 里的 ProgramFlipOverlay，状态就是上面那个 flip）。
        // 提层的原因：动画层留在这个 Box 里的话，顶栏底栏一收（布局高度真的变了）整页会重排，
        // 翻转中的卡片跟着上移 224px；节点图的可见范围也被页面高度卡在 2528 而不是整屏 3200。

        // ---- 长按菜单 ----
        val target = menuTarget
        if (target != null) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                DropdownMenu(expanded = true, onDismissRequest = { menuTarget = null }) {
                    DropdownMenuItem(
                        text = { ClickText(stringResource(R.string.program_rename), 14.sp, MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            menuTarget = null
                            renameTarget = target
                        },
                    )
                    DropdownMenuItem(
                        text = { ClickText(stringResource(R.string.program_duplicate), 14.sp, MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            menuTarget = null
                            ProgramStore.duplicate(context, target.id)
                            programs = ProgramStore.list(context)
                        },
                    )
                    DropdownMenuItem(
                        text = { ClickText(stringResource(R.string.program_delete), 14.sp, MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuTarget = null
                            deleteTarget = target
                        },
                    )
                }
            }
        }
    }

    // ---- 重命名 ----
    val renaming = renameTarget
    if (renaming != null) {
        var draft by remember(renaming.id) { mutableStateOf(renaming.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = {
                ClickText(
                    stringResource(R.string.program_rename),
                    MaterialTheme.typography.headlineSmall.fontSize,
                    MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = {
                        ClickText(
                            stringResource(R.string.program_name_hint),
                            MaterialTheme.typography.bodyMedium.fontSize,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ProgramStore.rename(context, renaming.id, draft.trim())
                        programs = ProgramStore.list(context)
                        renameTarget = null
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    ClickText(stringResource(R.string.confirm), 14.sp, MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    ClickText(stringResource(R.string.cancel), 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // ---- 删除二次确认 ----
    val deleting = deleteTarget
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                ClickText(
                    stringResource(R.string.program_delete),
                    MaterialTheme.typography.headlineSmall.fontSize,
                    MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                ClickText(
                    stringResource(R.string.program_delete_confirm, deleting.name),
                    MaterialTheme.typography.bodyMedium.fontSize,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ProgramStore.delete(context, deleting.id)
                    programs = ProgramStore.list(context)
                    deleteTarget = null
                }) {
                    ClickText(stringResource(R.string.confirm), 14.sp, MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    ClickText(stringResource(R.string.cancel), 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/**
 * 列表页那一层的 turnstile 包装。
 *
 * 轴（屏幕左外侧 40px 的竖轴、y = 屏幕中线）和透视系数都在这一档里定死，
 * 调用方（MainScaffold 的页面内容区）只需要报上自己这一层的窗口左上角。
 * 角度为 0 时是空操作 —— 其他档和静止态都不受影响。
 */
internal fun Modifier.programListTurnstile(
    flip: ProgramFlipState,
    layerLeft: Float,
    layerTop: Float,
): Modifier =
    // 窄板档：列表页不转，只缩小 + 淡出（转场由上面那层图页负责）
    if (FLIP_PROFILE == FlipProfile.SLAB) {
        this.graphicsLayer {
            scaleX = flip.slabListScale
            scaleY = flip.slabListScale
            alpha = flip.slabListAlpha.coerceIn(0f, 1f)
        }
    } else {
        turnstileLayer(
            angleDeg = flip.listAngle,
            alpha = flip.listAlpha,
            originX = TURNSTILE_ORIGIN_X,
            originY = flip.window.height / 2f,
            perspective = TURNSTILE_PERSPECTIVE_IN,
            layerLeft = layerLeft,
            layerTop = layerTop,
        )
    }

/**
 * turnstile 层：整层绕「屏幕左外侧 40dp 的竖轴」转 [angleDeg] 度，再叠加不透明度 [alpha]。
 *
 * 透视是自己算的：真机上 `graphicsLayer.cameraDistance` 完全不起作用（实测三种距离下剪影都是正投影的
 * 规整矩形），所以这里用 4 点映射（setPolyToPoly 得到的是真正的射影变换）在 draw 阶段做 ——
 * 页面里的文字、卡片、工具条会跟着一起透视缩短，而不是整页被压扁。
 * [originX]/[originY] 给的是**窗口坐标**，[layerLeft]/[layerTop] 是这一层自己的窗口左上角：
 * 图层不在窗口原点时必须减掉，轴心才是对的。
 * 角度为 0 时什么都不做 —— 静止态的点击命中和没有这一层时完全一致（布局没有被变换）。
 */
internal fun Modifier.turnstileLayer(
    angleDeg: Float,
    alpha: Float,
    originX: Dp,
    originY: Float,
    perspective: Dp,
    layerLeft: Float,
    layerTop: Float,
): Modifier =
    this
        .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
        .drawWithContent {
            val quad = turnstileQuad(
                thetaDeg = angleDeg,
                originX = originX.toPx() - layerLeft,
                originY = originY - layerTop,
                perspective = perspective.toPx(),
                width = size.width,
                height = size.height,
            )
            if (quad == null) {
                this@drawWithContent.drawContent()
                return@drawWithContent
            }
            val src = floatArrayOf(
                0f, 0f,
                size.width, 0f,
                size.width, size.height,
                0f, size.height,
            )
            val matrix = android.graphics.Matrix()
            matrix.setPolyToPoly(src, 0, quad, 0, 4)
            drawContext.canvas.nativeCanvas.save()
            drawContext.canvas.nativeCanvas.concat(matrix)
            this@drawWithContent.drawContent()
            drawContext.canvas.nativeCanvas.restore()
        }

/**
 * turnstile 的四条轨道。p 是**开口程度**（不是「已播放进度」）：0 = 列表态、1 = 图页态 ——
 * 进场 p: 0→1、退场 p: 1→0，所以退场那几条一律按 `1 − p` 反过来读时间轴，静止态才自洽
 * （p=1 ⇒ 图页 0°、列表 −50° 且透明；p=0 ⇒ 图页 80°、列表 0° 且不透明）。
 * 压缩系数 `300/128` 表示「128ms 那条轨道只跑时间轴的前 43%」，进场是列表那次跑前段，
 * 退场换成图页那次跑前段 —— 两边都是「短的那条先走完」。
 *   graphAngle：进场 80° → 0°（ForwardIn 曲线，300ms）；退场 0° → 80°（BackwardOut 曲线，128ms）
 *   graphAlpha：进场 0 → 1（cubic-bezier(0, 2, 0, 2)）；退场 1 → 0（cubic-bezier(1, −0.42, 0.995, −0.425)）
 *   listAngle ：进场 0° → −50°（ForwardOut 曲线，128ms）；退场 −50° → 0°（BackwardIn 曲线，300ms）
 *   listAlpha ：进场 1 → 0（128ms）；退场 0 → 1（300ms）
 * 两条 opacity 曲线的性格都是「瞬时到位 + 定格」：进场那条 t≈2.7ms 就拉到 1，退场那条到 t≈123ms
 * 才动、最后几毫秒切到 0 —— 照字面用 CubicBezierEasing 就自动对，别换成平滑淡入淡出。
 */
private fun turnstileGraphAngle(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        TURNSTILE_IN_DEG * (1f - TURNSTILE_IN_EASE.transform(q))
    } else {
        TURNSTILE_IN_DEG * TURNSTILE_OUT_EASE.transform(
            ((1f - q) * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f, 1f),
        )
    }
}

private fun turnstileGraphAlpha(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        TURNSTILE_FADE_IN_EASE.transform(q)
    } else {
        1f - TURNSTILE_FADE_OUT_EASE.transform(
            ((1f - q) * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f, 1f),
        )
    }.coerceIn(0f, 1f)
}

private fun turnstileListAngle(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        TURNSTILE_LIST_DEG * TURNSTILE_OUT_EASE.transform(
            (q * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f, 1f),
        )
    } else {
        TURNSTILE_LIST_DEG * (1f - TURNSTILE_IN_EASE.transform(1f - q))
    }
}

private fun turnstileListAlpha(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        1f - TURNSTILE_FADE_OUT_EASE.transform(
            (q * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f, 1f),
        )
    } else {
        TURNSTILE_FADE_IN_EASE.transform(1f - q)
    }.coerceIn(0f, 1f)
}

/**
 * flip 档：p（线性时间轴 0..1）处的「窗口」生长量。
 * 进场：367ms 里一路长完，招牌曲线 —— 没有旋转、没有翻面，就是卡片矩形长到全屏。
 * 退场：前 167ms 只淡出、窗口一动不动；之后 367ms 才收回，用退场曲线。总 534ms。
 */
private fun tileGrow(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        FLIP_EASE.transform(q)
    } else {
        val start = FLIP_FADE_DELAY_EXIT + FLIP_FADE_MS
        val t = ((q * FLIP_EXIT_MS - start) / FLIP_MOVE_MS).coerceIn(0f, 1f)
        1f - FLIP_EASE_EXIT.transform(t)
    }
}

/**
 * flip 档：p 处节点图的不透明度（revealed / hidden 两条时间轴）。
 * 进场：延迟 200ms 再花 167ms 淡入；退场：立刻花 167ms 淡出。都用招牌曲线。
 */
private fun tileAlpha(p: Float, entering: Boolean): Float {
    val q = p.coerceIn(0f, 1f)
    return if (entering) {
        val t = ((q * FLIP_ENTER_MS - FLIP_FADE_DELAY_ENTER) / FLIP_FADE_MS).coerceIn(0f, 1f)
        FLIP_EASE.transform(t)
    } else {
        val t = ((q * FLIP_EXIT_MS - FLIP_FADE_DELAY_EXIT) / FLIP_FADE_MS).coerceIn(0f, 1f)
        1f - FLIP_EASE.transform(t)
    }
}

/**
 * 窗口生长曲线：0 → 1 的「窗口」生长量。
 *
 * flip 档走 WinJS 的两条时间轴（见 [flipGrow]）。
 * FLIP 档：[HANDOFF] 之前先长 [HANDOFF_GROW]，让「翻」和「长大」真的是同一段运动；交接那一刻的窗口
 * 正好等于正面的矩形。之后再把剩下的长完，两段都用先快后慢 —— 接缝处速度对得上，不会一顿。
 */
private fun flipGrow(p: Float, entering: Boolean): Float =
    if (FLIP_PROFILE == FlipProfile.EXPAND) {
        tileGrow(p, entering)
    } else if (p <= HANDOFF) {
        HANDOFF_GROW * FastOutSlowInEasing.transform((p / HANDOFF).coerceIn(0f, 1f))
    } else {
        HANDOFF_GROW + (1f - HANDOFF_GROW) *
            FastOutSlowInEasing.transform(((p - HANDOFF) / (1f - HANDOFF)).coerceIn(0f, 1f))
    }

/**
 * 窗口矩形：从卡片矩形插值到整屏矩形，四个角各自长。
 * 因为是「重新排版」而不是「缩放画面」，卡片里的文字和图标只会重新摆位，不会被拉扁。
 */
private fun flipRect(p: Float, frame: CardFrame, window: IntSize, entering: Boolean): Rect {
    val g = flipGrow(p, entering)
    val cardRight = frame.left + frame.width
    val cardBottom = frame.top + frame.height
    val screenRight = window.width.toFloat()
    val screenBottom = window.height.toFloat()
    return Rect(
        left = frame.left * (1f - g),
        top = frame.top * (1f - g),
        right = cardRight + (screenRight - cardRight) * g,
        bottom = cardBottom + (screenBottom - cardBottom) * g,
    )
}

/**
 * 背面：从头到尾 1:1 全屏的节点图。
 *
 * 它自己没有任何缩放 / 镜像 / 位移 —— 变大的只是外面这层「窗口」，窗口靠 drawWithContent 裁出来，
 * 所以节点图在屏幕上永远是 1:1 的，用户看到的是「从卡片的位置长开」，而不是「图被放大」。
 * 所有动画值都只在 layout / draw 阶段读（composition 里一次都不读），
 * 于是整段动画里这个 Composable 一次都不会重组 —— 节点图不会因为翻转被反复重组拖慢。
 * 布局上没有父级变换，点击命中和静止时完全一致（工具栏、节点、画布都点得到）。
 */
@Composable
private fun GraphReveal(
    programId: String,
    onBack: () -> Unit,
    progress: Animatable<Float, AnimationVector1D>,
    entering: Boolean,
    frame: CardFrame,
    window: IntSize,
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { TILE_CORNER_DP.dp.toPx() }
    val flip = FLIP_PROFILE == FlipProfile.EXPAND
    val turnstile = FLIP_PROFILE == FlipProfile.FLIP_TURNSTILE
    Box(
        modifier = Modifier
            .fillMaxSize()
            // EXPAND：整层不透明度按 WinJS 的 revealed / hidden 时间轴走 —— 进场延迟 200ms 再淡入 167ms，
            // 退场立刻淡出 167ms。alpha 只在 layer 块里读，所以动画期间这一层一次都不重组。
            .graphicsLayer { if (flip) alpha = tileAlpha(progress.value, entering) }
            .drawWithContent {
                // turnstile 档：图页就是「整页转进来」，不裁剪、也不分阶段揭示 ——
                // 旋转和不透明度都在外面那层 turnstileLayer 里做。
                // SLAB 档的变换全在外面那层做，这里也必须原样透传，否则会被下面 FLIP 的裁剪吞掉
                if (turnstile || FLIP_PROFILE == FlipProfile.SLAB || FLIP_PROFILE == FlipProfile.GROW) {
                    this@drawWithContent.drawContent()
                    return@drawWithContent
                }
                val p = progress.value
                // FLIP：正面还没转到 90°（还没交班），背面一点都不露。EXPAND：从 0 就开始长（只是还很淡）
                if (!flip && p < HANDOFF) return@drawWithContent
                // 长满整屏就彻底不裁了（也省掉一次路径裁剪）
                if (p >= 1f) {
                    this@drawWithContent.drawContent()
                    return@drawWithContent
                }
                val rect = flipRect(p, frame, window, entering)
                // 窗口圆角和卡片一致；最后 15% 收成直角，免得全屏的节点图四角顶出圆角
                val radius = if (p > 0.85f) cornerPx * ((1f - p) / 0.15f) else cornerPx
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                            cornerRadius = CornerRadius(radius, radius),
                        ),
                    )
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            },
    ) {
        NodeGraphScreen(
            programId = programId,
            onBack = onBack,
            // 用户：「现在节点图的上面顶到屏幕顶端了，上面是状态栏，把状态栏的地方空出来」。
            // 覆盖层本身是窗口级全屏（动画期间的 3D 变换层就该铺满），但图的内容区必须从状态栏下方开始，
            // 而且静止态也一直保持 —— 状态栏那块永远是空的。
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
        )
    }
}

/**
 * 正面：被点的那一张真卡片（标题、颜色点、右下角三个按钮都在）。
 * 它装在「窗口」里，一边转 / 一边长，所以不会出现被拉扁的怪帧。
 *
 * FLIP 档：转到 [HANDOFF]（正好 90°，侧面对着人、等于看不见了）就让位给背面 —— 不做镜像、不做淡出。
 * 透视是**自己算的**：真机上 graphicsLayer.cameraDistance 完全不起作用（实测三种距离下剪影都是正投影的规整矩形），
 * 所以这里在 draw 阶段把「卡片矩形」用 4 点映射成一个梯形 —— 近边（左边缘）高、远边（右边缘）矮，
 * 也就是右边缘转进屏幕里。用 setPolyToPoly 得到的是一个真正的射影变换，
 * 卡片里的文字、圆点、按钮会跟着一起做透视缩短，不是简单地把矩形压扁。
 *
 * flip 档：没有翻转、也没有透视 —— 这张卡片就是「长出来的那块底」，节点图在它上面淡入（见 GraphReveal）；
 * 唯一残留的姿态是按下时那点缩放，在进场头 100ms 里线性展开，和列表里那次松手回弹接得上。
 *
 * 所有动画值都只在 draw / layer 阶段读（composition 里只读 p 决定「画不画」），
 * 所以动画过程中这一层不会因为反复重组而拖慢。
 */
@Composable
private fun FlipFront(
    program: Program?,
    actions: @Composable () -> Unit,
    progress: Animatable<Float, AnimationVector1D>,
    entering: Boolean,
    tilt: CardTilt,
    frame: CardFrame,
    window: IntSize,
) {
    val p = progress.value
    val flip = FLIP_PROFILE == FlipProfile.EXPAND
    // FLIP：过了 90° 就交给背面了。flip：从头画到尾（节点图只是在它上面淡入）
    if (!flip && p >= HANDOFF) return
    val density = LocalDensity.current
    val rect = flipRect(p, frame, window, entering)
    // flip：进场头 100ms 把按下那点缩放线性放掉 —— 和列表里那次「先等 200ms、再 100ms 线性回弹」
    // 是同一条时间表（单位是规格毫秒，所以放慢倍数不影响比例）
    val unwind = if (flip && entering) {
        (((p * FLIP_ENTER_MS) - PRESS_UNWIND_DELAY_MS) / PRESS_UNWIND_MS).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .offset(
                x = with(density) { rect.left.toDp() },
                y = with(density) { rect.top.toDp() },
            )
            .size(
                width = with(density) { (rect.right - rect.left).toDp() },
                height = with(density) { (rect.bottom - rect.top).toDp() },
            )
            .graphicsLayer {
                if (flip) {
                    // 从按下姿态接着走：只有缩放，线性回到中性
                    val s = 1f + (tilt.scale - 1f) * (1f - unwind)
                    scaleX = s
                    scaleY = s
                }
            }
            .drawWithContent {
                if (flip) {
                    // flip 不做透视：就是一块实实在在长大的卡片
                    this@drawWithContent.drawContent()
                    return@drawWithContent
                }
                val theta = (PI / 2.0) * (p / HANDOFF).coerceIn(0f, 1f).toDouble()
                val sinT = sin(theta).toFloat()
                val cosT = cos(theta).toFloat()
                val halfW = size.width / 2f
                val halfH = size.height / 2f
                // 相机在屏幕外 d 处；深度 ±halfW·sinθ 的投影缩放就是 d / (d ± halfW·sinθ)。
                // d 跟着窗口宽度走，所以放大过程中透视强度恒定，不会越放越平。
                val d = PERSPECTIVE_DISTANCE_FACTOR * size.width
                val sNear = d / (d - halfW * sinT)   // 左边缘：转进屏幕的反面，离相机更近 → 更大
                val sFar = d / (d + halfW * sinT)    // 右边缘：转进屏幕里，离相机更远 → 更小
                // 整体除以 sNear：让近边正好落在窗口矩形上、不往外溢。
                // 不归一化的话近边会比窗口还高 30% 左右（45° 时约 95px），上半截顶出程序页的内容区、
                // 被顶栏切掉一条，看起来像坏帧。归一化之后透视形状完全一样（远边照样缩到 0.62），
                // 卡片只是「左边缘原地不动、右边缘转进屏幕变矮」—— 正好是用户要的方向。
                val far = sFar / sNear
                val src = floatArrayOf(
                    0f, 0f,
                    size.width, 0f,
                    size.width, size.height,
                    0f, size.height,
                )
                // 左上、右上、右下、左下 —— 上下边也跟着斜，才是真的透视，而不是「压扁的矩形」
                val dst = floatArrayOf(
                    halfW - halfW * cosT, halfH - halfH,
                    halfW + halfW * cosT * far, halfH - halfH * far,
                    halfW + halfW * cosT * far, halfH + halfH * far,
                    halfW - halfW * cosT, halfH + halfH,
                )
                val matrix = android.graphics.Matrix()
                matrix.setPolyToPoly(src, 0, dst, 0, 4)
                drawContext.canvas.nativeCanvas.concat(matrix)
                this@drawWithContent.drawContent()
            },
    ) {
        // 卡片始终是这个窗口里的「一整块」：内容跟着窗口重新排版，尺寸不会忽大忽小
        Box(modifier = Modifier.fillMaxSize()) {
            ProgramTile(
                program = program,
                modifier = Modifier.fillMaxSize(),
                actions = actions,
            )
        }
    }
}

/**
 * 翻转动画的共享状态 —— 一个「页面驱动、外壳渲染」的对象。
 *
 * 为什么要拆出来：动画层必须是**窗口级**的。留在程序页里的话，顶栏底栏一收（布局高度真的变了）
 * 整页就跟着重排，翻转中的卡片跟着上移（真机实测 224px，而用户要的是「只有被点的那张卡片动」）；
 * 节点图的可见范围也会被页面高度卡住在 2528，而不是整屏 3200。
 * 所以分工：ProgramScreen 负责**驱动**它，MainScaffold 在窗口级负责**画**它（ProgramFlipOverlay）。
 *
 * 坐标一律是**窗口坐标** —— cardFrames 存的就是 boundsInRoot()，和窗口级覆盖层的原点重合，不用换算。
 */
@Stable
internal class ProgramFlipState {
    /**
     * 0 = 列表；0.5 = 正面正好转到 90°（交班给节点图）；1 = 节点图长满整屏。
     * 只在图形的 draw / layout 阶段读它（在 composition 里读会让这一层每帧重组）。
     */
    val progress = Animatable(0f)

    /** 正在翻的那张卡片属于哪个程序（null = 没在翻）。翻完也留着 —— 退场要用它的矩形。 */
    var flipId by mutableStateOf<String?>(null)

    /** 画面上要展示的程序 id（翻的过程中 = flipId；图开着 = 外壳给的 openProgramId） */
    var openId by mutableStateOf<String?>(null)

    /** 被点的那张卡片在窗口坐标里的矩形：点下去那一刻冻结，整段动画只用它 */
    var frame by mutableStateOf(CardFrame())

    /** 正面那张卡片的内容 */
    var frontProgram by mutableStateOf<Program?>(null)

    /** 窗口尺寸（覆盖层自己量，永远是整个窗口） */
    var window by mutableStateOf(IntSize.Zero)

    /** 动画在播：这期间输入全锁，一结束立刻解锁 */
    var animating by mutableStateOf(false)

    /** 退场动画在跑（用来吞掉重复的返回） */
    var closing by mutableStateOf(false)

    /**
     * 这一次动画是进场还是退场。
     * flip 档的位移、淡入淡出是两条时间轴（进场先位移后淡入、退场先淡出后位移），
     * 光看进度值分不出来，所以显式记着；上下栏要不要按「图已打开」处理也按它报。
     */
    var entering by mutableStateOf(true)
        private set

    /** 被点那张卡片按下去时的姿态：进场动画从这个姿态接着走，回弹和进场不打架 */
    var tilt by mutableStateOf(CardTilt())
        private set

    /**
     * turnstile 档：列表页此刻该有的旋转角 / 不透明度。
     * 列表页在 MainScaffold 那边（要跟图页一起转才是 flip 那种「整页转场」），这里只负责算数值。
     */
    val listAngle: Float
        get() = if (turnstileActive) turnstileListAngle(progress.value, entering) else 0f

    val listAlpha: Float
        get() = if (turnstileActive) turnstileListAlpha(progress.value, entering) else 1f

    /** turnstile 档：节点图页此刻该有的旋转角 / 不透明度。 */
    val graphAngle: Float
        get() = if (turnstileActive) turnstileGraphAngle(progress.value, entering) else 0f

    val graphAlpha: Float
        get() = if (turnstileActive) turnstileGraphAlpha(progress.value, entering) else 1f

    private val turnstileActive: Boolean
        get() = FLIP_PROFILE == FlipProfile.FLIP_TURNSTILE && (openId != null || flipId != null)

    // ---- 窄板档（SLAB）：列表页缩退 + 页板转开成窄板停一拍 + 扫满带残角收平 ----

    /** 列表页此刻的缩放：前 [SLAB_A_END] 毫秒缩到 0.42。 */
    val slabListScale: Float
        get() = if (slabActive) slabListScaleAt(progress.value) else 1f

    val slabListAlpha: Float
        get() = if (slabActive) slabListAlphaAt(progress.value) else 1f

    /** 图页此刻绕右竖轴的转角（负值 = 左半边收向屏幕里）。 */
    val slabGraphAngle: Float
        get() = if (slabActive) slabGraphAngleAt(progress.value) else 0f

    val slabGraphAlpha: Float
        get() = if (slabActive) slabGraphAlphaAt(progress.value) else 1f

    /** 图页此刻的缩放：窄板阶段只有卡片那么大，扫满那一下长到 1。 */
    val slabGraphScale: Float
        get() = if (slabActive) slabGraphScaleAt(progress.value) else 1f

    private val slabActive: Boolean
        get() = FLIP_PROFILE == FlipProfile.SLAB && (openId != null || flipId != null)

    // ---- 回调：由 ProgramScreen 装上（只有它知道点开的是哪张卡片、列表怎么改）----
    var scope: CoroutineScope? = null

    /** 上下栏要不要按「图已经打开」处理：进场动画一开始就 true，退场一开始就 false */
    var onFullScreen: (Boolean) -> Unit = {}

    /** 翻出去收尾（图完全打开） */
    var onEntered: (String) -> Unit = {}

    /** 翻回来收尾（回列表） */
    var onExited: () -> Unit = {}

    /** 覆盖层上的返回（图里的关闭按钮 / 系统返回） */
    var onBack: () -> Unit = {}

    var onRename: (Program) -> Unit = {}
    var onCopy: (Program) -> Unit = {}
    var onDelete: (Program) -> Unit = {}

    /** 这一次动画的目标值（0 / 1）：看门狗收尾要用它（不能靠 openProgramId 反推） */
    private var target = 1f

    /** 这一次动画的收尾动作：正常放完和看门狗兜底走同一条路 */
    private var finish: (() -> Unit)? = null

    /** 点开一张卡片：冻结它的矩形与按下姿态，长大 / 翻出去；放完（进度满）才通知外壳把图打开 */
    fun open(program: Program, cardFrame: CardFrame, pressTilt: CardTilt = CardTilt()) {
        if (flipId != null || animating) return
        flipId = program.id
        openId = program.id
        frontProgram = program
        frame = cardFrame
        tilt = pressTilt
        closing = false
        animateTo(1f) { onEntered(program.id) }
    }

    /** 返回：反向翻回列表（和进入完全对称）；不是翻进来的就直接交给外壳 */
    fun close() {
        if (animating) return
        if (flipId == null) {
            onExited()
            return
        }
        closing = true
        animateTo(0f) {
            flipId = null
            openId = null
            frontProgram = null
            onExited()
        }
    }

    /**
     * 进场还没翻完就按了返回：从当前进度原地翻回列表（不进图）。
     * Animatable 起新动画会自动取消上一个 animateTo，所以是从当前角度继续往回走、不跳帧；
     * 往回退的时间按剩余进度折算，和正向同一套手感（写死 600ms 的话半路返回会突然变慢）。
     */
    fun cancelEnter() {
        if (flipId == null || closing) return
        closing = true
        target = 0f
        finish = null
        onFullScreen(false)
        val s = scope ?: return
        s.launch {
            val remain = (durationMs(0f) * progress.value).roundToInt().coerceAtLeast(1)
            // 半路掉头：还是用**进场那条时间轴**原样倒着走（不切成退场那套「先淡出再收回」的映射），
            // 这样从当前这一帧往回退，画面不会跳。退场语义只在图已经完全打开时才用。
            progress.animateTo(
                0f,
                tween(
                    durationMillis = remain,
                    easing = if (FLIP_PROFILE != FlipProfile.FLIP) LinearEasing else FastOutSlowInEasing,
                ),
            )
            animating = false
            closing = false
            flipId = null
            openId = null
            frontProgram = null
        }
    }

    /**
     * 看门狗兜底：**只在进度真的停住时**才硬跳到目标值。
     *
     * 判据是「静默」而不是固定时长：每 50ms 看一眼进度，只要它还在动就继续等，
     * 连续 [STUCK_MS] 毫秒没有任何变化才认为卡死，补一次收尾。
     * 旧版是 `delay(时长 + 400)` 无条件 snap —— 动画被别的动画顶掉（或时长猜小了）时，
     * 它会在动画中途突然把进度拽到终点，观感就是「一帧之内整页换掉」。
     * 验收方在 tap 后 ~1.1s 量到的那次「单帧巨变、没有任何中间态」正是这个签名。
     */
    suspend fun forceSettle() {
        var last = progress.value
        var quietSince = System.nanoTime()
        while (true) {
            delay(50L)
            if (!animating) return
            val now = progress.value
            if (now != last) {
                last = now
                quietSince = System.nanoTime()
            } else if ((System.nanoTime() - quietSince) / 1_000_000L >= STUCK_MS) {
                progress.snapTo(target)
                settle()
                return
            }
        }
    }

    /**
     * 这一次动画的时长。默认档（FLIP_TURNSTILE）两个方向都是 300ms（图页那条；列表页的 128ms 跑在前段）。
     * [FLIP_PROFILE] 切到 EXPAND（磁贴长大）时是进场 367 / 退场 534ms，切到 FLIP 时是恒定 [FLIP_MS]。
     */
    private fun durationMs(targetValue: Float): Int =
        SLOWMO * if (FLIP_PROFILE == FlipProfile.FLIP_TURNSTILE) {
            // turnstile：进场 = 图页那条 300ms；退场 = 列表页那条 300ms（图页那 128ms 跑在前段）
            TURNSTILE_IN_MS
        } else if (FLIP_PROFILE == FlipProfile.EXPAND) {
            if (targetValue > HANDOFF) FLIP_ENTER_MS else FLIP_EXIT_MS
        } else if (FLIP_PROFILE == FlipProfile.GROW) {
            if (targetValue > HANDOFF) GROW_TOTAL_MS.toInt() else GROW_EXIT_MS
        } else if (FLIP_PROFILE == FlipProfile.SLAB) {
            if (targetValue > HANDOFF) SLAB_TOTAL_MS.toInt() else SLAB_EXIT_MS
        } else {
            FLIP_MS
        }

    private fun animateTo(targetValue: Float, onDone: () -> Unit) {
        val s = scope ?: run { onDone(); return }
        target = targetValue
        entering = targetValue > HANDOFF
        finish = onDone
        animating = true
        // 用户：顶栏底栏要和动画同时动 ⇒「全屏」从点下去那一刻就算数，退场动画一开始就立刻还回来
        onFullScreen(entering)
        val ms = durationMs(targetValue)
        s.launch {
            if (entering) progress.snapTo(0f)
            // flip 的时间轴必须是**线性**的：两条时间轴各自的曲线与延迟（招牌曲线 / 退场曲线）
            // 都在 flipGrow / flipAlpha 里自己算，Animatable 只负责把 0..1 均匀走完。
            progress.animateTo(
                targetValue,
                tween(
                    durationMillis = ms,
                    easing = if (FLIP_PROFILE != FlipProfile.FLIP) LinearEasing else FastOutSlowInEasing,
                ),
            )
            settle()
        }
    }

    /** 把这一次动画收干净：正常放完和看门狗都走这里 */
    private fun settle() {
        val done = finish ?: return
        finish = null
        animating = false
        closing = false
        done()
    }
}

/**
 * 谁拿着窗口级的翻转状态。由 MainScaffold 提供（它在最外层画覆盖层），ProgramScreen 取来驱动；
 * 单独用这个页面时（预览、别的宿主）取不到，就地自己造一个，行为不变。
 */
internal val LocalProgramFlip = compositionLocalOf<ProgramFlipState?> { null }

/**
 * 窗口级动画层：常驻的 1:1 全屏节点图 + 正在翻的那张卡片 + 动画期间的输入锁。
 *
 * 挂在 MainScaffold 最外层（页面之外），所以整段动画里它一动都不动：
 * 背面是节点图本身，靠 GraphReveal 的裁剪窗口从卡片矩形长到整屏露出来；
 * 正面是被点的那张卡片自己绕竖中轴翻过去，转到 90°（正好侧面对着人）交给节点图。
 */
@Composable
internal fun ProgramFlipOverlay(flip: ProgramFlipState) {
    val id = flip.openId ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 揭示窗口的目标尺寸就是整个窗口 —— 顶栏底栏收起后不会再有 2528/3200 那种差
            .onSizeChanged { flip.window = it },
    ) {
        // 正面：被点的那一张真卡片自己（三个按钮跟着一起动；动画期间输入锁着，点不到它们）
        val front: @Composable () -> Unit = {
            flip.frontProgram?.let { program ->
                FlipFront(
                    program = program,
                    actions = {
                        ProgramTileActions(
                            onRename = { flip.onRename(program) },
                            onCopy = { flip.onCopy(program) },
                            onDelete = { flip.onDelete(program) },
                        )
                    },
                    progress = flip.progress,
                    entering = flip.entering,
                    tilt = flip.tilt,
                    frame = flip.frame,
                    window = flip.window,
                )
            }
        }
        val graph: @Composable () -> Unit = {
            GraphReveal(
                programId = id,
                onBack = flip.onBack,
                progress = flip.progress,
                entering = flip.entering,
                frame = flip.frame,
                window = flip.window,
            )
        }
        // turnstile：正面那份复制卡片用不上（列表页自己会跟着转），只画图页那一层
        // 这一层自己的窗口左上角：turnstileLayer 要的是图层局部坐标里的轴心，
        // 写死 (0,0) 只在「overlay 真的贴在窗口原点」时才对 —— 以实测为准（tick 日志里会打出来）
        var layerOrigin by remember { mutableStateOf(Offset.Zero) }
        if (FLIP_PROFILE == FlipProfile.FLIP_TURNSTILE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { layerOrigin = it.boundsInRoot().topLeft }
                    .turnstileLayer(
                        angleDeg = flip.graphAngle,
                        alpha = flip.graphAlpha,
                        originX = TURNSTILE_ORIGIN_X,
                        originY = flip.window.height / 2f,
                        perspective = if (flip.entering) TURNSTILE_PERSPECTIVE_IN else TURNSTILE_PERSPECTIVE_OUT,
                        layerLeft = layerOrigin.x,
                        layerTop = layerOrigin.y,
                    ),
            ) {
                graph()
            }
        } else if (FLIP_PROFILE == FlipProfile.EXPAND) {
            front()
            graph()
        } else if (FLIP_PROFILE == FlipProfile.GROW) {
            // 放大档：被点的那张卡片从原位长到满屏（圆角同步收到 0），图页在它上面同时淡入
            val density = LocalDensity.current
            val winW = flip.window.width
            val winH = flip.window.height
            val frameW = if (flip.frame.width > 1f) flip.frame.width else winW * 0.92f
            val frameH = if (flip.frame.height > 1f) flip.frame.height else winH * 0.14f
            val frameX = if (flip.frame.width > 1f) flip.frame.left else winW * 0.04f
            val frameY = if (flip.frame.height > 1f) flip.frame.top else winH * 0.06f
            val gms = growMs(flip.progress.value)
            val cardT = FastOutSlowInEasing.transform((gms / GROW_CARD_END).coerceIn(0f, 1f))
            // 图页在 85% 处就淡透：最后 100ms 画面完全是静的，交接只会发生在静止帧上，看不出来
            val alphaT = if (flip.entering) {
                ((gms - GROW_FADE_START) / (GROW_TOTAL_MS * 0.85f - GROW_FADE_START)).coerceIn(0f, 1f)
            } else {
                // 退场：图页跟着卡片一起退。以前图页要到 85% 才开始淡，那之前它整页盖在卡片上面，
                // 用户只看到「整页停一下、然后一闪」，卡片收回去的过程全被挡住。
                ((cardT - 0.12f) / 0.88f).coerceIn(0f, 1f)
            }
            // 起始块必须和「手指按下时那张卡片」一模一样：卡片按下会缩到 PRESS_SCALE（0.94），
            // 色块要是从没缩过的矩形长起来，一按下就看见按钮自己“啪”地胀开。这里把按下那一份缩放
            // 带进起点，随 cardT 一起放大到 1。
            val pressK = 1f + (flip.tilt.scale.coerceIn(0.5f, 1.1f) - 1f) * (1f - cardT)
            val startW = frameW * pressK
            val startH = frameH * pressK
            val startX = frameX + (frameW - startW) / 2f
            val startY = frameY + (frameH - startH) / 2f
            val cardW = startW + (winW - startW) * cardT
            val cardH = startH + (winH - startH) * cardT
            val cardX = startX * (1f - cardT)
            val cardY = startY * (1f - cardT)
            // 不再画列表副本：真实列表还在下面那一层，副本只会和它差几像素 —— 开场那一下跳变就是它。
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(cardX.roundToInt(), cardY.roundToInt())
                    }
                    .size(with(density) { cardW.toDp() }, with(density) { cardH.toDp() })
                    .background(
                        color = androidx.compose.ui.graphics.lerp(
                            // 起点色必须是列表卡片自己的底色（surfaceContainerHigh），否则一按下按钮会变色。
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surface,
                            cardT,
                        ),
                        shape = RoundedCornerShape((GROW_CARD_RADIUS * (1f - cardT)).dp),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = 1f - cardT
                        if (flip.entering) {
                            // 进场只淡入、不改几何：整页每帧重算缩放会掉帧（前几帧尤其明显）。
                            // 「卡片长大」由下面那块色块完成，观感一样而代价小得多。
                            alpha = alphaT
                        } else {
                            // 退场让图页本身体积缩回卡片：落点取当下这个矩形（转屏后已按新方向重新量过）
                            scaleX = 1f + (frameW / winW - 1f) * t
                            scaleY = 1f + (frameH / winH - 1f) * t
                            translationX = (frameX + frameW / 2f - winW / 2f) * t
                            translationY = (frameY + frameH / 2f - winH / 2f) * t
                            alpha = (1f - ((t - 0.82f) / 0.18f)).coerceIn(0f, 1f)
                        }
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    },
            ) {
                // 图页首次组合/布局很重（点阵画布 + 全部节点 + 托盘），放在进场开头会卡掉前几帧。
                // 推迟到色块快铺满时再组合：掉的那一帧被色块整个盖住，淡入也从那一刻开始。
                if (!flip.entering || gms >= GROW_FADE_START - 24f) graph()
            }
        } else if (FLIP_PROFILE == FlipProfile.SLAB) {
            // 窄板档：整页先按「卡片大小」渲染，让这个小盒子绕自己的竖轴转 —— 转到侧面时
            // 自然就是一条窄板，背景也露得出来；④ 阶段这个盒子长到全屏，同一个轴心把页摆平。
            val density = LocalDensity.current
            val winW = flip.window.width
            val winH = flip.window.height
            val frameW = if (flip.frame.width > 1f) flip.frame.width else winW * 0.92f
            val frameH = if (flip.frame.height > 1f) flip.frame.height else winH * 0.14f
            val frameX = if (flip.frame.width > 1f) flip.frame.left else winW * 0.04f
            val frameY = if (flip.frame.height > 1f) flip.frame.top else winH * 0.06f
            val grow = ((flip.slabGraphScale - SLAB_PANEL_SCALE) / (1f - SLAB_PANEL_SCALE)).coerceIn(0f, 1f)
            // 页是白的，转到侧面时和同样白的背景糊成一片 —— 底下垫一层随扫满淡出的暗幕。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SLAB_SCRIM_ALPHA * flip.slabGraphAlpha * (1f - grow))),
            )
            // 窄板档：图页先按屏幕 40% 的「页面等比小盒子」渲染（绕开「整页转过去时远侧被放大铺满
            // 全屏、看不出窄板」的问题），绕小盒自己的竖轴转 —— 转到侧面时自然就是一条窄板，
            // 背景也露得出来；扫满阶段这个盒子长到全屏，同一个轴心把页摆平。角度为负 = 与参考方向相反。
            val holdW = winW * 0.40f
            val holdH = winH * 0.40f
            val holdX = (frameX + frameW / 2f) - holdW / 2f
            val holdY = winH / 2f - holdH / 2f
            val boxW = holdW + (winW - holdW) * grow
            val boxH = holdH + (winH - holdH) * grow
            val boxX = holdX * (1f - grow)
            val boxY = holdY * (1f - grow)
            var slabLayerOrigin by remember { mutableStateOf(Offset.Zero) }
            val axisDp = with(density) { (boxX + boxW / 2f).toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(boxX.roundToInt(), boxY.roundToInt()) }
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                    .onGloballyPositioned { slabLayerOrigin = it.boundsInRoot().topLeft }
                    .clipToBounds()
                    .turnstileLayer(
                        angleDeg = flip.slabGraphAngle,
                        alpha = flip.slabGraphAlpha,
                        originX = axisDp,
                        originY = boxY + boxH / 2f,
                        perspective = SLAB_PERSPECTIVE,
                        layerLeft = slabLayerOrigin.x,
                        layerTop = slabLayerOrigin.y,
                    ),
            ) {
                graph()
            }
        } else {
            graph()
            front()
        }
        if (flip.animating) {
            // 翻转中把输入全锁住：动画一结束这块就没了，点击立刻恢复
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                // 在 Initial pass 就吃掉：下面所有 clickable 都收不到这一下
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

/** 卡片在窗口坐标里的位置与尺寸；翻转动画以它为起点、放大以它为中心。 */
internal data class CardFrame(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

/**
 * 按下姿态：**只有缩放**。
 *
 * 用户明确不要 flip 磁贴「鼠标长按」那种透视变形（原来的 rotationX / rotationY / 轴心 / 变暗
 * 那一整条 TiltEffect 链已经删掉），所以这里只剩一个缩放值：按下缩一点、按住放大一点、松手回 1。
 * 点开卡片时把它交给动画层，进场动画从这个缩放接着走 —— 回弹和进场不打架。
 */
internal data class CardTilt(
    val scale: Float = 1f,
)

/**
 * 动画档位（默认档是 [FLIP_TURNSTILE]，改下面 `FLIP_PROFILE` 这一行就能切档）。
 * - [FLIP_TURNSTILE] **当前默认**：flip 页面转场的本体 —— 整页绕「屏幕左外侧 40px 的竖轴」转，
 *   图页 80°→0°（300ms）+ 列表页 0°→−50°（128ms），退场反过来；用自算射影映射（poly-to-poly）实现。
 * - [EXPAND]：卡片矩形长到全屏 + 节点图在裁剪窗口里淡入（上一版的 flip 磁贴语义，按下只有缩放）；
 * - [FLIP]：卡片绕自身竖中轴转 180° 交班给背面，600ms 强透视（更早的一版）。
 * 三档共用同一套结构：覆盖层在窗口级、两条栏动画期间不改布局、卡片矩形快照 + 裁剪揭示。
 */
private val FLIP_PROFILE = FlipProfile.GROW

/**
 * 三档动画，一行可切（lead 要求给用户留后路）：
 * - [FLIP_TURNSTILE]：flip 页面转场的本体 —— 整页绕「屏幕左外侧 40px 的竖轴」转（见 TURNSTILE_*），默认档；
 * - [EXPAND]：卡片矩形长到全屏 + 节点图在裁剪窗口里淡入（上一版）；
 * - [FLIP]：卡片绕自身竖中轴转 180° 交班给背面 + 自算射影透视，600ms。
 */
private enum class FlipProfile { FLIP_TURNSTILE, EXPAND, FLIP, SLAB, GROW }

/** [FlipProfile.FLIP] 档专用：总时长（毫秒）。其余两档各用自己的时间轴，不读它。 */
private const val FLIP_MS = 600

/**
 * 看门狗判据：进度连续静默这么久（毫秒）才认定卡死。
 * 比任何一条动画时长的抖动都长，又短到用户察觉不到。
 */
/**
 * 时间轴上的所有时长常量都必须是**交付值本身**，不许再乘任何倍数。
 *
 * 若将来要慢放取证（`screencap` 约 0.35 秒一帧，看不清 300ms 的曲线）：临时给时间轴乘一个倍数
 * （本项目用过 20，300ms → 6s，任何帧率都能逐帧量），取完证必须改回去 ——
 * 本项目曾把 60 秒的慢放版装到用户机上。它同时会放大按下回弹的时延（`PRESS_UNWIND_*`），
 * 改不回去时动画看起来就是「卡在一半」，而不是慢动作。
 */
private const val STUCK_MS = 400L

// ---------------------------------------------------------------------------
// turnstile：WinJS.UI.PageNavigationAnimation.turnstile（base.js 发行源码）
//
// 它跟「卡片长出来」完全是两回事：**整页**绕一条竖轴转，进场页和退场页同时转、共用同一条轴。
// 轴不在页面上：collectTurnstileTransformOrigins 给的是 LTR x = −40px（屏幕左**外侧** 40px）、
// y = 屏幕高 / 2 —— 所有元素共用这一条轴。
//
// 角度与曲线（逐字照 base.js）：
//   turnstileForwardIn   : perspective(600px) rotateY(80°) → 0°    300ms  (0.01, 0.975, 0.4775, 0.9775)
//   turnstileForwardOut  : perspective(600px) rotateY(0°)  → −50°  128ms  (0.4925, 0.01, 0.7675, −0.01)
//   turnstileBackwardIn  : perspective(600px) rotateY(−50°) → 0°   300ms  同 ForwardIn
//   turnstileBackwardOut : perspective(800px) rotateY(0°) → 80°    128ms  同 ForwardOut
// 每条另挂一条 opacity 轨道：进场 0→1 300ms cubic-bezier(0, 2, 0, 2)；退场 1→0 128ms
// cubic-bezier(1, −0.42, 0.995, −0.425)。
//
// 本应用映射：打开节点图 = forward（图页 80°→0°，同时列表页 0°→−50°）；
// 退出 = backward（列表页 −50°→0°，同时图页 0°→80°）。两边同时起步，图页那条 128ms 先跑完。
//
// 透视：官方 perspective(600px)。**CSS px 是设备无关单位 ≡ Android dp**：
// 本机 1440px / density 3 = 480dp 宽，正好就是 flip 手机端那个 480 宽的视口，
// 所以 600px ≡ 600.dp（本机 1800px），跟「按屏宽 1.25 倍」是同一个数，但 dp 才是它本来的语义。
// 必须随密度换算：1440px 宽若直接把 600 当 px 用，退场 −50° 时分母 P − d·sinθ 会变号
// （点跑到观察者身后），数学上是病态的。
// BackwardOut 官方单用 perspective(800px) ⇒ 那条 800.dp（本机 2400px）。
// 投影缩放 scale(d) = P / (P + d·sinθ)，d = x − originX：d 从 120px（屏最左）到 1560px（屏最右）；
// 80° 时（P=1800）左 0.9384 / 右 0.5395 ⇒ 左右边缘高度比 = 1.7393，与 flip 手机端逐位一致。
// （早前按 px 取 −40/P=1800 得 1.7710，差 1.8%，属二阶误差 —— 现在按 dp 走。）
// rotationY 取正 = 右边缘转进屏幕里（远离观察者、变小），正是用户在 30 题里选的方向。
// ---------------------------------------------------------------------------
// TEMP 慢放取证（本次抓帧专用）：把 turnstile 的动画时长整体乘这个倍数，
// 让 300ms 的转场拉长到能逐帧 screencap 的量级。轨道形状不受影响（四条轨道是 p 的函数，
// 只乘 durationMs）。**取证完必须改回 1 并回读确认** —— 本项目曾把 60 秒慢放版装到用户机上。
private const val SLOWMO = 1

private const val TURNSTILE_IN_MS = 300
private const val TURNSTILE_OUT_MS = 128
private const val TURNSTILE_IN_DEG = 80f
private const val TURNSTILE_LIST_DEG = -50f

/**
 * 转轴：屏幕左**外侧** [TURNSTILE_ORIGIN_X]、y = 屏幕中线。
 * 官方的 40 是 CSS px ≡ dp ⇒ −40.dp（本机 −120px）。
 */
private val TURNSTILE_ORIGIN_X = (-40).dp

/** ForwardIn / BackwardIn 的透视距离：官方 `perspective(600px)` ≡ 600.dp（本机 1800px）。 */
private val TURNSTILE_PERSPECTIVE_IN = 600.dp

/** BackwardOut 的透视距离：官方 `perspective(800px)` ≡ 800.dp（本机 2400px）。 */
private val TURNSTILE_PERSPECTIVE_OUT = 800.dp
private val TURNSTILE_IN_EASE = CubicBezierEasing(0.01f, 0.975f, 0.4775f, 0.9775f)
private val TURNSTILE_OUT_EASE = CubicBezierEasing(0.4925f, 0.01f, 0.7675f, -0.01f)
private val TURNSTILE_FADE_IN_EASE = CubicBezierEasing(0f, 2f, 0f, 2f)
private val TURNSTILE_FADE_OUT_EASE = CubicBezierEasing(1f, -0.42f, 0.995f, -0.425f)

// ---- 窄板档（SLAB）的四拍：全部时间都落在 [SLAB_TOTAL_MS] 这条轴上 ----
// ① 0–100ms   列表页缩到 0.42 并淡出
// ② 100–300ms 页板绕右竖轴转到 −76°，收成一条竖窄板
// ③ 300–430ms 窄板停一拍（角度不变）
// ④ 430–534ms 一帧扫到 −3.5°（残角），再收平到 0°
private const val SLAB_A_END = 100f
private const val SLAB_B_END = 300f
private const val SLAB_C_END = 430f
private const val SLAB_D_END = 467f
private const val SLAB_TOTAL_MS = 534f
private const val SLAB_EXIT_MS = 400
private const val SLAB_PANEL_DEG = -84f
private const val SLAB_REST_DEG = -3.5f
private const val SLAB_PANEL_SCALE = 0.42f
private const val SLAB_SCRIM_ALPHA = 0.32f
private val SLAB_PERSPECTIVE = 600.dp

// ---- 放大淡入档（GROW）：卡片从原位长到满屏，图页同时淡入 ----
private const val GROW_TOTAL_MS = 700f
private const val GROW_EXIT_MS = 520
private const val GROW_CARD_END = 490f
private const val GROW_FADE_START = 430f
private const val GROW_CARD_RADIUS = 16f

private fun growMs(p: Float): Float = p.coerceIn(0f, 1f) * GROW_TOTAL_MS

private fun slabMs(p: Float): Float = p.coerceIn(0f, 1f) * SLAB_TOTAL_MS

private fun slabListScaleAt(p: Float): Float =
    1f - 0.58f * FastOutSlowInEasing.transform((slabMs(p) / SLAB_A_END).coerceIn(0f, 1f))

private fun slabListAlphaAt(p: Float): Float =
    1f - (slabMs(p) / SLAB_A_END).coerceIn(0f, 1f)

private fun slabGraphAngleAt(p: Float): Float {
    val ms = slabMs(p)
    return when {
        ms <= SLAB_A_END -> 0f
        ms <= SLAB_B_END -> SLAB_PANEL_DEG * FastOutSlowInEasing.transform(
            ((ms - SLAB_A_END) / (SLAB_B_END - SLAB_A_END)).coerceIn(0f, 1f),
        )
        ms <= SLAB_C_END -> SLAB_PANEL_DEG
        ms <= SLAB_D_END -> {
            val q = ((ms - SLAB_C_END) / (SLAB_D_END - SLAB_C_END)).coerceIn(0f, 1f)
            SLAB_PANEL_DEG * (1f - q) + SLAB_REST_DEG * q
        }
        else -> {
            val q = ((ms - SLAB_D_END) / (SLAB_TOTAL_MS - SLAB_D_END)).coerceIn(0f, 1f)
            SLAB_REST_DEG * (1f - FastOutSlowInEasing.transform(q))
        }
    }
}

private fun slabGraphAlphaAt(p: Float): Float =
    ((slabMs(p) - SLAB_A_END) / 60f).coerceIn(0f, 1f)

private fun slabGraphScaleAt(p: Float): Float {
    val ms = slabMs(p)
    return when {
        ms <= SLAB_C_END -> SLAB_PANEL_SCALE
        ms <= SLAB_D_END -> {
            val q = ((ms - SLAB_C_END) / (SLAB_D_END - SLAB_C_END)).coerceIn(0f, 1f)
            SLAB_PANEL_SCALE + (1f - SLAB_PANEL_SCALE) * q
        }
        else -> 1f
    }
}

/** 正面转到 90°（侧面对着人）的那一刻：过这条线就交出背面 —— 节点图。FLIP 档专用。 */
private const val HANDOFF = 0.5f

/**
 * 交接瞬间「窗口」已经从卡片长出去的比例（FLIP 档专用）。
 * 留这一小段，是为了让「翻」和「放大」真的是同一段运动（一边翻一边长），而不是先翻完再放大；
 * 同时它决定了节点图第一次露出来的那个矩形 —— 正好等于同一时刻正面卡片的矩形，接得上、不跳帧。
 */
private const val HANDOFF_GROW = 0.16f

// ---- flip 档：逐字照 WinJS 的 expand / collapse 语义 ----
/** 进场：卡片矩形 367ms 长到全屏；节点图在窗口里 167ms 淡入、延迟 200ms（因为有 affected 元素）⇒ 总 367ms。 */
private const val FLIP_ENTER_MS = 367

/** 退场：节点图 167ms 淡出（延迟 0），卡片矩形 367ms 收回、延迟 167ms ⇒ 总 534ms。 */
private const val FLIP_EXIT_MS = 534

/** 位移（卡片矩形长大 / 收回）与淡入淡出的时长。 */
private const val FLIP_MOVE_MS = 367
private const val FLIP_FADE_MS = 167
private const val FLIP_FADE_DELAY_ENTER = 200
private const val FLIP_FADE_DELAY_EXIT = 0

/** 招牌曲线：WinJS 的 cubic-bezier(0.1, 0.9, 0.2, 1)。 */
private val FLIP_EASE = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

/** 退场曲线：WinJS 的 cubic-bezier(0.11, 0.5, 0.24, 0.96)，整个 WinJS 只有两处用它。 */
private val FLIP_EASE_EXIT = CubicBezierEasing(0.11f, 0.5f, 0.24f, 0.96f)

/**
 * 按下姿态：用户明确不要 flip 磁贴那套「鼠标长按」变形（原来的 TILT_MAX_DEG 17.1887°、
 * 后退缩小 0.965、变暗 0.06、朝屏幕中心偏的轴心 0.22 —— 整条 TiltEffect 链已删除）。
 * 现在只有两个缩放值：按下缩一点、长按放大一点。
 */

/** 长按（≥500ms）后的缩放：单纯放大，不倾斜、不变暗。 */
private const val PRESS_HOLD_SCALE = 1.06f

/** 松手后先等 200ms，再用 100ms 线性回弹（沿用 flip TiltEffect 的 ACTION_UP 时间表，只作用于缩放）。 */
private const val PRESS_UNWIND_DELAY_MS = 200L
private const val PRESS_UNWIND_MS = 100

/**
 * 透视相机距离 = 这个系数 × 卡片窗口当前的像素宽（自己做透视，不用 graphicsLayer.cameraDistance ——
 * 用户在真机上实测过：那台机器无论把 cameraDistance 设成 3.5 / 1.2 / 0.3 倍宽，
 * 剪影左右两侧的高度比都是 1.00/0.99/0.98，也就是根本没生效、就是正投影）。
 *
 * 推导：绕卡片竖直中轴转 θ 时，屏幕上的半宽是 a·cosθ，深度是 ±a·sinθ（a = 窗口半宽 = w/2）。
 * 相机在屏幕外 d 处，投影缩放 = d / (d ± a·sinθ)，近边比远边高多少就是两条边的缩放比：
 *     ratio(θ) = (d + a·sinθ) / (d - a·sinθ)
 * 45° 时 a·sinθ = 0.707a；要让 ratio 落在 1.3~1.8，解出 d ∈ [2.47a, 5.42a] = [1.24w, 2.71w]。
 * 现在取 1.5w（= 3a）：45° 时 ratio = 3.707 / 2.293 ≈ 1.62 —— 落在 1.3~1.8 里偏强的一档，
 * 也就是比区间下端明显更「近大远小」。真机上按这个值实测剪影：左右两侧高度 595 / 397 = 1.50。
 */
private const val PERSPECTIVE_DISTANCE_FACTOR = 1.5f

/** 卡片圆角（和 ProgramTile 里一致），放大到全屏的过程中收到 0，免得全屏节点图四角顶出圆角。 */
private const val TILE_CORNER_DP = 16f

/** 长按弹出菜单后，手指再移动这么多（dp）才转成拖动排序。 */
private const val DRAG_THRESHOLD_DP = 8

/** 卡片按下的缩放反馈。 */
private const val PRESS_SCALE = 0.94f

/** 卡片上下间距（dp），排序时按「卡片高 + 间距」折算槽位。 */
private const val CARD_GAP_DP = 12

/** 一张卡片最多画几个颜色点，再多用 +N 收尾。 */
private const val MAX_PREVIEW_DOTS = 12

/**
 * 按「指针 y」算拖到哪里：拿每张卡片实测的根坐标 top 做比较，
 * 落在某张卡片中线上方就插到它前面；全都比过还没找到，就是插到最后。
 * 用实测位置而不是猜行高，卡片高度变了也不会错位。
 */
private fun dragTargetIndexAt(
    pointerY: Float,
    programs: List<Program>,
    cardFrames: Map<String, CardFrame>,
    fallback: Int,
): Int {
    if (programs.isEmpty()) return fallback
    for (i in programs.indices) {
        val frame = cardFrames[programs[i].id] ?: continue
        // 从上往下找第一张「指针在它中线以上」的卡片，那就是要插进去的位置。
        // 这里必须是真正的 find-first：以前用 return@forEachIndexed 当 break 使，
        // 循环会一路跑到底，于是永远返回最后一张卡片 —— 拖到哪儿都掉到末尾。
        if (pointerY < frame.top + frame.height / 2f) return i
    }
    return programs.size - 1
}

@Composable
private fun ProgramListView(
    programs: List<Program>,
    listState: LazyListState,
    listAlpha: Float,
    draggingId: String?,
    draggingFromIndex: Int,
    draggingGrabTop: Float,
    dragPointerDeltaY: Float,
    listTopInRoot: Float,
    onListTopChange: (Float) -> Unit,
    onCardFrame: (String, CardFrame) -> Unit,
    onOpen: (Program, CardTilt) -> Unit,
    onLongPress: (Program) -> Unit,
    onRename: (Program) -> Unit,
    onCopy: (Program) -> Unit,
    onDelete: (Program) -> Unit,
    onDragStart: (Program, Int, Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDrop: (Float) -> Unit,
    onNewProgram: () -> Unit,
    /** 正在翻出去的那张卡片的 id：只有它自己让位（交给覆盖层里那份真卡片），别的不动、列表也不褪色 */
    pageFlippingId: String?,
    cardFrames: Map<String, CardFrame>,
) {
    // 手指现在在列表的哪个槽位（拖动时其他卡片照这个让位）
    val dragTargetIndex = if (draggingFromIndex >= 0) {
        dragTargetIndexAt(
            pointerY = draggingGrabTop + dragPointerDeltaY + 1f,
            programs = programs,
            cardFrames = cardFrames,
            fallback = draggingFromIndex,
        )
    } else {
        -1
    }
    val gapPx = with(LocalDensity.current) { CARD_GAP_DP.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = listAlpha },
    ) {
        if (programs.isEmpty()) {
            ClickText(
                text = stringResource(R.string.program_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                // 不要在这里补状态栏/挖孔内边距：外壳（MainScaffold）已经处理过一轮，
                // 再补一次会让第一张卡片比主页的按钮低一整条状态栏
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { onListTopChange(it.boundsInRoot().top) },
                // 拖动排序期间不让列表自己滚，免得两个手势打架
                userScrollEnabled = draggingId == null,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    // 底部留出「新建」按钮和导航条的位置
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
            ) {
                items(programs, key = { it.id }) { program ->
                    ProgramEntry(
                        program = program,
                        index = programs.indexOf(program),
                        dragging = draggingId == program.id,
                        draggingAnything = draggingId != null,
                        flipping = pageFlippingId == program.id,
                        draggingFromIndex = draggingFromIndex,
                        dragTargetIndex = dragTargetIndex,
                        dragPointerDeltaY = dragPointerDeltaY,
                        onCardFrame = onCardFrame,
                        onOpen = { tilt -> onOpen(program, tilt) },
                        onLongPress = { onLongPress(program) },
                        onRename = { onRename(program) },
                        onCopy = { onCopy(program) },
                        onDelete = { onDelete(program) },
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDrop = onDrop,
                    )
                }
            }
        }

        // 右下角「新建程序」
        // 动画期间不画「新建」按钮：它在翻转层之上，最后一刻消失时看着就是一次跳变
        if (LocalProgramFlip.current?.animating != true) FloatingActionButton(
            onClick = onNewProgram,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                )
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.program_new),
            )
        }
    }
}

/**
 * 列表里的一张卡片：外层负责「让位」和抬起来，里层才是 [ProgramTile]。
 *
 * 被拖的那张跟着手指上下走；手指越过别人的槽位时，那些卡片整体平移一格让出位置；
 * 松手才真正改顺序并写回 [ProgramStore.reorder]。
 */
@Composable
private fun ProgramEntry(
    program: Program,
    index: Int,
    dragging: Boolean,
    draggingAnything: Boolean,
    /** 这张卡片正在翻出去：本体隐藏，动画层上的副本才是「按钮自己在动」 */
    flipping: Boolean,
    draggingFromIndex: Int,
    dragTargetIndex: Int,
    dragPointerDeltaY: Float,
    onCardFrame: (String, CardFrame) -> Unit,
    /** 点开这张卡片：把按下姿态（现在只有缩放）一起交出去，进场动画从那个姿态接着走 */
    onOpen: (CardTilt) -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: (Program, Int, Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDrop: (Float) -> Unit,
) {
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    /** 长按已经触发：这时只放大（用户要的「长按就单纯放大动画」），不做任何倾斜/变形 */
    var heldLong by remember { mutableStateOf(false) }

    val gapPx = with(density) { CARD_GAP_DP.dp.toPx() }
    val scale by animateFloatAsState(
        // 只有缩放：按下缩一点、按住/长按放大一点、拖起来再大一点，松手回 1。
        // （原来那条 flip TiltEffect：rotationX/rotationY + 轴心 + 变暗，用户明确不要，已删）
        targetValue = when {
            dragging -> 1.03f
            heldLong -> PRESS_HOLD_SCALE
            pressed && !draggingAnything -> PRESS_SCALE
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (pressed) 90 else 160, easing = FastOutSlowInEasing),
        label = "programCardPress",
    )

    // 被拖的：跟手上下移动
    val selfOffset = if (dragging) dragPointerDeltaY else 0f
    // 其他卡片：手指越过了自己就让一格
    val shift = if (!dragging && draggingFromIndex >= 0 && rowHeight > 0f) {
        when {
            draggingFromIndex < index && dragTargetIndex >= index -> -(rowHeight + gapPx)
            draggingFromIndex > index && dragTargetIndex <= index -> rowHeight + gapPx
            else -> 0f
        }
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .onSizeChanged {
                if (rowHeight <= 0f) rowHeight = it.height.toFloat()
            }
            // 手势挂在不动的这一层：卡片被平移时手势坐标会跟着卡片跑，
            // 「手指没动坐标却变了」会自己喂自己 —— 拖动抽搐的来源
            .programTileGestures(
                // 点开时把「按下时的缩放」交给动画层：进场从这个缩放接着走
                onTap = { onOpen(CardTilt(scale = if (heldLong) PRESS_HOLD_SCALE else PRESS_SCALE)) },
                onLongPress = {
                    heldLong = true
                    onLongPress()
                },
                onPressChange = { down, _ ->
                    pressed = down
                    if (!down) heldLong = false
                },
                dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() },
                onDragStart = { grabOffsetY ->
                    pressed = false
                    heldLong = false
                    onDragStart(program, index, grabOffsetY)
                },
                onDrag = onDrag,
                onDragEnd = onDrop,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = selfOffset + shift },
        ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ProgramTile(
                program = program,
                actions = { ProgramTileActions(onRename = onRename, onCopy = onCopy, onDelete = onDelete) },
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (dragging) 10.dp else 0.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = false,
                    )
                    .graphicsLayer {
                        // 只有缩放：按下缩一点、长按放大一点，松手回 1。
                        // 没有倾斜、没有透视、没有变暗 —— 用户明确不要 flip 磁贴那套按下变形。
                        scaleX = scale
                        scaleY = scale
                        // EXPAND / FLIP 档：正在翻出去的这张本体藏起来，屏幕上只剩动画层里的同一张卡片，
                        // 看起来就是「你按的那个按钮自己在翻」。
                        // turnstile 档 flipping 恒为 false（那边没有副本层，藏了收尾时会整张蹦回来）。
                        alpha = if (flipping) 0f else 1f
                    }
                    .onGloballyPositioned {
                        val b = it.boundsInRoot()
                        onCardFrame(program.id, CardFrame(b.left, b.top, b.width, b.height))
                    },
            )
        }
        }
    }
}

/**
 * 卡片右下角的三个动作按钮：重命名 / 复制 / 删除。
 *
 * 用户要求照“滑动手势”那一行的做法（铅笔 + 垃圾桶），不要长按弹菜单：
 * 长按留给拖动排序，松手绝不会打开程序。
 */
@Composable
private fun ProgramTileActions(
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(
            Triple(R.drawable.ic_edit, R.string.program_rename, onRename),
            Triple(R.drawable.ic_content_copy, R.string.program_duplicate, onCopy),
            Triple(R.drawable.ic_delete, R.string.program_delete, onDelete),
        ).forEach { (iconRes, descRes, action) ->
            IconButton(onClick = action, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = stringResource(descRes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
/** 磁贴本体：程序名 + 节点数 + 节点配色预览。列表和翻转覆盖层共用同一个。 */
@Composable
private fun ProgramTile(
    program: Program?,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                ClickText(
                    text = program?.name.orEmpty(),
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ClickText(
                    text = stringResource(R.string.program_node_count, program?.nodes?.size ?: 0),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { ProgramColorPreview(program) }
                actions?.invoke()
            }
        }
    }
}

/** 节点配色预览：按节点顺序画一排小圆点，颜色取 node.color。 */
@Composable
private fun ProgramColorPreview(program: Program?) {
    val nodes = program?.nodes.orEmpty()
    if (nodes.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = nodes.take(MAX_PREVIEW_DOTS)
        shown.forEach { node ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(node.color)),
            )
        }
        val rest = nodes.size - shown.size
        if (rest > 0) {
            ClickText(
                text = "+$rest",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/*
 * 点击 / 长按 / 长按后拖动 三合一。
 *
 * 没有用 detectDragGesturesAfterLongPress：要的顺序是「长按先抓起来（卡片立刻浮起，有反馈），
 * 手指真的移动超过阈值才开始重排」，而不是一判定长按就换位。
 */
private fun Modifier.programTileGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    /** 按下 / 抬起，外加按下点在卡片里的位置（flip 的倾斜姿态要用它算） */
    onPressChange: (Boolean, Offset) -> Unit,
    dragThresholdPx: Float,
    onDragStart: (grabOffsetY: Float) -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: (deltaY: Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onPress = { pos ->
                onPressChange(true, pos)
                tryAwaitRelease()
                onPressChange(false, pos)
            },
            // 传上 onLongPress：长按超时之后抬手就不会再触发 onTap。
            // 长按松手绝不能变成「打开程序」，它只负责把卡片拖起来排序。
            onLongPress = { onLongPress() },
            onTap = { onTap() },
        )
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            // 卡片内的抓取点（本地坐标），抬手时用来还原手指在根坐标里的位置
            val grabOffsetY = down.position.y
            // 手指相对按下点的位移：手势坐标是卡片本地的，按位置变化累加即可
            var deltaY = 0f
            var lastY = down.position.y

            // ---- 阶段一：等长按超时；中途移动超过阈值或抬手都不算长按 ----
            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            val touchSlop = viewConfiguration.touchSlop
            var longPressed = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break
                if (change.positionChanged() && (change.position - down.position).getDistance() > touchSlop) break
                if (change.uptimeMillis >= deadline) {
                    longPressed = true
                    break
                }
            }
            if (!longPressed) return@awaitEachGesture

            // ---- 阶段二：长按到点就立刻抓起来（卡片浮起、有反馈），
            //      但只有手指真的移动过阈值才算「在重排」，免得手一抖就换位 ----
            onLongPress()
            onDragStart(grabOffsetY)
            var reordering = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break
                deltaY += change.position.y - lastY
                lastY = change.position.y
                if (!reordering && (change.position - down.position).getDistance() > dragThresholdPx) {
                    reordering = true
                }
                if (reordering) onDrag(deltaY)
                // 卡片已经抓起来了：吃掉事件，别让列表跟着滚
                change.consume()
            }
            // 没移动过（长按后原地松手）就交回 0：父层据此什么都不换、卡片原地放回
            onDragEnd(if (reordering) deltaY else 0f)
        }
    }
