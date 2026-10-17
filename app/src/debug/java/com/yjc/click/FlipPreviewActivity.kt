package com.yjc.click

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yjc.click.ui.program.FlipEntryStage
import com.yjc.click.ui.program.Stage
import com.yjc.click.ui.program.StageProfile

/**
 * 仅供 debug 变体使用的动画预览台（release 包里不存在）。
 *
 * 顶部切换四档，下面就是那套「播放进入 / 播放退出 / 进度滑块」的舞台，
 * 抓帧时用它把动画逐帧走一遍。
 */
class FlipPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PreviewRoot() } }
    }
}

@Composable
private fun PreviewRoot() {
    var profile by remember { mutableStateOf(StageProfile.SLAB) }
    // -1 = 交给播放台自己动；0..12 = 定格在这一帧（p = step/12），方便逐帧抓图
    var step by remember { mutableStateOf(-1) }
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StageProfile.values().forEach { p ->
                TextButton(onClick = { profile = p }) {
                    Text(if (p == profile) "▣ ${p.name}" else p.name)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            TextButton(onClick = { step = -1 }) { Text(if (step < 0) "▣播放" else "播放") }
            (0..12).forEach { i ->
                TextButton(onClick = { step = i }) {
                    Text(if (step == i) "[$i]" else "$i")
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (step < 0) {
                FlipEntryStage(profileLabel = profile.name, profile = profile)
            } else {
                Stage(profile = profile, progress = step / 12f)
            }
        }
    }
}
