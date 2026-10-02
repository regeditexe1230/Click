package com.yjc.click

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 「应用背景」预设列表的持久化存储。
 *
 * 这一版预设只是个名字（占位）：真正携带背景图片/颜色的字段以后再往 [Preset] 里加，
 * 所以列表按 JSON 存，方便扩展。
 *
 * 只存 [Preset.seq]（1、2、3…）不存名字：名字由 [displayName] 按当前语言现场拼，
 * 换语言时列表里的「预设一」会跟着变成「Preset 1」，也不用处理重名。
 */
object BackgroundStore {

    private const val PREFS_NAME = "background_settings"
    private const val KEY_PRESETS = "presets"
    private const val KEY_SELECTED = "selected_preset"

    data class Preset(val id: String, val seq: Int)

    data class Snapshot(
        val presets: List<Preset> = emptyList(),
        val selectedId: String? = null,
    )

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
            presets.add(Preset(id, obj.optInt("seq", presets.size + 1)))
        }
        val selected = prefs.getString(KEY_SELECTED, "") ?: ""
        return Snapshot(
            presets = presets,
            // 选中的预设被删掉后，偏好里可能还留着旧 id，这里对齐一次
            selectedId = selected.takeIf { it.isNotEmpty() && presets.any { p -> p.id == it } },
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        val arr = JSONArray()
        snapshot.presets.forEach { preset ->
            arr.put(JSONObject().apply {
                put("id", preset.id)
                put("seq", preset.seq)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .putString(KEY_SELECTED, snapshot.selectedId ?: "")
            .apply()
    }

    /** 新预设：编号取最小未用值，所以删掉「预设一」再加回来还是「预设一」 */
    fun newPreset(presets: List<Preset>): Preset {
        val used = presets.map { it.seq }.toSet()
        var seq = 1
        while (seq in used) seq++
        return Preset(UUID.randomUUID().toString(), seq)
    }

    /**
     * 预设显示名：`预设%1$s`，序号取自 [R.array.preset_numerals]
     *（中文是「一、二、三…」，其它语言是「1、2、3…」；超出数组长度回落到阿拉伯数字）。
     */
    fun displayName(context: Context, seq: Int): String {
        val numerals = context.resources.getStringArray(R.array.preset_numerals)
        val numeral = numerals.getOrNull(seq - 1) ?: seq.toString()
        return context.getString(R.string.preset_name, numeral)
    }
}
