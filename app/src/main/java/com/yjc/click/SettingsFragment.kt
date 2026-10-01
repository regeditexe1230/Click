package com.yjc.click

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yjc.click.ui.settings.SettingsScreen
import com.yjc.click.ui.theme.AppTheme
import com.yjc.click.ui.theme.ClickTheme
import java.io.File

class SettingsFragment : Fragment() {

    companion object {
        /** 应用支持的语言，与 MainActivity.applyLanguage() 的兜底规则保持一致 */
        private val SUPPORTED_LANGUAGES = listOf("zh", "en", "ja", "ko")
    }

    // Compose 侧状态（替代原来 findViewById 后直接改 View）
    private var languageValue by mutableStateOf("")
    private var fontValue by mutableStateOf("")
    private var theme by mutableStateOf("follow_system")
    private var fontFamily by mutableStateOf<FontFamily?>(null)
    private var dynamicColorChecked by mutableStateOf(false)
    private var colorSchemeExpanded by mutableStateOf(false)
    private var colorExpanded by mutableStateOf(false)
    private var backgroundExpanded by mutableStateOf(false)

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        handleFontSelected(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 设置页界面已迁移到 Compose；语言/字体选择弹窗与字体导入进度弹窗仍是 View 实现，
        // 由本 Fragment 持有并通过下面的回调触发。
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ClickTheme(fontFamily = fontFamily, darkTheme = AppTheme.isDark) {
                    SettingsScreen(
                        languageValue = languageValue,
                        fontValue = fontValue,
                        theme = theme,
                        colorSchemeExpanded = colorSchemeExpanded,
                        colorExpanded = colorExpanded,
                        backgroundExpanded = backgroundExpanded,
                        dynamicColorChecked = dynamicColorChecked,
                        onLanguageClick = { showLanguageDialog() },
                        onFontClick = { showFontDialog() },
                        onThemeSelected = { newTheme, origin -> applyTheme(newTheme, origin) },
                        onColorSchemeHeaderClick = { colorSchemeExpanded = !colorSchemeExpanded },
                        onColorHeaderClick = { colorExpanded = !colorExpanded },
                        onBackgroundHeaderClick = { backgroundExpanded = !backgroundExpanded },
                        onDynamicColorChange = { dynamicColorChecked = it },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 展开状态：旋转重建时沿用系统保存的 bundle（与旧实现一致）
        colorSchemeExpanded = savedInstanceState?.getBoolean("color_scheme_expanded", false) ?: false
        colorExpanded = savedInstanceState?.getBoolean("color_expanded", false) ?: false
        backgroundExpanded = savedInstanceState?.getBoolean("background_expanded", false) ?: false

        theme = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("app_theme", "follow_system") ?: "follow_system"

        updateLanguageDisplay()
        updateFontDisplay()
    }

    /**
     * 写入偏好并切换主题；由 [AppTheme.apply] 负责截取旧画面并播放"圆形揭示"过渡
     * （圆心 = 手指点击的位置，见 SettingsScreen 的 ThemeOption）。
     */
    private fun applyTheme(newTheme: String, origin: androidx.compose.ui.geometry.Offset? = null) {
        val ctx = requireContext()
        val systemDark = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        AppTheme.apply(requireActivity(), newTheme, systemDark, origin)
        theme = newTheme
    }

    override fun onResume() {
        super.onResume()
        // 自定义字体改为通过 ClickTheme 下发 FontFamily：原来遍历 View 树设 typeface 的做法
        // 对 Compose 文本完全无效（设置页弹窗仍是 View，故 applyFontToDialog 继续保留）
        syncFontFamily()
        updateLanguageDisplay()
        updateFontDisplay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("color_scheme_expanded", colorSchemeExpanded)
        outState.putBoolean("color_expanded", colorExpanded)
        outState.putBoolean("background_expanded", backgroundExpanded)
    }

    // ==================== 语言 ====================

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_simplified_chinese),
            getString(R.string.lang_traditional_chinese),
            getString(R.string.lang_english),
            getString(R.string.lang_japanese),
            getString(R.string.lang_korean)
        )
        val localeCodes = arrayOf("", "zh-CN", "zh-TW", "en", "ja", "ko")

        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val currentIndex = when {
            currentLocale.isEmpty() || currentLocale == "und" -> 0
            currentLocale.startsWith("zh-CN") -> 1
            currentLocale.startsWith("zh-TW") || currentLocale.startsWith("zh-Hant") -> 2
            currentLocale.startsWith("en") -> 3
            currentLocale.startsWith("ja") -> 4
            currentLocale.startsWith("ko") -> 5
            else -> 0
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLocale = localeCodes[which]
                applyLanguage(selectedLocale)
                updateLanguageDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun applyLanguage(localeCode: String) {
        val context = requireContext()
        // 切换语言时重置字体为系统默认
        FontManager.setSelectedFont(context, null)
        // 持久化语言选择：MainActivity 启动时读取该值恢复语言（空串表示跟随系统）
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("app_locale", localeCode)
            .apply()
        val localeListCompat = if (localeCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(localeCode)
        }
        AppCompatDelegate.setApplicationLocales(localeListCompat)
    }

    private fun updateLanguageDisplay() {
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        languageValue = when {
            currentLocale.isEmpty() || currentLocale == "und" -> getString(R.string.lang_follow_system)
            currentLocale.startsWith("zh-CN") -> getString(R.string.lang_simplified_chinese)
            currentLocale.startsWith("zh-TW") || currentLocale.startsWith("zh-Hant") -> getString(R.string.lang_traditional_chinese)
            currentLocale.startsWith("en") -> getString(R.string.lang_english)
            currentLocale.startsWith("ja") -> getString(R.string.lang_japanese)
            currentLocale.startsWith("ko") -> getString(R.string.lang_korean)
            else -> getString(R.string.lang_follow_system)
        }
    }

    // ==================== 字体 ====================

    private fun showFontDialog() {
        val ctx = requireContext()
        val customFonts = FontManager.getCustomFonts(ctx)
        val selectedPath = FontManager.getSelectedFontPath(ctx)

        // 构建字体名称列表（与语言弹窗完全一致的逻辑）
        val names = mutableListOf<String>()
        val paths = mutableListOf<String>()  // "" = 系统默认
        val typefaces = mutableListOf<Typeface?>()

        names.add(getString(R.string.font_default))
        paths.add("")
        typefaces.add(null)

        for (font in customFonts) {
            names.add(font.familyName)
            paths.add(font.filePath)
            typefaces.add(FontManager.loadTypefaceForPreview(font.filePath))
        }

        names.add(getString(R.string.add_font))
        paths.add("__add__")
        typefaces.add(null)

        val currentIndex = paths.indexOf(selectedPath).coerceAtLeast(0)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.select_font)
            .setSingleChoiceItems(names.toTypedArray(), currentIndex) { dialog, which ->
                val path = paths[which]
                dialog.dismiss()
                if (path == "__add__") {
                    fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                } else if (path.isEmpty()) {
                    // 系统默认，直接应用
                    FontManager.setSelectedFont(ctx, null)
                    syncFontFamily()
                    updateFontDisplay()
                } else {
                    // 从缓存查语言支持
                    val langTag = getCurrentLanguageTag()
                    val cachedLangs = FontLangCache.getSupportedLanguages(ctx, path)
                    val supportsCurrent = cachedLangs?.contains(langTag) ?: true // 无缓存默认允许
                    if (!supportsCurrent) {
                        val langName = getCurrentLanguageName()
                        val d = MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.error)
                            .setMessage(getString(R.string.font_no_language_support, langName))
                            .setPositiveButton(R.string.ok, null)
                            .create()
                        d.show()
                        FontManager.applyFontToDialog(d)
                        return@setSingleChoiceItems
                    }
                    val font = customFonts.find { it.filePath == path }
                    FontManager.setSelectedFont(ctx, font)
                    syncFontFamily()
                    updateFontDisplay()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 弹窗显示后：1) 应用字体预览 2) "添加字体"项着色
        dialog.setOnShowListener {
            val listView = dialog.listView ?: return@setOnShowListener
            val primaryColor = android.util.TypedValue().let {
                ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, it, true)
                it.data
            }

            for (i in 0 until listView.childCount) {
                val child = listView.getChildAt(i)
                if (child is TextView) {
                    // 应用字体预览（非"添加字体"项）
                    if (i < typefaces.size && paths[i] != "__add__") {
                        typefaces[i]?.let { child.typeface = it }
                    }
                    // "添加字体"项：自定义字体 + 主题色 + 加号图标
                    if (i < paths.size && paths[i] == "__add__") {
                        FontManager.currentTypeface?.let { child.typeface = it }
                        child.setTextColor(primaryColor)
                        val icon = ContextCompat.getDrawable(ctx, android.R.drawable.ic_input_add)
                        icon?.setTint(primaryColor)
                        child.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                        child.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
                    }
                }
            }
        }

        // 长按自定义字体项触发删除
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            if (position < paths.size && paths[position].isNotEmpty() && paths[position] != "__add__") {
                val font = customFonts.find { it.filePath == paths[position] }
                if (font != null) {
                    dialog.dismiss()
                    showDeleteFontDialog(font)
                }
                true
            } else false
        }

        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun handleFontSelected(uri: Uri) {
        val ctx = requireContext()

        // 立即显示加载弹窗（主线程）
        val progressBar = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
            val dp48 = (48 * resources.displayMetrics.density).toInt()
            setPadding(dp48, dp48, dp48, dp48)
        }
        val loadingDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.font_checking)
            .setView(progressBar)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        // 后台线程处理所有耗时操作
        Thread {
            // 获取文件名并检查后缀
            val fileName = getFileNameFromUri(uri)
            val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
            if (ext !in listOf("ttf", "otf")) {
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    val d = MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.font_unsupported_format))
                        .setPositiveButton(R.string.ok, null)
                        .create()
                    d.show()
                    FontManager.applyFontToDialog(d)
                }
                return@Thread
            }

            // 复制文件到临时目录
            val tempFile = File(ctx.cacheDir, "temp_font.$ext")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    val d = MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.font_read_error))
                        .setPositiveButton(R.string.ok, null)
                        .create()
                    d.show()
                    FontManager.applyFontToDialog(d)
                }
                return@Thread
            }

            // 检查是否是有效字体
            val typeface = FontParser.loadTypeface(tempFile)
            if (typeface == null) {
                tempFile.delete()
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    val d = MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.font_invalid))
                        .setPositiveButton(R.string.ok, null)
                        .create()
                    d.show()
                    FontManager.applyFontToDialog(d)
                }
                return@Thread
            }

            // 检测语言支持
            val supportedLanguages = FontParser.getSupportedLanguages(tempFile)
            val langTag = getCurrentLanguageTag()
            val supportsCurrent = langTag in supportedLanguages

            // 计算MD5
            val md5 = tempFile.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }

            // 检查是否已存在（按MD5）
            val prefs = ctx.getSharedPreferences("font_settings", Context.MODE_PRIVATE)
            val json = prefs.getString("custom_fonts", "[]") ?: "[]"
            val arr = org.json.JSONArray(json)
            var exists = false
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("md5") == md5) {
                    exists = true; break
                }
            }

            if (exists) {
                tempFile.delete()
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    val d = MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.font_already_added))
                        .setPositiveButton(R.string.ok, null)
                        .create()
                    d.show()
                    FontManager.applyFontToDialog(d)
                }
                return@Thread
            }

            // 存入缓存
            val fontInfo = FontParser.getFontFamilyName(tempFile)
            val safeName = (fontInfo ?: "font").replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff]"), "_")
            val uniqueName = "${safeName}_${System.currentTimeMillis()}.${ext}"
            val finalFile = File(ctx.filesDir, "fonts/$uniqueName")
            if (!finalFile.parentFile!!.exists()) finalFile.parentFile!!.mkdirs()
            tempFile.copyTo(finalFile, overwrite = false)
            tempFile.delete()
            FontLangCache.putSupportedLanguages(ctx, finalFile.absolutePath, supportedLanguages)

            activity?.runOnUiThread {
                loadingDialog.dismiss()
                if (!supportsCurrent) {
                    finalFile.delete()
                    FontLangCache.remove(ctx, finalFile.absolutePath)
                    val langName = getCurrentLanguageName()
                    val d = MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.font_no_language_support, langName))
                        .setPositiveButton(R.string.ok, null)
                        .create()
                    d.show()
                    FontManager.applyFontToDialog(d)
                } else {
                    // 添加字体到列表
                    val name = fontInfo ?: finalFile.nameWithoutExtension
                    val obj = org.json.JSONObject().apply {
                        put("name", name)
                        put("path", finalFile.absolutePath)
                        put("md5", md5)
                    }
                    arr.put(obj)
                    prefs.edit().putString("custom_fonts", arr.toString()).apply()
                    showFontDialog()
                }
            }
        }.start()
    }

    private fun showDeleteFontDialog(font: FontParser.FontInfo) {
        val d = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_font)
            .setMessage(getString(R.string.delete_font_confirm, font.familyName))
            .setPositiveButton(R.string.delete) { _, _ ->
                FontManager.removeCustomFont(requireContext(), font)
                syncFontFamily()
                updateFontDisplay()
                showFontDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.show()
        FontManager.applyFontToDialog(d)
    }

    private fun updateFontDisplay() {
        val selectedPath = FontManager.getSelectedFontPath(requireContext())
        fontValue = if (selectedPath.isEmpty()) {
            getString(R.string.font_default)
        } else {
            val fonts = FontManager.getCustomFonts(requireContext())
            fonts.find { it.filePath == selectedPath }?.familyName ?: getString(R.string.font_default)
        }
    }

    /** 把当前选中的字体同步到 Compose 主题（FontFamily） */
    private fun syncFontFamily() {
        FontManager.init(requireContext())
        fontFamily = FontManager.currentTypeface?.let { FontFamily(it) }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        // 尝试从 ContentResolver 获取文件名
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        // fallback: 从 URI path 提取
        return uri.lastPathSegment
    }

    /**
     * 当前【实际生效】的语言标签（zh/en/ja/ko）。
     *
     * 系统语言不在支持范围内时，MainActivity.applyLanguage() 会把界面兜底成英文；
     * 这里必须采用同一规则，否则会拿系统语言（如 fr）去查字体的语言覆盖，
     * 而 FontParser 只会返回 en/zh/ja/ko，于是任何字体都被判定为"不支持当前语言"而无法添加。
     */
    private fun getCurrentLanguageTag(): String {
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val language = when {
            currentLocale.startsWith("zh") -> "zh"
            currentLocale.startsWith("ja") -> "ja"
            currentLocale.startsWith("ko") -> "ko"
            currentLocale.startsWith("en") -> "en"
            else -> java.util.Locale.getDefault().language
        }
        return if (language in SUPPORTED_LANGUAGES) language else "en"
    }

    private fun getCurrentLanguageName(): String {
        return when (getCurrentLanguageTag()) {
            "zh" -> getString(R.string.lang_simplified_chinese)
            "ja" -> getString(R.string.lang_japanese)
            "ko" -> getString(R.string.lang_korean)
            "en" -> getString(R.string.lang_english)
            else -> getCurrentLanguageTag()
        }
    }
}
