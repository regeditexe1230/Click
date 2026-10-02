package com.yjc.click.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 切页 / 启动的帧时间基准：固定场景 + FrameTimingMetric，给 P50/P90/P99 帧时长和掉帧数，
 * 用来替代"靠手感判断有没有变快"。
 * 场景里的点击按 Compose testTag 暴露出来的 resource-id 定位（nav_home/nav_program/nav_settings、
 * radioClick/radioSwipe）。
 */
@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** 冷启动到首页可交互 */
    @Test
    fun startup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        // 不能用 CompilationMode.DEFAULT：它会通过 app 自带的 profileinstaller 装 baseline profile，
        // 而那个版本不支持新 SDK（模拟器 API 37 上直接抛异常）。测 debug 包本来也没 profile。
        compilationMode = CompilationMode.None(),
        setupBlock = { pressHome() },
    ) {
        launchApp()
    }

    /** 底部导航来回切：程序 → 设置 → 首页 ×3（用户反馈最卡的地方），首页固定"点击"模式 */
    @Test
    fun tabSwitching() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            pressHome()
            launchApp()
            tap("radioClick")
        },
    ) {
        repeat(3) {
            tap("nav_program")
            tap("nav_settings")
            tap("nav_home")
        }
    }

    /**
     * 同上，但首页切到"滑动"模式：首页有 7 个输入框，切页如果要重建整页，
     * 这里比"点击"模式（3 个输入框）贵好几倍，信噪比高——用来看"页面常驻"到底省了多少。
     */
    @Test
    fun tabSwitchingSwipe() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            pressHome()
            launchApp()
            tap("radioSwipe")
        },
    ) {
        repeat(3) {
            tap("nav_program")
            tap("nav_settings")
            tap("nav_home")
        }
    }

    /** 首页分段控件来回切：带指示器动画 + 滑动参数区展开收起 */
    @Test
    fun homeSegment() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            pressHome()
            launchApp()
        },
    ) {
        repeat(4) {
            tap("radioSwipe")
            tap("radioClick")
        }
    }

    /**
     * 启动 app 并确保真的到了首页：安装 / pm clear 之后会先弹"使用说明"和通知权限弹窗，
     * 它们会把后面的点击全吃掉（症状是 0 frames）。这里主动点掉，基准自己就能跑通。
     */
    private fun MacrobenchmarkScope.launchApp() {
        startActivityAndWait()
        while (true) {
            val dialog = device.wait(Until.findObject(By.res("android:id/button1")), 800)
                ?: device.wait(Until.findObject(By.res(DIALOG_ID)), 800)
                ?: break
            dialog.click()
            device.waitForIdle(500)
        }
        check(device.wait(Until.hasObject(By.res("nav_home")), 5_000)) { "首页没出现，前面可能还有弹窗" }
    }

    private fun MacrobenchmarkScope.tap(tag: String) {
        val node = device.wait(Until.findObject(By.res(tag)), 2_000)
        check(node != null) { "找不到控件 $tag" }
        node.click()
        device.waitForIdle(500)
    }

    private companion object {
        const val PACKAGE = "com.yjc.click"
        const val DIALOG_ID = "com.android.permissioncontroller:id/permission_allow_button"
    }
}
