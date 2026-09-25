package com.yjc.click

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ClickApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在Application级别应用主题，确保Splash Screen使用正确的深浅色
        val themePref = getSharedPreferences("settings", MODE_PRIVATE).getString("app_theme", "follow_system")
        when (themePref) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
