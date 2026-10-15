package com.yjc.click

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 「滑动 → 手势录制」预设列表的持久化存储。
 *
 * 每条预设 = 一次录制下来的完整轨迹（点序列 + 总时长），选中的那条就是执行滑动时用的手势。
 * 和 [BackgroundStore] 同一套写法：只存 [Preset.seq]（1、2、3…），名字由 [displayName] 按当前语言现场拼，
 * 换语言时「手势一」会跟着变成「Gesture 1」，也不用处理重名。
 * 轨迹点很多，JSON 里存成扁平的 `[x0, y0, x1, y1, …]` 数组，省掉"每个点一个对象"的开销。
 */
object GestureStore {

    private const val PREFS_NAME = "gesture_settings"
    private const val KEY_PRESETS = "gestures"
    private const val KEY_SELECTED = "selected_gesture"

    /** 单条预设最多存这么多点，免得超长轨迹把偏好文件撑爆 */
    private const val MAX_POINTS = 20000

    /**
     * 一条手势预设。点是屏幕绝对坐标（与 [RecordedGesture] 一致），
     * [totalDuration] 是录制实际耗时，执行滑动时按它换算速度。
     * [name] 是用户重命名后的自定义名，空串表示还没改名、用 [displayName] 的自动编号名。
     */
    data class Preset(
        val id: String,
        val seq: Int,
        val points: List<Pair<Float, Float>> = emptyList(),
        val totalDuration: Long = 0L,
        val name: String = "",
    ) {
        /** 至少两个点才能拿来执行滑动 */
        val isUsable: Boolean get() = points.size >= 2

        fun toGesture(): RecordedGesture = RecordedGesture(points, totalDuration)
    }

    data class Snapshot(
        val presets: List<Preset> = emptyList(),
        val selectedId: String? = null,
    ) {
        /** 选中的那条（列表为空、或 id 失效时为 null） */
        val selected: Preset? get() = presets.firstOrNull { it.id == selectedId }

        /** 交给运行时用的手势：没得选就是空手势 */
        fun gesture(): RecordedGesture =
            selected?.takeIf { it.isUsable }?.toGesture() ?: RecordedGesture.EMPTY
    }

    fun load(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_PRESETS, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val presets = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            val raw = obj.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<Pair<Float, Float>>(raw.length() / 2)
            var index = 0
            while (index + 1 < raw.length()) {
                points.add(
                    Pair(
                        raw.optDouble(index, 0.0).toFloat(),
                        raw.optDouble(index + 1, 0.0).toFloat(),
                    )
                )
                index += 2
            }
            // 少于两个点的记录没法执行滑动，直接丢掉（免得列表里留一条"点不动"的）
            if (points.size < 2) continue
            presets.add(
                Preset(
                    id = id,
                    seq = obj.optInt("seq", presets.size + 1),
                    points = points,
                    // 录得太快（同一毫秒内按下抬起）时给个下限，0 会让 StrokeDescription 抛异常
                    totalDuration = obj.optLong("duration", 0L).coerceAtLeast(1L),
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
            val flat = JSONArray()
            val limit = minOf(preset.points.size, MAX_POINTS)
            for (i in 0 until limit) {
                flat.put(preset.points[i].first.toDouble())
                flat.put(preset.points[i].second.toDouble())
            }
            arr.put(JSONObject().apply {
                put("id", preset.id)
                put("seq", preset.seq)
                put("duration", preset.totalDuration)
                put("name", preset.name)
                put("points", flat)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .putString(KEY_SELECTED, selectedId ?: "")
            .apply()
    }

    /** 新预设：编号取最小未用值，所以删掉「手势一」再录一条回来还是「手势一」 */
    fun newPreset(
        presets: List<Preset>,
        points: List<Pair<Float, Float>>,
        totalDuration: Long,
    ): Preset {
        val used = presets.map { it.seq }.toSet()
        var seq = 1
        while (seq in used) seq++
        return Preset(UUID.randomUUID().toString(), seq, points, totalDuration)
    }

    /**
     * 预设显示名：用户改过名就用自定义名，否则 `手势%1$s`，序号取自 [R.array.preset_numerals]
     *（中文是「一、二、三…」，其它语言是「1、2、3…」；超出数组长度回落到阿拉伯数字）。
     */
    fun displayName(context: Context, preset: Preset): String {
        preset.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val numerals = context.resources.getStringArray(R.array.preset_numerals)
        val numeral = numerals.getOrNull(preset.seq - 1) ?: preset.seq.toString()
        return context.getString(R.string.gesture_name, numeral)
    }

    /**
     * 录制完成：追加一条预设并选中它（录完立刻就是当前手势）。
     * 返回落盘后的快照，调用方拿它刷新界面即可。
     */
    fun appendRecorded(
        context: Context,
        points: List<Pair<Float, Float>>,
        totalDuration: Long,
    ): Snapshot {
        val current = load(context)
        val preset = newPreset(current.presets, points, totalDuration)
        save(context, Snapshot(current.presets + preset, preset.id))
        return load(context)
    }
}
