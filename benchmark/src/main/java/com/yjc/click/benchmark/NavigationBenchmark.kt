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
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.res("nav_home")), 5_000)
    }

    /** 底部导航来回切：程序 → 设置 → 首页 ×3（用户反馈最卡的地方） */
    @Test
    fun tabSwitching() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res("nav_home")), 5_000)
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
            startActivityAndWait()
            device.wait(Until.hasObject(By.res("nav_home")), 5_000)
        },
    ) {
        repeat(4) {
            tap("radioSwipe")
            tap("radioClick")
        }
    }

    private fun MacrobenchmarkScope.tap(tag: String) {
        val node = device.wait(Until.findObject(By.res(tag)), 2_000) ?: return
        node.click()
        device.waitForIdle(500)
    }

    private companion object {
        const val PACKAGE = "com.yjc.click"
    }
}
