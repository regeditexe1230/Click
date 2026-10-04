package com.yjc.click

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 「应用背景」预设列表的持久化存储。
 *
 * 这一版预设只是个名字（占位）：真正携带背景图片/颜色的字段以后再往 [Preset] 里加，
 * 所以列表按 JSON 存，方便扩展。
 *
 * 只存 [Preset.seq]（1、2、3…）不存名字：名字由 [displayName] 按当前语言现场拼，
 * 换语言时列表里的「预设一」会跟着变成「Preset 1」，也不用处理重名。
 * 内置的「默认」预设（[DEFAULT_PRESET_ID]）例外：它排在最前面、名字固定，且删不掉。
 */
object BackgroundStore {

    private const val PREFS_NAME = "background_settings"
    private const val KEY_PRESETS = "presets"
    private const val KEY_SELECTED = "selected_preset"

    /** 内置「默认」预设的 id：列表里永远有它兜底，用户删不掉（没有它就没法退回默认背景） */
    const val DEFAULT_PRESET_ID = "default"

    /**
     * 一个预设。目前只携带一张背景图 + 两个透明度：
     * [imageAlpha] 图片本身的不透明度（0 = 图看不见，100 = 原图），
     * [scrimAlpha] 叠在图上的遮罩不透明度（浅色主题下是白色、深色主题下是黑色，把图压向背景色）。
     * 默认 80 相当于"背景色盖 20%"，即图片略微变淡、文字看得清。
     */
    data class Preset(
        val id: String,
        val seq: Int,
        val imagePath: String? = null,
        val imageAlpha: Int = DEFAULT_IMAGE_ALPHA,
        val scrimAlpha: Int = DEFAULT_SCRIM_ALPHA,
    ) {
        /** 内置「默认」预设：不可删除 */
        val isDefault: Boolean get() = id == DEFAULT_PRESET_ID
    }

    /** 全新的「默认」预设（seq 固定 0，不参与「预设一/二/三」的编号） */
    private fun newDefaultPreset() = Preset(DEFAULT_PRESET_ID, seq = 0)

    const val DEFAULT_IMAGE_ALPHA = 80
    const val DEFAULT_SCRIM_ALPHA = 0

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
            // 图片文件被清掉/删掉时按"没有图"处理，免得面板里挂着一个打不开的路径
            val path = obj.optString("image_path").takeIf { it.isNotEmpty() && File(it).exists() }
            presets.add(
                Preset(
                    id = id,
                    seq = obj.optInt("seq", presets.size + 1),
                    imagePath = path,
                    imageAlpha = obj.optInt("image_alpha", DEFAULT_IMAGE_ALPHA).coerceIn(0, 100),
                    scrimAlpha = obj.optInt("scrim_alpha", DEFAULT_SCRIM_ALPHA).coerceIn(0, 100),
                ),
            )
        }
        // 内置的「默认」永远排在最前面：老版本存下来的列表里没有它，这里补一个
        val all = if (presets.any { it.isDefault }) presets else listOf(newDefaultPreset()) + presets
        val selected = prefs.getString(KEY_SELECTED, "") ?: ""
        return Snapshot(
            presets = all,
            // 选中的预设被删掉后，偏好里可能还留着旧 id，这里对齐一次；
            // 没得选（或 id 无效）就落到「默认」
            selectedId = selected.takeIf { it.isNotEmpty() && all.any { p -> p.id == it } }
                ?: DEFAULT_PRESET_ID,
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        // 同样保证「默认」在列表里、选中项有效，免得某个调用点漏了这两条
        val presets =
            if (snapshot.presets.any { it.isDefault }) snapshot.presets
            else listOf(newDefaultPreset()) + snapshot.presets
        val selectedId =
            snapshot.selectedId?.takeIf { id -> presets.any { it.id == id } } ?: DEFAULT_PRESET_ID
        val arr = JSONArray()
        presets.forEach { preset ->
            arr.put(JSONObject().apply {
                put("id", preset.id)
                put("seq", preset.seq)
                preset.imagePath?.let { put("image_path", it) }
                put("image_alpha", preset.imageAlpha)
                put("scrim_alpha", preset.scrimAlpha)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .putString(KEY_SELECTED, selectedId)
            .apply()
    }

    /** 背景图存放目录（每个预设一张，文件名带时间戳避免重名） */
    fun backgroundsDir(context: Context): File =
        File(context.filesDir, "backgrounds").apply { if (!exists()) mkdirs() }

    /** 新预设：编号取最小未用值，所以删掉「预设一」再加回来还是「预设一」 */
    fun newPreset(presets: List<Preset>): Preset {
        val used = presets.map { it.seq }.toSet()
        var seq = 1
        while (seq in used) seq++
        return Preset(UUID.randomUUID().toString(), seq)
    }

    /**
     * 预设显示名：内置「默认」用固定文案，其余是 `预设%1$s`，序号取自 [R.array.preset_numerals]
     *（中文是「一、二、三…」，其它语言是「1、2、3…」；超出数组长度回落到阿拉伯数字）。
     */
    fun displayName(context: Context, preset: Preset): String {
        if (preset.isDefault) return context.getString(R.string.preset_default)
        val numerals = context.resources.getStringArray(R.array.preset_numerals)
        val numeral = numerals.getOrNull(preset.seq - 1) ?: preset.seq.toString()
        return context.getString(R.string.preset_name, numeral)
    }

    /** 替换列表里的某个预设（data class copy，直接换整个列表以便 Compose 察觉变化） */
    fun withPreset(presets: List<Preset>, updated: Preset): List<Preset> =
        presets.map { if (it.id == updated.id) updated else it }
}
