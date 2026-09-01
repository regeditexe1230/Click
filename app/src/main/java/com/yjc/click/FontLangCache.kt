package com.yjc.click

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 缓存字体语言检测结果，存储在 SharedPreferences 中。
 * 结构：{ "fontFilePath": ["zh", "en"], ... }
 */
object FontLangCache {

    private const val PREFS_NAME = "font_lang_cache"
    private const val KEY_MAP = "lang_map"

    fun getSupportedLanguages(context: Context, fontPath: String): List<String>? {
        val map = loadMap(context)
        return map[fontPath]
    }

    fun putSupportedLanguages(context: Context, fontPath: String, languages: List<String>) {
        val map = loadMap(context)
        map[fontPath] = languages
        saveMap(context, map)
    }

    fun remove(context: Context, fontPath: String) {
        val map = loadMap(context)
        map.remove(fontPath)
        saveMap(context, map)
    }

    private fun loadMap(context: Context): MutableMap<String, List<String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MAP, "{}") ?: "{}"
        val obj = JSONObject(json)
        val result = mutableMapOf<String, List<String>>()
        for (key in obj.keys()) {
            val arr = obj.getJSONArray(key)
            val langs = mutableListOf<String>()
            for (i in 0 until arr.length()) langs.add(arr.getString(i))
            result[key] = langs
        }
        return result
    }

    private fun saveMap(context: Context, map: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((key, langs) in map) {
            obj.put(key, JSONArray(langs))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAP, obj.toString()).apply()
    }
}
