package com.yjc.click.program

import androidx.annotation.StringRes
import com.yjc.click.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 「程序」系统的数据模型。
 *
 * 术语：一个程序 = 一个预设 = 一张有向图。
 * - 图里必有一个 START 节点（自带，紫色悬浮球，只出不进）。
 * - 没有「结束」节点：一个节点没有出线，这条支线就跑完了。
 * - 一个节点有多条出线 = 并行支线，真正同时执行（靠多指手势）。
 * - 不汇合：每条支线各自跑到头。
 */
enum class ProgramNodeType {
    /** 每个程序自带的起点，紫色球，只出不进。 */
    START,

    /** 点击一个坐标，可重复。 */
    CLICK,

    /** 从起点滑到终点（或复现录制轨迹），可重复。 */
    SWIPE,

    /** 等固定毫秒数。 */
    DELAY,

    /** 等用户点悬浮球，或等到当天某个时刻。 */
    WAIT,

    /** 回到前面某个节点再跑，跑 N 次（0 = 无限）。 */
    LOOP,
}

/** WAIT 节点的两种模式。 */
enum class WaitMode { USER, TIME }

/** 悬浮球 / 节点配色（开始节点默认用第一个）。 */
val PROGRAM_COLORS: List<Int> = listOf(
    0xFF6750A4.toInt(), // 主题紫
    0xFFE53935.toInt(), // 红
    0xFFFB8C00.toInt(), // 橙
    0xFFFDD835.toInt(), // 黄
    0xFF43A047.toInt(), // 绿
    0xFF00ACC1.toInt(), // 青
    0xFF1E88E5.toInt(), // 蓝
    0xFF8E24AA.toInt(), // 紫
    0xFFD81B60.toInt(), // 玫红
    0xFF6D4C41.toInt(), // 棕
)

/** 球的默认尺寸（dp）。 */
const val BALL_DIAMETER_DP = 44
/** 球停在动作点旁边的偏移（dp），避免挡住真正要点的位置。 */
const val BALL_OFFSET_DP = 52

data class ProgramNode(
    val id: String,
    val type: ProgramNodeType,
    /** 节点在画布上的位置。 */
    val x: Float,
    val y: Float,
    val color: Int = PROGRAM_COLORS[0],

    // ---- CLICK / SWIPE ----
    val pointX: Int = 0,
    val pointY: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    /** 滑动总时长（毫秒）。 */
    val durationMs: Int = 300,
    /** 录制的轨迹（屏幕坐标），空 = 用 pointX/pointY → endX/endY 直线滑。 */
    val path: List<List<Float>> = emptyList(),
    /** 引用的手势名（只用于显示，可以是空）。 */
    val gestureName: String = "",
    /** 节点自身重复次数。 */
    val repeatCount: Int = 1,

    // ---- DELAY ----
    val delayMs: Long = 1000,

    // ---- WAIT ----
    val waitMode: WaitMode = WaitMode.USER,
    /** WAIT + TIME 模式：当天的毫秒数（08:00 = 8 * 3600_000）。 */
    val waitUntilMs: Long = 0,

    // ---- LOOP ----
    /** 回跳目标；null = 回到 START。 */
    val loopTargetId: String? = null,
    /** 循环次数，0 = 无限。 */
    val loopCount: Int = 1,
) {
    val isAction: Boolean get() = type == ProgramNodeType.CLICK || type == ProgramNodeType.SWIPE

    /** 滑动轨迹用于画缩略图的点集（屏幕坐标）。 */
    fun swipePoints(): List<List<Float>> =
        if (path.isNotEmpty()) path
        else listOf(listOf(pointX.toFloat(), pointY.toFloat()), listOf(endX.toFloat(), endY.toFloat()))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("color", color)
        put("pointX", pointX)
        put("pointY", pointY)
        put("endX", endX)
        put("endY", endY)
        put("durationMs", durationMs)
        put("path", JSONArray().apply {
            path.forEach { p -> put(JSONArray().apply { p.forEach { v -> put(v.toDouble()) } }) }
        })
        put("gestureName", gestureName)
        put("repeatCount", repeatCount)
        put("delayMs", delayMs)
        put("waitMode", waitMode.name)
        put("waitUntilMs", waitUntilMs)
        loopTargetId?.let { put("loopTargetId", it) } ?: put("loopTargetId", JSONObject.NULL)
        put("loopCount", loopCount)
    }

    companion object {
        fun fromJson(o: JSONObject): ProgramNode {
            val pathArr = o.optJSONArray("path") ?: JSONArray()
            val path = ArrayList<List<Float>>(pathArr.length())
            for (i in 0 until pathArr.length()) {
                val p = pathArr.optJSONArray(i) ?: continue
                if (p.length() >= 2) path.add(listOf(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
            }
            val target = if (o.isNull("loopTargetId")) null else o.optString("loopTargetId").ifEmpty { null }
            return ProgramNode(
                id = o.optString("id", newId()),
                type = runCatching { ProgramNodeType.valueOf(o.optString("type", "CLICK")) }
                    .getOrDefault(ProgramNodeType.CLICK),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                color = o.optInt("color", PROGRAM_COLORS[0]),
                pointX = o.optInt("pointX", 0),
                pointY = o.optInt("pointY", 0),
                endX = o.optInt("endX", 0),
                endY = o.optInt("endY", 0),
                durationMs = o.optInt("durationMs", 300),
                path = path,
                gestureName = o.optString("gestureName", ""),
                repeatCount = o.optInt("repeatCount", 1).coerceAtLeast(1),
                delayMs = o.optLong("delayMs", 1000).coerceAtLeast(0),
                waitMode = runCatching { WaitMode.valueOf(o.optString("waitMode", "USER")) }
                    .getOrDefault(WaitMode.USER),
                waitUntilMs = o.optLong("waitUntilMs", 0),
                loopTargetId = target,
                loopCount = o.optInt("loopCount", 1).coerceAtLeast(0),
            )
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** 一条连线。同一节点多条第 1 条/多条出线 = 并行支线。 */
data class ProgramEdge(val fromId: String, val toId: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("from", fromId)
        put("to", toId)
    }

    companion object {
        fun fromJson(o: JSONObject) = ProgramEdge(o.optString("from"), o.optString("to"))
    }
}

data class Program(
    val id: String,
    val name: String,
    val nodes: List<ProgramNode>,
    val edges: List<ProgramEdge>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun node(id: String?): ProgramNode? = nodes.firstOrNull { it.id == id }

    val start: ProgramNode? get() = nodes.firstOrNull { it.type == ProgramNodeType.START }

    /** 某个节点的所有出线，按目标节点在画布上的 y 排序（上 → 下 = 并行支线顺序）。 */
    fun outEdges(fromId: String): List<ProgramEdge> =
        edges.filter { it.fromId == fromId }
            .sortedBy { e -> node(e.toId)?.y ?: 0f }

    /** 某个节点的第一条入线（用于显示）。 */
    fun inEdge(toId: String): ProgramEdge? = edges.firstOrNull { it.toId == toId }

    /** 校验：能不能从 from 连到 to。返回 null 表示可以，否则是 UI 层要根据 [EdgeError] 翻成文案的原因。 */
    fun connectError(fromId: String, toId: String): EdgeError? {
        val from = node(fromId) ?: return EdgeError.NO_SOURCE
        val to = node(toId) ?: return EdgeError.NO_TARGET
        if (fromId == toId) return EdgeError.SELF
        if (to.type == ProgramNodeType.START) return EdgeError.TO_START
        if (edges.any { it.fromId == fromId && it.toId == toId }) return EdgeError.DUPLICATE
        // 允许成环（循环节点就是靠回连实现），但不允许绕过 LOOP 直接连回上游造成隐式死循环？
        // 这里放开：用户显式连线即视为他的意图，运行时按图跑。
        if (from.type == ProgramNodeType.LOOP && edges.any { it.fromId == fromId }) return EdgeError.LOOP_SINGLE_OUT
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        put("edges", JSONArray().apply { edges.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Program {
            val nodes = ArrayList<ProgramNode>()
            o.optJSONArray("nodes")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { nodes.add(ProgramNode.fromJson(it)) }
            }
            val edges = ArrayList<ProgramEdge>()
            o.optJSONArray("edges")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { edges.add(ProgramEdge.fromJson(it)) }
            }
            return Program(
                id = o.optString("id", ProgramNode.newId()),
                name = o.optString("name", "Program"),
                nodes = nodes,
                edges = edges,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        /** 新程序：带一个开始节点。 */
        fun create(name: String): Program {
            val start = ProgramNode(
                id = ProgramNode.newId(),
                type = ProgramNodeType.START,
                x = 120f,
                y = 240f,
                color = PROGRAM_COLORS[0],
            )
            return Program(
                id = ProgramNode.newId(),
                name = name,
                nodes = listOf(start),
                edges = emptyList(),
            )
        }
    }
}

/**
 * 连线失败的原因。模型层不持有文案（否则英文界面里会冒出中文），
 * 只给一个资源 id，由 UI 层 stringResource/context.getString 翻。
 */
enum class EdgeError(@param:StringRes val res: Int) {
    NO_SOURCE(R.string.edge_error_no_source),
    NO_TARGET(R.string.edge_error_no_target),
    SELF(R.string.edge_error_self),
    TO_START(R.string.edge_error_to_start),
    DUPLICATE(R.string.edge_error_duplicate),
    LOOP_SINGLE_OUT(R.string.edge_error_loop_single_out),
}
