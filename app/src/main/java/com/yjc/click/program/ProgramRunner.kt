package com.yjc.click.program

import android.graphics.Path
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「程序」的执行引擎。
 *
 * 并发模型（用户逐条确认过的语义）：
 * - 从 START 开始走；一个节点有 1 条出线就接着走，有多条出线就是**并行支线**，每条一个协程。
 * - 支线之间按各自的延时/节奏独立推进，**互不等待、不汇合**；没有出线这条支线就结束；
 *   所有支线都结束 = 程序结束（有无限循环的支线就一直跑，直到用户停止）。
 * - LOOP 回到目标节点后**从那里继续走它的出线**，不是重新起一条并发支线。
 *
 * 多指并发：所有支线的动作都投进一个队列，[ActionDispatcher] 用 ~16ms 的聚合窗口把同一
 * 窗口到期的动作合并成**一次多 stroke 的手势**（最多 10 指），也就是让两根手指同时按在
 * 不同位置上；窗口里只有一个动作时就退化成单指手势。
 *
 * 为什么动作必须串行派发：dispatchGesture 每次派发都会取消系统里正在进行的手势，
 * 所以只能等上一批手势回调结束再派发下一批 —— 批内的多指是并发的，批与批之间是串行的。
 */
class ProgramRunner(
    val program: Program,
    private val listener: BranchListener,
    /** 真正派发手势的地方，由 ProgramService 接给 ClickAccessibilityService。 */
    private val dispatch: suspend (List<Action>) -> Boolean,
) {
    /** 每条支线的状态回调，ProgramService 用它维护悬浮球与通知。 */
    interface BranchListener {
        fun onBranchStart(branchId: String, node: ProgramNode)

        /** 支线换到新节点（球跟着换位置和颜色）。 */
        fun onBranchStep(branchId: String, node: ProgramNode)

        /** 支线停在 WAIT 节点等用户点球。 */
        fun onBranchWaiting(branchId: String, node: ProgramNode)

        /** 支线结束（球消失）。 */
        fun onBranchEnd(branchId: String)

        /** 所有支线都跑完了。 */
        fun onAllDone()
    }

    /** 一条待派发的手势。 */
    class Action(
        val path: Path,
        val durationMs: Long,
        /** 队列里的票据，只用于日志/排查。 */
        val id: Long = seq.incrementAndGet().toLong(),
    ) {
        companion object {
            private val seq = AtomicInteger(0)

            /** 点击：按压时长固定 100ms，沿用旧的点击系统。 */
            private const val CLICK_PRESS_MS = 100L

            /** 点击：点在同一点上。 */
            fun click(x: Int, y: Int): Action {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                return Action(path, CLICK_PRESS_MS)
            }

            /** 滑动：走录制的轨迹，或起点→终点直线。 */
            fun swipe(path: Path, durationMs: Long): Action =
                Action(path, durationMs.coerceAtLeast(1L))
        }
    }

    /** 根作用域：SupervisorJob 保证一条支线崩了不影响别的支线。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val branches = ConcurrentHashMap<String, Branch>()
    private val branchSeq = AtomicInteger(0)
    private val pausedFlag = AtomicBoolean(false)

    /** 暂停闸门：所有支线一起停 / 一起继续。 */
    @Volatile
    private var gate: CompletableDeferred<Unit>? = null
    private val gateLock = Any()

    /** 动作队列：所有支线往里投，派发循环从里面取。 */
    private val mailbox = Channel<Pair<Branch, Action>>(Channel.UNLIMITED)

    @Volatile
    private var started = false

    @Volatile
    private var stopped = false

    val isPaused: Boolean get() = pausedFlag.get()

    val isStopped: Boolean get() = stopped

    /** 当前存活的支线 id（只读快照）。 */
    fun branchIds(): List<String> = branches.keys.toList()

    // ------------------------------------------------------------------
    // 对外控制
    // ------------------------------------------------------------------

    /** 从 START 节点起跑。 */
    fun start() {
        if (started) return
        started = true
        val startNode = program.start
        if (startNode == null) {
            Log.e(TAG, "start: no START node, program=${program.id}")
            stopped = true
            listener.onAllDone()
            return
        }
        launchBranch(startNode)
        // 巡检：所有支线都没了 = 程序结束
        scope.launch {
            while (isActive) {
                delay(ALL_DONE_POLL_MS)
                if (branches.isEmpty()) {
                    Log.d(TAG, "all branches finished, program done")
                    listener.onAllDone()
                    return@launch
                }
            }
        }
    }

    /** 用户点球继续 WAIT；返回 true 表示确实有一条支线在等。 */
    fun resumeWait(branchId: String?): Boolean {
        val list = if (branchId != null) listOfNotNull(branches[branchId]) else branches.values.toList()
        var resumed = false
        list.forEach { if (it.resumeCurrentWait()) resumed = true }
        return resumed
    }

    /** 双击球：暂停/继续（所有支线一起）。返回切换后的状态（true = 已暂停）。 */
    fun togglePause(): Boolean {
        if (pausedFlag.get()) resume() else pause()
        return pausedFlag.get()
    }

    fun pause() {
        if (stopped) return
        synchronized(gateLock) {
            if (pausedFlag.get()) return
            // 先放闸门再翻标志位：翻标志位是 volatile 写，
            // 之后任何线程读到「已暂停」都能看见这个闸门
            gate = CompletableDeferred()
            pausedFlag.set(true)
        }
        Log.d(TAG, "paused")
    }

    fun resume() {
        synchronized(gateLock) {
            if (!pausedFlag.get()) return
            pausedFlag.set(false)
            gate?.complete(Unit)
            gate = null
        }
        Log.d(TAG, "resumed")
    }

    /** 停掉一切：取消根作用域（连带所有支线）、清空状态。 */
    fun stop() {
        if (stopped) return
        stopped = true
        // 先把所有卡在挂起点上的支线放出来，免得它们被取消时留下未收尾的通道
        branches.values.forEach { it.releaseWait() }
        branches.clear()
        gate?.complete(Unit)
        gate = null
        mailbox.close()
        dispatcher.stop()
        scope.cancel()
        Log.d(TAG, "stopped")
    }

    // ------------------------------------------------------------------
    // 支线
    // ------------------------------------------------------------------

    private fun launchBranch(node: ProgramNode) {
        val branch = Branch("b" + branchSeq.incrementAndGet(), node)
        branches[branch.id] = branch
        branch.job = scope.launch { runBranch(branch) }
    }

    /**
     * 一条支线的主协程：跑完自己的节点链后收尾。
     *
     * 注意这里**不取消**它 fork 出去的兄弟支线 —— 支线之间互不等待、也不互相牵连，
     * 用户停止时是靠整个作用域一起取消的。
     */
    private suspend fun runBranch(branch: Branch) {
        try {
            branch.current?.let { listener.onBranchStart(branch.id, it) }
            walk(branch)
        } catch (e: CancellationException) {
            // 用户 stop 时为正常路径
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "branch ${branch.id} crashed", e)
        } finally {
            branches.remove(branch.id)
            branch.releaseWait()
            listener.onBranchEnd(branch.id)
        }
    }

    /**
     * 一条支线的主循环。
     *
     * LOOP 的回跳不在这里做 —— [nextNode] 会把回跳算进去，从目标节点继续走它的出线。
     */
    private suspend fun walk(branch: Branch) {
        var guard = 0
        while (true) {
            awaitGate()
            val node = branch.current
            if (node == null) {
                Log.w(TAG, "branch ${branch.id}: node not found, ending")
                return
            }
            if (node.type != ProgramNodeType.START) {
                listener.onBranchStep(branch.id, node)
            }
            runNode(branch, node)
            val next = nextNode(branch, node) ?: return
            branch.current = next
            // 分叉：有多条出线时，第一条自己走，其余各起一条支线
            forkAround(node)
            guard++
            if (guard >= MAX_STEPS_PER_BRANCH) {
                Log.w(TAG, "branch ${branch.id}: step limit reached, ending")
                return
            }
        }
    }

    /**
     * 只在「这个节点的出线不止一条、且不是回跳」时开新支线。
     *
     * 回跳（LOOP 指向的目标）不在这里分叉，回到目标节点后由目标节点自己的出线决定怎么走。
     */
    private fun forkAround(node: ProgramNode) {
        if (node.type == ProgramNodeType.LOOP) return
        val outs = program.outEdges(node.id)
        if (outs.size <= 1) return
        // 第一条已经由本协程接手了，这里只开剩下的
        outs.drop(1).forEach { edge ->
            val child = program.node(edge.toId) ?: return@forEach
            if (branches.size >= MAX_BRANCHES) {
                Log.w(TAG, "too many branches (>=$MAX_BRANCHES), skipping ${child.id}")
                return@forEach
            }
            launchBranch(child)
        }
    }

    /** 算出下一个该走的节点；null = 这条支线到头了。 */
    private fun nextNode(branch: Branch, node: ProgramNode): ProgramNode? {
        if (node.type == ProgramNodeType.LOOP) {
            val times = branch.loopCounts[node.id] ?: 0
            // loopCount = 0（或负数）表示无限循环
            val shouldLoop = node.loopCount <= 0 || times < node.loopCount
            if (shouldLoop) {
                branch.loopCounts[node.id] = times + 1
                // loopTargetId = null（= 回 START）或目标节点已被删掉，都回 START
                val target = node.loopTargetId?.let { program.node(it) } ?: program.start
                if (target != null) return target
            }
        }
        return firstOut(node)
    }

    /** 只取第一条出线：走主路径；其余的支线由 [forkAround] 负责。 */
    private fun firstOut(node: ProgramNode): ProgramNode? =
        program.outEdges(node.id).firstOrNull()?.let { program.node(it.toId) }

    private suspend fun runNode(branch: Branch, node: ProgramNode) {
        when (node.type) {
            ProgramNodeType.START, ProgramNodeType.LOOP -> {
                // START 没有动作；LOOP 的回跳由 nextNode 负责
            }

            ProgramNodeType.CLICK -> {
                val repeats = node.repeatCount.coerceAtLeast(1)
                for (i in 0 until repeats) {
                    awaitGate()
                    runAction(branch, Action.click(node.pointX, node.pointY))
                    if (i < repeats - 1) delay(REPEAT_GAP_MS)
                }
            }

            ProgramNodeType.SWIPE -> {
                val repeats = node.repeatCount.coerceAtLeast(1)
                for (i in 0 until repeats) {
                    awaitGate()
                    val path = swipePath(node)
                    if (path == null) {
                        Log.e(TAG, "swipe node ${node.id}: invalid path, skipped")
                    } else {
                        runAction(branch, Action.swipe(path, node.durationMs.toLong()))
                    }
                    if (i < repeats - 1) delay(REPEAT_GAP_MS)
                }
            }

            ProgramNodeType.DELAY -> {
                if (node.delayMs > 0) delay(node.delayMs)
            }

            ProgramNodeType.WAIT -> when (node.waitMode) {
                WaitMode.TIME -> waitUntil(node.waitUntilMs)
                WaitMode.USER -> {
                    listener.onBranchWaiting(branch.id, node)
                    branch.awaitUser()
                    // 用户点完球之后不该立刻冲出去，留一点反应时间
                    delay(AFTER_WAIT_RESUME_MS)
                }
            }
        }
    }

    /** 投一个动作给派发队列，并等它（可能和别的支线合并成一次多指手势）跑完。 */
    private suspend fun runAction(branch: Branch, action: Action) {
        if (stopped) return
        if (!mailbox.trySend(branch to action).isSuccess) return
        val ok = branch.resultChannel.receiveCatching().getOrNull() ?: false
        if (!ok && !stopped) Log.w(TAG, "gesture not completed (action ${action.id})")
    }

    /**
     * 等到当天的 waitUntilMs 时刻。
     *
     * waitUntilMs 是**当天从 0 点起的毫秒数**（08:00 = 8 * 3600_000），不是时间戳，
     * 所以先按本地时区换算到「今天 0 点」；今天这个点已经过了就等明天那个点。
     */
    private suspend fun waitUntil(waitUntilMs: Long) {
        val nowMs = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(nowMs).toLong()
        // offset 可能为负，先归一到 [0, DAY) 再算当天 0 点的绝对毫秒
        val dayStart = Math.floorDiv(nowMs + offset, DAY_MS) * DAY_MS - offset
        var target = dayStart + waitUntilMs.coerceIn(0L, DAY_MS)
        if (target <= nowMs) target += DAY_MS
        val wait = (target - nowMs).coerceAtLeast(0L)
        Log.d(TAG, "wait until $target (${wait}ms later)")
        delay(wait)
    }

    private fun swipePath(node: ProgramNode): Path? = runCatching {
        val points = node.swipePoints()
        if (points.size < 2) return@runCatching null
        // 负坐标的手势必然派发失败，直接拒绝
        if (points.any { it[0] < 0f || it[1] < 0f }) return@runCatching null
        val path = Path()
        path.moveTo(points[0][0], points[0][1])
        for (i in 1 until points.size) path.lineTo(points[i][0], points[i][1])
        path
    }.getOrElse {
        Log.e(TAG, "build swipe path failed", it)
        null
    }

    private suspend fun awaitGate() {
        if (!pausedFlag.get()) return
        gate?.await()
    }

    // ------------------------------------------------------------------
    // 动作派发队列：多指合并
    // ------------------------------------------------------------------

    /**
     * 聚合窗口派发器。
     *
     * 从 [mailbox] 取第一个到期动作，然后最多再等 [WINDOW_MS] 收集同一窗口到期的其余动作，
     * 一起交给 ProgramService 派发：
     * - 只有 1 个 → 单指手势；
     * - 多个 → 一次多 stroke 的 GestureDescription，也就是多根手指同时按在不同位置。
     *
     * 单条支线的动作天然是串行的：它要等上一个动作的结果回来才会投下一个，
     * 所以同一批次里不可能出现同一条支线的两个动作。
     */
    private inner class ActionDispatcher {
        private var loop: Job? = null

        fun start(scope: CoroutineScope) {
            loop = scope.launch { runLoop() }
        }

        private suspend fun runLoop() {
            while (scope.isActive) {
                // 邮箱被关掉（stop）时 receiveCatching 返回失败，循环结束
                val first = mailbox.receiveCatching().getOrNull() ?: return
                val batch = ArrayList<Pair<Branch, Action>>(MAX_FINGERS)
                batch.add(first)
                val deadline = System.currentTimeMillis() + WINDOW_MS
                while (batch.size < MAX_FINGERS) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    val next = nextWithin(left) ?: break
                    batch.add(next)
                }
                dispatchBatch(batch)
            }
        }

        /** 在 [timeoutMs] 内取下一个动作；超时或邮箱关闭都返回 null。 */
        private suspend fun nextWithin(timeoutMs: Long): Pair<Branch, Action>? = try {
            withTimeoutOrNull(timeoutMs) { mailbox.receiveCatching().getOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "next action failed", e)
            null
        }

        private suspend fun dispatchBatch(batch: List<Pair<Branch, Action>>) {
            if (batch.isEmpty()) return
            val ok = try {
                if (stopped || !scope.isActive) false else dispatch(batch.map { it.second })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "dispatch batch failed", e)
                false
            }
            batch.forEach { (branch, _) -> branch.resultChannel.trySend(ok) }
        }

        fun stop() {
            loop?.cancel()
            loop = null
        }
    }

    private val dispatcher = ActionDispatcher()
    private var dispatcherStarted = false

    /** 由 ProgramService 在 [start] 之前调用，把派发循环拉起来。 */
    fun startDispatcher() {
        if (dispatcherStarted) return
        dispatcherStarted = true
        dispatcher.start(scope)
    }

    /**
     * 把一批动作拆成服务要的 stroke 三元组：`(path, startTimeMs, durationMs)`。
     * 所有 stroke 的 startTime 对齐成 0，也就是同时按下。
     */
    fun toStrokes(actions: List<Action>): List<Triple<Path, Long, Long>> =
        actions.map { Triple(it.path, 0L, it.durationMs.coerceAtLeast(1L)) }

    /** 一条支线。 */
    private inner class Branch(val id: String, var current: ProgramNode?) {
        var job: Job? = null
        val loopCounts = HashMap<String, Int>()

        /** 本支线自己的结果通道：一次只等一个动作的结果。 */
        val resultChannel = Channel<Boolean>(Channel.CONFLATED)

        @Volatile
        private var currentWait: CompletableDeferred<Unit>? = null

        /** 等用户点一下球继续。 */
        suspend fun awaitUser() {
            val deferred = CompletableDeferred<Unit>()
            currentWait = deferred
            try {
                deferred.await()
            } finally {
                currentWait = null
            }
        }

        fun resumeCurrentWait(): Boolean {
            val d = currentWait ?: return false
            d.complete(Unit)
            return true
        }

        /** 程序停止 / 支线收尾时，把所有挂起点放出来并关掉通道。 */
        fun releaseWait() {
            currentWait?.complete(Unit)
            currentWait = null
            // 还在等结果的支线要收到一个明确结果，否则会一直挂到协程被取消
            resultChannel.trySend(false)
            resultChannel.close()
        }
    }

    companion object {
        const val TAG = "ProgramRunner"

        /** 聚合窗口：同一 ~16ms 内到期的动作合并成一次多指手势。 */
        const val WINDOW_MS = 16L

        /** 一次手势最多 10 指（系统上限，服务端还会再用 GestureDescription 的上限兜一次）。 */
        const val MAX_FINGERS = 10

        /** 同一个节点重复点击/滑动之间的间隔。 */
        private const val REPEAT_GAP_MS = 60L

        /** 用户点球继续后，稍微缓冲一下再发下一个动作。 */
        private const val AFTER_WAIT_RESUME_MS = 120L

        /**
         * 一条支线最多走这么多步，纯粹是防「图被连成没有动作的死循环把 CPU 打满」的兜底。
         * 正常的无限循环（有动作、有延时）远远走不到这个数。
         */
        private const val MAX_STEPS_PER_BRANCH = 1_000_000

        /** 同时存在的支线上限，防止失控扇出。 */
        private const val MAX_BRANCHES = 64

        /** 「所有支线都结束了」的巡检间隔。 */
        private const val ALL_DONE_POLL_MS = 50L

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
