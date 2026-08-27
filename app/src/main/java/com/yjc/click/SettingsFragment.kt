package com.yjc.click

import android.content.Context
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLocale = localeCodes[which]
                applyLanguage(selectedLocale)
                updateLanguageDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
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
            currentLocale.isEmpty() || currentLocale == "und" -> getString(R.string.lang_follow_system)
            currentLocale.startsWith("zh-CN") -> getString(R.string.lang_simplified_chinese)
            currentLocale.startsWith("zh-TW") || currentLocale.startsWith("zh-Hant") -> getString(R.string.lang_traditional_chinese)
            currentLocale.startsWith("en") -> getString(R.string.lang_english)
            currentLocale.startsWith("ja") -> getString(R.string.lang_japanese)
            currentLocale.startsWith("ko") -> getString(R.string.lang_korean)
            else -> getString(R.string.lang_follow_system)
        }
    }
}
