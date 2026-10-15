package com.yjc.click.program

import android.content.Context
import com.yjc.click.R
import org.json.JSONArray

/**
 * 「程序」的本地存储：SharedPreferences + JSON。
 *
 * 与旧的点击/滑动系统完全独立（旧系统用 click_config.xml / gesture_settings.xml 等），
 * 这里只动 program_settings.xml，互不影响。
 */
object ProgramStore {
    private const val PREFS = "program_settings"
    private const val KEY = "programs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部程序，按创建时间排序。 */
    fun list(context: Context): List<Program> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Program>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Program.fromJson(it)) }
            }
            out.sortedBy { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun load(context: Context, id: String): Program? = list(context).firstOrNull { it.id == id }

    fun save(context: Context, program: Program) {
        val all = list(context).toMutableList()
        val idx = all.indexOfFirst { it.id == program.id }
        val stamped = program.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) all[idx] = stamped else all.add(stamped)
        write(context, all)
    }

    fun delete(context: Context, id: String) {
        write(context, list(context).filterNot { it.id == id })
    }

    fun rename(context: Context, id: String, name: String) {
        val p = load(context, id) ?: return
        save(context, p.copy(name = name))
    }

    fun duplicate(context: Context, id: String): Program? {
        val p = load(context, id) ?: return null
        val copy = Program(
            id = ProgramNode.newId(),
            name = context.getString(R.string.program_copy_suffix, p.name),
            nodes = p.nodes.map { it.copy(id = ProgramNode.newId(), loopTargetId = null) },
            edges = emptyList(),
        )
        // 复制节点时要重映射边（节点 id 全变了），所以按索引一一对应重建。
        val idMap = p.nodes.mapIndexed { i, n -> n.id to copy.nodes[i].id }.toMap()
        val copy2 = copy.copy(
            nodes = copy.nodes.mapIndexed { i, n ->
                val src = p.nodes[i]
                n.copy(loopTargetId = src.loopTargetId?.let { idMap[it] })
            },
            edges = p.edges.mapNotNull { e ->
                val f = idMap[e.fromId] ?: return@mapNotNull null
                val t = idMap[e.toId] ?: return@mapNotNull null
                ProgramEdge(f, t)
            },
        )
        val all = list(context).toMutableList().apply { add(copy2) }
        write(context, all)
        return copy2
    }

    /** 新建程序：自带一个开始节点。 */
    fun create(context: Context, name: String? = null): Program {
        val existing = list(context)
        val p = Program.create(name ?: defaultName(context, existing))
        val all = existing.toMutableList().apply { add(p) }
        write(context, all)
        return p
    }

    private fun defaultName(context: Context, existing: List<Program>): String {
        var i = existing.size + 1
        while (existing.any { it.name == context.getString(R.string.program_default_name, i) }) i++
        return context.getString(R.string.program_default_name, i)
    }

    /** 拖拽排序后按新顺序整体重写。 */
    fun reorder(context: Context, ordered: List<Program>) {
        val now = System.currentTimeMillis()
        write(context, ordered.mapIndexed { i, p -> p.copy(createdAt = now + i) })
    }

    private fun write(context: Context, programs: List<Program>) {
        val arr = JSONArray()
        programs.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
