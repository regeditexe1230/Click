package com.yjc.click

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 当前生效的应用背景（= 选中的那个预设携带的图与透明度）。
 *
 * 放在全局可观察状态里：设置页里选完图片/拖滑块立刻生效，
 * 外壳（MainScaffold）直接读，不用把这些参数从 Activity 一层层传下去。
 */
object AppBackground {

    var imagePath by mutableStateOf<String?>(null)
        private set

    /** 图片透明度 0–100：0 = 图看不见（只剩背景色），100 = 原图 */
    var imageAlpha by mutableStateOf(100)
        private set

    /** 黑色遮罩不透明度 0–100：把亮图压暗，深色主题下白字更清楚 */
    var scrimAlpha by mutableStateOf(0)
        private set

    /** 从偏好重新解析一次（启动时、以及添加/删除/切换预设后调用） */
    fun refresh(context: Context) {
        val snapshot = BackgroundStore.load(context)
        apply(snapshot.presets.firstOrNull { it.id == snapshot.selectedId })
    }

    fun apply(preset: BackgroundStore.Preset?) {
        imagePath = preset?.imagePath?.takeIf { it.isNotEmpty() }
        imageAlpha = preset?.imageAlpha ?: 100
        scrimAlpha = preset?.scrimAlpha ?: 0
    }
}
