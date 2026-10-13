package com.yjc.click

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 用 Activity 生命周期判断「本应用现在是不是在前台」。
 *
 * 为什么不直接用 ActivityManager.runningAppProcesses 的重要性：
 * 在部分 ROM（实测小米 8.1 / MIUI）上，即使界面已经退到后台，我们自己的进程
 * 仍被报成 IMPORTANCE_FOREGROUND，于是「等退到后台再发通知」的判断永远为真，
 * 通知就会被当成前台发的通知、弹不出横幅。
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var startedCount = 0

    val isForeground: Boolean
        get() = startedCount > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedCount > 0) startedCount--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
