package com.yjc.click.program

import android.content.Context
import com.yjc.click.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「程序」的本地存储：SharedPreferences + JSON。
 *
 * 与旧的点击/滑动系统完全独立（旧系统用 click_config.xml / gesture_settings.xml 等），
 * 这里只动 program_settings.xml，互不影响。
 */
object ProgramStore {
    private const val PREFS = "program_settings"
    private const val KEY = "programs"

    /** 一次性迁移标记（见 [migrateIfNeeded]）。 */
    private const val MIGRATION_KEY = "tray_migration_v1"

    /** 迁移前的备份目录（相对 `filesDir`）。 */
    private const val BACKUP_DIR = "programs_pre_tray_backup"

    /** 积木托盘是否展开（跨会话保持，默认展开）。 */
    private const val KEY_TRAY_EXPANDED = "tray_expanded"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部程序，按创建时间排序。 */
    fun list(context: Context): List<Program> {
        // 托盘化改造的一次性迁移：读之前先把老数据里的开始节点清掉（只做一次，见 migrateIfNeeded）
        migrateIfNeeded(context)
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

    /** 新建程序：空画布（开始块要用户自己从底部托盘拖出来）。 */
    fun create(context: Context, name: String? = null): Program {
        val existing = list(context)
        val p = Program.create(name ?: defaultName(context, existing))
        val all = existing.toMutableList().apply { add(p) }
        write(context, all)
        return p
    }

    // ---------------------------------------------------------------- 积木托盘

    /** 积木托盘是否展开（默认展开）。 */
    fun isTrayExpanded(context: Context): Boolean = prefs(context).getBoolean(KEY_TRAY_EXPANDED, true)

    /** 记住托盘的展开/收起状态。 */
    fun setTrayExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRAY_EXPANDED, expanded).apply()
    }

    // ---------------------------------------------------------------- 一次性迁移

    /**
     * 托盘化改造的**一次性迁移**（标记 `tray_migration_v1`）：把所有程序里的开始节点、
     * 挂在它上面的边、以及指向它的回跳目标一并删掉（用户明确选了「已有的开始节点也一并删掉」）。
     *
     * 这是破坏性改动 ⇒ 动手之前先把**受影响程序的完整 JSON** 备份到
     * `filesDir/programs_pre_tray_backup/<programId>.json`（文件里额外写 `backupAt` / `backupReason`）：
     * 只要有任何一次备份写失败就**中止迁移、原样保留**，并且不落迁移标记（下次启动再试）。
     * 已经存在的备份文件不覆盖 —— 那是最早那次的迁移前快照。
     *
     * 解析不出旧数据时也直接返回（同样不落标记），宁可下次再迁，也不能把用户的程序弄丢。
     */
    fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(MIGRATION_KEY, false)) return
        val raw = p.getString(KEY, null)
        if (raw.isNullOrEmpty()) {
            p.edit().putBoolean(MIGRATION_KEY, true).apply()
            return
        }
        val programs = runCatching {
            val arr = JSONArray(raw)
            ArrayList<JSONObject>(arr.length()).apply {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
            }
        }.getOrNull() ?: return

        val affected = programs.filter { startNodeIds(it).isNotEmpty() }
        if (affected.isNotEmpty()) {
            if (!backupBeforeMigration(context, affected)) return
            affected.forEach { trimStartNodes(it) }
            writeRaw(context, programs)
        }
        p.edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    /** 程序 JSON 里所有开始节点的 id。 */
    private fun startNodeIds(program: JSONObject): Set<String> {
        val nodes = program.optJSONArray("nodes") ?: return emptySet()
        val out = HashSet<String>(2)
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("type") != "START") continue
            val id = n.optString("id")
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /** 原地删掉开始节点、挂在它上面的边，以及指向它的回跳目标（LOOP 的 loopTargetId 置空）。 */
    private fun trimStartNodes(program: JSONObject) {
        val ids = startNodeIds(program)
        if (ids.isEmpty()) return
        val nodes = program.optJSONArray("nodes") ?: JSONArray()
        val keptNodes = JSONArray()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("id") in ids) continue
            if (!n.isNull("loopTargetId") && n.optString("loopTargetId") in ids) {
                n.put("loopTargetId", JSONObject.NULL)
            }
            keptNodes.put(n)
        }
        program.put("nodes", keptNodes)
        val edges = program.optJSONArray("edges") ?: JSONArray()
        val keptEdges = JSONArray()
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            if (e.optString("from") in ids || e.optString("to") in ids) continue
            keptEdges.put(e)
        }
        program.put("edges", keptEdges)
        program.put("updatedAt", System.currentTimeMillis())
    }

    /** 备份受影响的程序；任何一步失败都返回 false（调用方据此中止迁移）。 */
    private fun backupBeforeMigration(context: Context, affected: List<JSONObject>): Boolean {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists() && !dir.mkdirs()) return false
        val now = System.currentTimeMillis()
        for (o in affected) {
            val id = o.optString("id")
            if (id.isEmpty()) return false
            val file = File(dir, "$id.json")
            if (file.exists() && file.length() > 0L) continue // 保留最早那份迁移前快照
            val body = JSONObject(o.toString()).apply {
                put("backupAt", now)
                put("backupReason", "tray_migration_v1")
            }
            runCatching { file.writeText(body.toString()) }.getOrElse { return false }
        }
        return true
    }

    private fun writeRaw(context: Context, programs: List<JSONObject>) {
        val arr = JSONArray()
        programs.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
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
