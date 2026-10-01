package com.yjc.click

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.yjc.click.ui.theme.AppTheme

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
        // 深浅色/动态取色的状态在这里先读一次：进程可能只为悬浮球服务而启动
        //（通知栏重开、START_STICKY 重启），那时没有 Activity 去初始化 AppTheme，
        // 球会被画成固定紫色、深色下还会用错主色。
        AppTheme.loadFrom(this)
    }
}
