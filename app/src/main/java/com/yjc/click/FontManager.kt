package com.yjc.click

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FontManager {

    private const val PREFS_NAME = "font_settings"
    private const val KEY_FONTS = "custom_fonts"
    private const val KEY_SELECTED = "selected_font"

    private val fontTypefaceCache = mutableMapOf<String, Typeface>()

    var currentTypeface: Typeface? = null
        private set

    fun init(context: Context) {
        loadSelectedFont(context)
    }

    fun getCustomFonts(context: Context): List<FontParser.FontInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FONTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<FontParser.FontInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                FontParser.FontInfo(
                    familyName = obj.getString("name"),
                    filePath = obj.getString("path"),
                    isCustom = true
                )
            )
        }
        return result
    }

    fun addCustomFont(context: Context, sourceFile: File): FontParser.FontInfo? {
        val fontsDir = File(context.filesDir, "fonts")
        if (!fontsDir.exists()) fontsDir.mkdirs()

        // 计算MD5
        val md5 = sourceFile.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        // 检查是否已存在（按MD5）
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FONTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("md5") == md5) {
                return null // 已存在
            }
        }

        // Copy file to internal storage
        val destFile = File(fontsDir, sourceFile.name)
        sourceFile.inputStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Get font family name
        val familyName = FontParser.getFontFamilyName(destFile) ?: destFile.nameWithoutExtension

        // Save to preferences
        val obj = JSONObject().apply {
            put("name", familyName)
            put("path", destFile.absolutePath)
            put("md5", md5)
        }
        arr.put(obj)
        prefs.edit().putString(KEY_FONTS, arr.toString()).apply()

        return FontParser.FontInfo(familyName, destFile.absolutePath, true)
    }

    fun removeCustomFont(context: Context, font: FontParser.FontInfo) {
        // Delete file
        val file = File(font.filePath)
        if (file.exists()) file.delete()

        // Remove language cache
        FontLangCache.remove(context, font.filePath)

        // Remove from preferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FONTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("path") != font.filePath) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString(KEY_FONTS, newArr.toString()).apply()

        // If this was the selected font, reset to default
        val selectedPath = prefs.getString(KEY_SELECTED, "") ?: ""
        if (selectedPath == font.filePath) {
            setSelectedFont(context, null)
        }
    }

    fun setSelectedFont(context: Context, font: FontParser.FontInfo?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED, font?.filePath ?: "").apply()
        loadSelectedFont(context)
    }

    fun getSelectedFontPath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED, "") ?: ""
    }

    private fun loadSelectedFont(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_SELECTED, "") ?: ""
        currentTypeface = if (path.isEmpty()) {
            null // system default
        } else {
            val file = File(path)
            if (file.exists()) {
                fontTypefaceCache.getOrPut(path) {
                    Typeface.createFromFile(file) ?: Typeface.DEFAULT
                }
            } else {
                null
            }
        }
    }

    fun applyFont(view: View) {
        val typeface = currentTypeface ?: Typeface.DEFAULT
        applyTypefaceRecursive(view, typeface)
    }

    fun applyFontToDialog(dialog: android.app.Dialog) {
        val typeface = currentTypeface ?: Typeface.DEFAULT
        val decorView = dialog.window?.decorView ?: return
        // 标题
        decorView.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.typeface = typeface
        // 消息
        decorView.findViewById<TextView>(android.R.id.message)?.typeface = typeface
        // 按钮
        (dialog as? androidx.appcompat.app.AlertDialog)?.let {
            it.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.typeface = typeface
            it.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.typeface = typeface
        }
    }

    private fun applyTypefaceRecursive(view: View, typeface: Typeface) {
        when (view) {
            is TextInputLayout -> view.typeface = typeface
            is TextView -> view.typeface = typeface
            is Button -> view.typeface = typeface
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTypefaceRecursive(view.getChildAt(i), typeface)
            }
        }
    }

    fun loadTypefaceForPreview(filePath: String): Typeface? {
        fontTypefaceCache[filePath]?.let { return it }
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            Typeface.createFromFile(file).also { fontTypefaceCache[filePath] = it }
        } catch (e: Exception) {
            null
        }
    }
}
