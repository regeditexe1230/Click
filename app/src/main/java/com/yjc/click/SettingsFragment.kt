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
        languageValue = view.findViewById(R.id.settings_language_value)
        view.findViewById<View>(R.id.settings_language)?.setOnClickListener {
            // 直接跳转系统语言设置页
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            intent.data = Uri.fromParts("package", requireContext().packageName, null)
            startActivity(intent)
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
