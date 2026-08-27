package com.yjc.click

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private lateinit var languageValue: TextView

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

        // 语言设置
        view.findViewById<View>(R.id.settings_language)?.setOnClickListener {
            showLanguageDialog()
        }

        // 字体设置（暂无功能）
        view.findViewById<View>(R.id.settings_font)?.setOnClickListener {
            // 暂无功能
        }

        updateLanguageDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateLanguageDisplay()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("跟随系统", "简体中文", "繁體中文", "English", "日本語", "한국어")
        val localeCodes = arrayOf("", "zh-CN", "zh-TW", "en", "ja", "ko")

        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val currentLocale = prefs.getString("app_locale", "")
        val currentIndex = localeCodes.indexOf(currentLocale).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择语言")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLocale = localeCodes[which]
                prefs.edit().putString("app_locale", selectedLocale).apply()
                applyLanguage(selectedLocale)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyLanguage(localeCode: String) {
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
            currentLocale.isEmpty() || currentLocale == "und" -> "跟随系统"
            currentLocale.startsWith("zh-CN") -> "简体中文"
            currentLocale.startsWith("zh-TW") || currentLocale.startsWith("zh-Hant") -> "繁體中文"
            currentLocale.startsWith("en") -> "English"
            currentLocale.startsWith("ja") -> "日本語"
            currentLocale.startsWith("ko") -> "한국어"
            else -> "跟随系统"
        }
    }
}
