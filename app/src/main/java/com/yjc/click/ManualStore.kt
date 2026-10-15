package com.yjc.click

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 「滑动 → 手动参数」预设列表的持久化存储。
 *
 * 一条预设 = 5 个滑动参数（起点 X/Y、终点 X/Y、时长），按字符串存（与输入框一致，空串表示没填）。
 * 和 [GestureStore]、[BackgroundStore] 同一套写法：只存 [Preset.seq]（1、2、3…），
 * 名字由 [displayName] 按当前语言现场拼；用户改过名就用自定义名。
 *
 * 老版本只有一份参数存在 [ConfigStore] 里：第一次读到空列表时，用那份值补一条「预设一」，
 * 老用户填过的参数不会丢。
 */
object ManualStore {

    private const val PREFS_NAME = "manual_settings"
    private const val KEY_PRESETS = "presets"
    private const val KEY_SELECTED = "selected_preset"

    data class Preset(
        val id: String,
        val seq: Int,
        val x1: String = "",
        val y1: String = "",
        val x2: String = "",
        val y2: String = "",
        val duration: String = "",
        /** 用户重命名后的自定义名；空串 = 还没改名，用 [displayName] 的自动编号名 */
        val name: String = "",
    ) {
        /**
         * 5 个值都填了、时长还是正数，才能拿去执行。
         * 判定与 MainActivity.isSwipeConfigValid 的手动参数分支一致
         *（时长为 0 会让 StrokeDescription 抛异常，滑动会静默失败）。
         */
        val isUsable: Boolean
            get() = x1.isNotEmpty() && y1.isNotEmpty() && x2.isNotEmpty() && y2.isNotEmpty() &&
                    (duration.toLongOrNull() ?: 0L) > 0L

        /** 只换 5 个值，id/seq/名字不动（编辑草稿、保存、撤销重做都走它） */
        fun withValues(other: Preset): Preset = copy(
            x1 = other.x1, y1 = other.y1,
            x2 = other.x2, y2 = other.y2,
            duration = other.duration,
        )
    }

    data class Snapshot(
        val presets: List<Preset> = emptyList(),
        val selectedId: String? = null,
    ) {
        /** 选中的那条（列表为空、或 id 失效时为 null） */
        val selected: Preset? get() = presets.firstOrNull { it.id == selectedId }
    }

    fun load(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRESETS, null)
        if (raw == null) {
            // 第一次进来：把老版本存在 ConfigStore 里的那份参数搬成「预设一」
            val legacy = ConfigStore.load(context)
            val migrated = Preset(
                id = UUID.randomUUID().toString(),
                seq = 1,
                x1 = legacy.swipeX1,
                y1 = legacy.swipeY1,
                x2 = legacy.swipeX2,
                y2 = legacy.swipeY2,
                duration = legacy.swipeDuration,
            )
            val snapshot = Snapshot(listOf(migrated), migrated.id)
            save(context, snapshot)
            return snapshot
        }
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
        val presets = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            presets.add(
                Preset(
                    id = id,
                    seq = obj.optInt("seq", presets.size + 1),
                    x1 = obj.optString("x1"),
                    y1 = obj.optString("y1"),
                    x2 = obj.optString("x2"),
                    y2 = obj.optString("y2"),
                    duration = obj.optString("duration"),
                    name = obj.optString("name").trim(),
                ),
            )
        }
        val selected = prefs.getString(KEY_SELECTED, "") ?: ""
        return Snapshot(
            presets = presets,
            // 选中的预设被删掉后偏好里可能还留着旧 id，这里对齐一次：落到第一条；列表为空就是 null
            selectedId = selected.takeIf { it.isNotEmpty() && presets.any { p -> p.id == it } }
                ?: presets.firstOrNull()?.id,
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        val selectedId = snapshot.selectedId?.takeIf { id -> snapshot.presets.any { it.id == id } }
            ?: snapshot.presets.firstOrNull()?.id
        val arr = JSONArray()
        snapshot.presets.forEach { preset ->
            arr.put(JSONObject().apply {
                put("id", preset.id)
                put("seq", preset.seq)
                put("x1", preset.x1)
                put("y1", preset.y1)
                put("x2", preset.x2)
                put("y2", preset.y2)
                put("duration", preset.duration)
                put("name", preset.name)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .putString(KEY_SELECTED, selectedId ?: "")
            .apply()
    }

    /**
     * 新预设：编号取最小未用值（删掉「预设一」再加回来还是「预设一」），
     * 5 个值直接复制 [seed]（当前选中的那条），这样新建出来就能用、不用从空白开始填。
     */
    fun newPreset(presets: List<Preset>, seed: Preset?): Preset {
        val used = presets.map { it.seq }.toSet()
        var seq = 1
        while (seq in used) seq++
        return Preset(
            id = UUID.randomUUID().toString(),
            seq = seq,
            x1 = seed?.x1 ?: "",
            y1 = seed?.y1 ?: "",
            x2 = seed?.x2 ?: "",
            y2 = seed?.y2 ?: "",
            duration = seed?.duration ?: "",
        )
    }

    /**
     * 预设显示名：用户改过名就用自定义名，否则 `预设%1$s`，序号取自 [R.array.preset_numerals]
     *（中文是「一、二、三…」，其它语言是「1、2、3…」；超出数组长度回落到阿拉伯数字）。
     */
    fun displayName(context: Context, preset: Preset): String {
        preset.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val numerals = context.resources.getStringArray(R.array.preset_numerals)
        val numeral = numerals.getOrNull(preset.seq - 1) ?: preset.seq.toString()
        return context.getString(R.string.preset_name, numeral)
    }

    /** 替换列表里的某个预设（data class copy，直接换整个列表以便 Compose 察觉变化） */
    fun withPreset(presets: List<Preset>, updated: Preset): List<Preset> =
        presets.map { if (it.id == updated.id) updated else it }
}
