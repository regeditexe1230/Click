package com.yjc.click

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

        // 语言设置
        val languageItem = view.findViewById<View>(R.id.settings_language)
        languageItem?.let {
            it.findViewById<ImageView>(R.id.settings_icon)?.setImageResource(R.drawable.ic_settings)
            it.findViewById<TextView>(R.id.settings_title)?.text = "语言"
            it.findViewById<TextView>(R.id.settings_description)?.text = "更改应用语言"
            languageValue = it.findViewById(R.id.settings_value)!!
            it.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                    intent.data = Uri.fromParts("package", requireContext().packageName, null)
                    startActivity(intent)
                } else {
                    showLanguageDialog()
                }
            }
        }

        // 字体设置
        val fontItem = view.findViewById<View>(R.id.settings_font)
        fontItem?.let {
            it.findViewById<ImageView>(R.id.settings_icon)?.setImageResource(R.drawable.ic_settings)
            it.findViewById<TextView>(R.id.settings_title)?.text = "字体"
            it.findViewById<TextView>(R.id.settings_description)?.text = "更改应用字体"
            it.findViewById<TextView>(R.id.settings_value)?.text = "默认"
        }

        updateLanguageDisplay()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("跟随系统", "简体中文", "繁體中文", "English", "日本語", "한국어")
        val localeCodes = arrayOf("", "zh-CN", "zh-TW", "en", "ja", "ko")

        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLocale = prefs.getString("app_locale", "")
        val currentIndex = localeCodes.indexOf(currentLocale).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择语言")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLocale = localeCodes[which]
                prefs.edit().putString("app_locale", selectedLocale).apply()
                applyLanguage(selectedLocale)
                updateLanguageDisplay()
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
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLocale = prefs.getString("app_locale", "")
        languageValue.text = when (currentLocale) {
            "" -> "跟随系统"
            "zh-CN" -> "简体中文"
            "zh-TW" -> "繁體中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> "跟随系统"
        }
    }
}
