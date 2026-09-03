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
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class SettingsFragment : Fragment() {

    private lateinit var languageValue: TextView
    private lateinit var fontValue: TextView
    private var colorSchemeExpanded = false

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
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        languageValue = view.findViewById(R.id.settings_language_value)
        fontValue = view.findViewById(R.id.settings_font_value)

        view.findViewById<View>(R.id.settings_language)?.setOnClickListener {
            showLanguageDialog()
        }

        view.findViewById<View>(R.id.settings_font)?.setOnClickListener {
            showFontDialog()
        }

        // 配色方案：点击展开/收回
        colorSchemeExpanded = savedInstanceState?.getBoolean("color_scheme_expanded", false) ?: false
        val colorSchemeOptions = view.findViewById<View>(R.id.color_scheme_options)
        val radioLight = view.findViewById<android.widget.RadioButton>(R.id.radio_light_theme)
        val radioDark = view.findViewById<android.widget.RadioButton>(R.id.radio_dark_theme)
        val radioFollow = view.findViewById<android.widget.RadioButton>(R.id.radio_follow_system_theme)
        val colorSchemeValue = view.findViewById<TextView>(R.id.settings_color_scheme_value)

        // 恢复展开状态
        if (colorSchemeExpanded) {
            colorSchemeOptions.visibility = View.VISIBLE
            colorSchemeOptions.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }

        // 加载当前主题设置
        val themePrefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentTheme = themePrefs.getString("app_theme", "follow_system") ?: "follow_system"

        fun updateRadioState(theme: String) {
            radioLight.isChecked = theme == "light"
            radioDark.isChecked = theme == "dark"
            radioFollow.isChecked = theme == "follow_system"
            colorSchemeValue.text = when (theme) {
                "light" -> getString(R.string.light_theme)
                "dark" -> getString(R.string.dark_theme)
                else -> getString(R.string.follow_system)
            }
        }
        updateRadioState(currentTheme)

        fun applyTheme(theme: String) {
            themePrefs.edit().putString("app_theme", theme).apply()
            updateRadioState(theme)
            when (theme) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        radioLight.setOnClickListener { applyTheme("light") }
        radioDark.setOnClickListener { applyTheme("dark") }
        radioFollow.setOnClickListener { applyTheme("follow_system") }

        // 点击缩略图区域也能切换主题
        view.findViewById<View>(R.id.option_light_theme)?.setOnClickListener { applyTheme("light") }
        view.findViewById<View>(R.id.option_dark_theme)?.setOnClickListener { applyTheme("dark") }
        view.findViewById<View>(R.id.option_follow_system_theme)?.setOnClickListener { applyTheme("follow_system") }

        view.findViewById<View>(R.id.settings_color_scheme_header)?.setOnClickListener {
            if (colorSchemeExpanded) {
                collapseSection(colorSchemeOptions)
            } else {
                expandSection(colorSchemeOptions)
            }
            colorSchemeExpanded = !colorSchemeExpanded
        }

        // 个性化设置项（暂无功能）
        view.findViewById<View>(R.id.settings_color)?.setOnClickListener { }
        view.findViewById<View>(R.id.settings_background)?.setOnClickListener { }

        updateLanguageDisplay()
        updateFontDisplay()
    }

    override fun onResume() {
        super.onResume()
        FontManager.init(requireContext())
        view?.let { FontManager.applyFont(it) }
        updateLanguageDisplay()
        updateFontDisplay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("color_scheme_expanded", colorSchemeExpanded)
    }

    // ==================== 展开/收起动画 ====================

    private fun expandSection(view: View) {
        if (view.visibility == View.VISIBLE && view.layoutParams.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) return

        val prevAnimator = view.getTag(R.id.anim_cancel_tag) as? android.animation.ValueAnimator
        prevAnimator?.cancel()

        view.visibility = View.VISIBLE
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = view.measuredHeight
        val params = view.layoutParams
        params.height = 1
        view.layoutParams = params

        var cancelled = false
        val animator = android.animation.ValueAnimator.ofInt(1, targetHeight)
        view.setTag(R.id.anim_cancel_tag, animator)
        animator.duration = 400
        animator.interpolator = android.view.animation.OvershootInterpolator(0.6f)
        animator.addUpdateListener { anim ->
            params.height = anim.animatedValue as Int
            view.layoutParams = params
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.setTag(R.id.anim_cancel_tag, null)
                if (!cancelled) {
                    params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = params
                }
            }
        })
        animator.start()
    }

    private fun collapseSection(view: View) {
        if (view.visibility == View.GONE) return

        val prevAnimator = view.getTag(R.id.anim_cancel_tag) as? android.animation.ValueAnimator
        prevAnimator?.cancel()

        val currentHeight = view.height
        if (currentHeight <= 0) {
            view.visibility = View.GONE
            return
        }

        var cancelled = false
        val params = view.layoutParams
        val animator = android.animation.ValueAnimator.ofInt(currentHeight, 0)
        view.setTag(R.id.anim_cancel_tag, animator)
        animator.duration = 300
        animator.interpolator = android.view.animation.AccelerateInterpolator(2f)
        animator.addUpdateListener { anim ->
            params.height = anim.animatedValue as Int
            view.layoutParams = params
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.setTag(R.id.anim_cancel_tag, null)
                if (!cancelled) {
                    view.visibility = View.GONE
                    view.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        })
        animator.start()
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
        // 切换语言时重置字体为系统默认
        FontManager.setSelectedFont(requireContext(), null)
        val localeListCompat = if (localeCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(localeCode)
        }
        AppCompatDelegate.setApplicationLocales(localeListCompat)
    }

    private fun updateLanguageDisplay() {
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        languageValue.text = when {
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
                    FontManager.applyFont(requireActivity().window.decorView)
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
                    FontManager.applyFont(requireActivity().window.decorView)
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
                FontManager.applyFont(requireActivity().window.decorView)
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
        fontValue.text = if (selectedPath.isEmpty()) {
            getString(R.string.font_default)
        } else {
            val fonts = FontManager.getCustomFonts(requireContext())
            fonts.find { it.filePath == selectedPath }?.familyName ?: getString(R.string.font_default)
        }
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

    private fun getCurrentLanguageTag(): String {
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            currentLocale.startsWith("zh") -> "zh"
            currentLocale.startsWith("ja") -> "ja"
            currentLocale.startsWith("ko") -> "ko"
            currentLocale.startsWith("en") -> "en"
            else -> java.util.Locale.getDefault().language
        }
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
