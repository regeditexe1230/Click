package com.yjc.click

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
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
import androidx.lifecycle.lifecycleScope
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
    private var autoCheckUpdates by mutableStateOf(false)
    private var colorSchemeExpanded by mutableStateOf(false)
    private var colorExpanded by mutableStateOf(false)
    private var backgroundExpanded by mutableStateOf(false)
    // 应用背景的预设列表（这一版预设只是个名字，见 BackgroundStore）
    private var presets by mutableStateOf<List<BackgroundStore.Preset>>(emptyList())
    private var selectedPresetId by mutableStateOf<String?>(null)
    private var expandedPresetIds by mutableStateOf<Set<String>>(emptySet())

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        handleFontSelected(uri)
    }

    /** 正在给哪个预设选图片（选择器回来后要用） */
    private var pendingImagePresetId: String? = null

    /**
     * 背景图选择：和添加字体一样走 OpenDocument + MIME 过滤，只列图片，
     * 非图片文件在选择器里就是灰的、点不了。
     */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val presetId = pendingImagePresetId
        pendingImagePresetId = null
        if (uri == null || presetId == null) return@registerForActivityResult
        handleImageSelected(presetId, uri)
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
                ClickTheme(
                    fontFamily = fontFamily,
                    darkTheme = AppTheme.isDark,
                    dynamicColor = AppTheme.useDynamicColor,
                    colorKey = AppTheme.colorKey,
                ) {
                    SettingsScreen(
                        languageValue = languageValue,
                        fontValue = fontValue,
                        theme = theme,
                        colorSchemeExpanded = colorSchemeExpanded,
                        colorExpanded = colorExpanded,
                        backgroundExpanded = backgroundExpanded,
                        dynamicColorChecked = dynamicColorChecked,
                        autoCheckUpdates = autoCheckUpdates,
                        colorKey = AppTheme.colorKey,
                        onLanguageClick = { showLanguageDialog() },
                        onFontClick = { showFontDialog() },
                        onThemeSelected = { newTheme, origin -> applyTheme(newTheme, origin) },
                        onColorSchemeHeaderClick = { colorSchemeExpanded = !colorSchemeExpanded },
                        onColorHeaderClick = { colorExpanded = !colorExpanded },
                        onBackgroundHeaderClick = { backgroundExpanded = !backgroundExpanded },
                        onDynamicColorChange = { checked ->
                            dynamicColorChecked = checked
                            requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("dynamic_color", checked).apply()
                            // 颜色排收起 300ms / 展开 400ms（与 SettingsScreen 里的动画一致），
                            // 只有它可见（颜色区块展开着）时才需要等它动完再翻配色
                            val rowAnimMs = if (colorExpanded) (if (checked) 300L else 400L) + 30L else 0L
                            AppTheme.applyDynamicColor(requireActivity(), checked, rowAnimMs)
                        },
                        onColorSelected = { key -> AppTheme.applyColor(requireActivity(), key) },
                        presets = presets,
                        selectedPresetId = selectedPresetId,
                        expandedPresetIds = expandedPresetIds,
                        onAddPreset = { addPreset() },
                        onPresetSelect = { id -> selectPreset(id) },
                        onPresetToggleExpand = { id -> togglePresetExpanded(id) },
                        onPresetDelete = { id -> confirmDeletePreset(id) },
                        onPresetAddImage = { id ->
                            pendingImagePresetId = id
                            imagePickerLauncher.launch(arrayOf("image/*"))
                        },
                        onPresetRemoveImage = { id -> removePresetImage(id) },
                        onPresetImageAlpha = { id, value -> setPresetAlpha(id, value, scrim = false) },
                        onPresetScrimAlpha = { id, value -> setPresetAlpha(id, value, scrim = true) },
                        onPresetParamsCommit = { persistPresets() },
                        // 更新三行：状态都放在 UpdateStore 里，检查/下载由 UpdateManager 跑
                        onAutoCheckUpdatesChange = { checked ->
                            autoCheckUpdates = checked
                            UpdateStore.setAutoCheck(requireContext(), checked)
                            // 刚打开就先查一次，用户能立刻看到结果
                            if (checked) UpdateManager.check(requireContext(), viewLifecycleOwner.lifecycleScope)
                        },
                        updateChannel = UpdateStore.channel,
                        updateStatus = UpdateStore.status,
                        onUpdateChannelClick = { showUpdateChannelDialog() },
                        onCheckUpdatesClick = {
                            UpdateManager.check(requireContext(), viewLifecycleOwner.lifecycleScope)
                        },
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
        autoCheckUpdates = UpdateStore.autoCheck
        // 二级面板的展开状态同样跟着 bundle 走：收起一级再展开时不会丢
        expandedPresetIds = savedInstanceState?.getStringArrayList("expanded_presets")?.toSet() ?: emptySet()

        val background = BackgroundStore.load(requireContext())
        presets = background.presets
        selectedPresetId = background.selectedId
        AppBackground.refresh(requireContext())

        theme = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("app_theme", "follow_system") ?: "follow_system"

        // 动态取色开关的初始状态必须来自偏好，否则重进设置页会看到"开关是关的、配色却是动态的"
        dynamicColorChecked = dynamicColorPref()

        updateLanguageDisplay()
        updateFontDisplay()
    }

    /**
     * 写入偏好并切换主题；由 [AppTheme.apply] 负责截取旧画面并播放"圆形揭示"过渡
     * （圆心 = 手指点击的位置，见 SettingsScreen 的 ThemeOption）。
     */
    private fun applyTheme(newTheme: String, origin: androidx.compose.ui.geometry.Offset? = null) {
        val ctx = requireContext()
        // 注意：必须读系统真实配置，不能用 ctx.resources —— 它已被 AppCompatDelegate 的
        // 强制日夜模式覆盖过（之前选过深色就一直是 NIGHT_YES），会导致"跟随系统"失效。
        val systemDark = (android.content.res.Resources.getSystem().configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        AppTheme.apply(requireActivity(), newTheme, systemDark, origin)
        theme = newTheme
        // 主题换了要重新下发系统栏样式，否则全面屏手势小白条那一条的背景/图标不会跟着变
        (activity as? MainActivity)?.refreshSystemBarStyle()
    }

    override fun onResume() {
        super.onResume()
        // 自定义字体改为通过 ClickTheme 下发 FontFamily：原来遍历 View 树设 typeface 的做法
        // 对 Compose 文本完全无效（设置页弹窗仍是 View，故 applyFontToDialog 继续保留）
        syncFontFamily()
        // 开关显示的是"用户设置"（偏好），不是 AppTheme 的当前渲染值：
        // 切换动态取色是延迟生效的（等开关动画播完再开始配色过渡），读 AppTheme 会拿到旧值
        dynamicColorChecked = dynamicColorPref()
        updateLanguageDisplay()
        updateFontDisplay()
    }

    private fun dynamicColorPref(): Boolean = requireContext()
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean("dynamic_color", false)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("color_scheme_expanded", colorSchemeExpanded)
        outState.putBoolean("color_expanded", colorExpanded)
        outState.putBoolean("background_expanded", backgroundExpanded)
        outState.putStringArrayList("expanded_presets", ArrayList(expandedPresetIds))
    }

    // ==================== 应用背景预设 ====================

    private fun addPreset() {
        presets = presets + BackgroundStore.newPreset(presets)
        persistPresets()
    }

    /**
     * 选中/取消选中一个预设。
     * 再点一次已经选中的圆圈就取消（回到「默认」）—— 列表里没有「默认」这一项，
     * 不给这条退路的话就只能靠删预设才能回到默认状态。
     */
    private fun selectPreset(id: String) {
        selectedPresetId = if (selectedPresetId == id) null else id
        persistPresets()
    }

    /** 展开/收起某个预设的二级面板（多个可以同时展开） */
    private fun togglePresetExpanded(id: String) {
        expandedPresetIds =
            if (id in expandedPresetIds) expandedPresetIds - id else expandedPresetIds + id
    }

    private fun confirmDeletePreset(id: String) {
        val preset = presets.firstOrNull { it.id == id } ?: return
        val dialog = MaterialAlertDialogBuilder(AppTheme.viewContext(requireContext()))
            .setTitle(R.string.delete_preset)
            .setMessage(getString(
                R.string.delete_preset_confirm,
                BackgroundStore.displayName(requireContext(), preset.seq),
            ))
            .setPositiveButton(R.string.delete) { _, _ -> deletePreset(id) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        FontManager.applyFontToDialog(dialog)
    }

    private fun deletePreset(id: String) {
        presets.firstOrNull { it.id == id }?.imagePath?.let { File(it).delete() }
        presets = presets.filterNot { it.id == id }
        // 删掉的正好是选中的那个 → 回到「默认」
        if (selectedPresetId == id) selectedPresetId = null
        expandedPresetIds = expandedPresetIds - id
        persistPresets()
    }

    // ==================== 背景图片 ====================

    /** 选完图片：拷进私有目录 → 校验能解码 → 写进预设 → 立刻生效 */
    private fun handleImageSelected(presetId: String, uri: Uri) {
        val ctx = requireContext()
        val progress = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
            val dp48 = (48 * resources.displayMetrics.density).toInt()
            setPadding(dp48, dp48, dp48, dp48)
        }
        val loading = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
            .setTitle(R.string.font_checking)
            .setView(progress)
            .setCancelable(false)
            .create()
        loading.show()

        Thread {
            val name = getFileNameFromUri(uri) ?: "background"
            val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "img" }
            val temp = File(ctx.cacheDir, "temp_background.$ext")
            val copied = try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                false
            }

            // 不是图片（比如 svg/pdf 这类 image/* 但解不开的）直接拒绝
            val valid = copied && ImageLoader.isDecodableImage(temp.absolutePath)
            var saved: File? = null
            if (valid) {
                val safe = name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff]"), "_")
                saved = File(
                    BackgroundStore.backgroundsDir(ctx),
                    "${safe.take(24).ifEmpty { "bg" }}_${System.currentTimeMillis()}.$ext",
                )
                temp.copyTo(saved, overwrite = true)
            }
            temp.delete()

            activity?.runOnUiThread {
                loading.dismiss()
                if (saved == null) {
                    showImageError(R.string.image_read_error)
                    return@runOnUiThread
                }
                val preset = presets.firstOrNull { it.id == presetId }
                if (preset == null) {
                    saved.delete()
                    return@runOnUiThread
                }
                File(preset.imagePath ?: "").takeIf { it.exists() }?.delete()
                presets = BackgroundStore.withPreset(
                    presets,
                    preset.copy(imagePath = saved.absolutePath),
                )
                persistPresets()   // 内部会刷新 AppBackground，背景立刻变
            }
        }.start()
    }

    /** 删掉这个预设的图片（右上角 ×）：文件删掉、面板回到「添加图片」 */
    private fun removePresetImage(id: String) {
        val preset = presets.firstOrNull { it.id == id } ?: return
        preset.imagePath?.let { File(it).delete() }
        presets = BackgroundStore.withPreset(presets, preset.copy(imagePath = null))
        persistPresets()
    }

    /** 滑块/输入框改透明度：改内存里的预设并**立刻**反映到背景上（拖动过程中就要看到变化），提交时再落盘 */
    private fun setPresetAlpha(id: String, value: Int, scrim: Boolean) {
        val preset = presets.firstOrNull { it.id == id } ?: return
        val clamped = value.coerceIn(0, 100)
        val updated = if (scrim) preset.copy(scrimAlpha = clamped) else preset.copy(imageAlpha = clamped)
        presets = BackgroundStore.withPreset(presets, updated)
        // 注意不能调 AppBackground.refresh()：它是从偏好重新解析的，而这时还没落盘，
        // 拖动过程中背景就不会变（之前的表现就是"松手才生效"）
        if (selectedPresetId == id) AppBackground.apply(updated)
    }

    private fun showImageError(messageRes: Int) {
        val d = MaterialAlertDialogBuilder(AppTheme.viewContext(requireContext()))
            .setTitle(R.string.error)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .create()
        d.show()
        FontManager.applyFontToDialog(d)
    }

    private fun persistPresets() {
        BackgroundStore.save(
            requireContext(),
            BackgroundStore.Snapshot(presets = presets, selectedId = selectedPresetId),
        )
        // 选中项 / 图片 / 透明度任何一处变了，外壳的背景都要跟着更新
        AppBackground.refresh(requireContext())
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

        val dialog = MaterialAlertDialogBuilder(AppTheme.viewContext(requireContext()))
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

        val dialog = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                        val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
            // 取当前配色的 primary：不能从 ctx.theme 解 colorPrimary，那是主题里写死的紫色，
            // 换配色/开动态取色都不会变（"添加字体"一直是紫的）
            val primaryColor = AppTheme.currentPrimaryArgb(ctx)

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

    // ==================== 更新 ====================

    /** 选更新分支：正式版 / 测试版，每项下面一行小字说明（和语言、字体弹窗同一套单选样式） */
    private fun showUpdateChannelDialog() {
        val ctx = requireContext()
        val channels = UpdateChannel.entries
        val currentIndex = channels.indexOf(UpdateStore.channel).coerceAtLeast(0)
        var selected = currentIndex

        // 系统单选项只显示一行字，这里换成两行的自定义行：标题 + 小字说明
        val adapter = object : ArrayAdapter<UpdateChannel>(
            ctx,
            R.layout.dialog_single_choice_item,
            channels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                // 用弹窗自己的 context 渲染这一行，圆点/文字色才跟系统单选行一致
                val view = convertView
                    ?: LayoutInflater.from(parent.context)
                        .inflate(R.layout.dialog_single_choice_item, parent, false)
                val channel = channels[position]
                view.findViewById<TextView>(R.id.choice_title).setText(channel.labelRes)
                view.findViewById<TextView>(R.id.choice_desc).setText(channel.descRes)
                view.findViewById<CheckedTextView>(R.id.choice_mark).isChecked = position == selected
                return view
            }
        }

        val dialog = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
            .setTitle(R.string.update_channel)
            .setSingleChoiceItems(adapter, currentIndex) { dialog, which ->
                selected = which
                val picked = channels[which]
                val changed = picked != UpdateStore.channel
                UpdateStore.setChannel(ctx, picked)
                dialog.dismiss()
                // 换了通道就按新通道查一次，省得用户再点一次「检查更新」
                if (changed) UpdateManager.check(ctx, viewLifecycleOwner.lifecycleScope)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
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
        val loadingDialog = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                    val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                    val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                    val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                    val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
                    val d = MaterialAlertDialogBuilder(AppTheme.viewContext(ctx))
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
        val d = MaterialAlertDialogBuilder(AppTheme.viewContext(requireContext()))
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
