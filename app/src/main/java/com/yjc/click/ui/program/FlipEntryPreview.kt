package com.yjc.click.ui.program

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 三种「进入节点图」磁贴动画的预览台 —— 专门为 Android Studio 的 Compose 编辑器（预览）做的。
 *
 * 用法（在 Android Studio 里）：
 *   1. 打开本文件，编辑器右上角会出现三个预览（转门 / 磁贴长大 / 磁贴翻转）；
 *   2. 点预览左上角的 ▶（Interactive Mode / 交互模式）；
 *   3. 点「播放进入 / 播放退出」看整段动画，或直接拖「进度」滑块逐帧看。
 *
 * 这里的几何与生产代码同源：
 *   - 转门 = TurnstileProjection.turnstileQuad（与 ProgramScreen.turnstileLayer 同一套投影），
 *   - 翻转 = ProgramScreen.FlipFront 里 setPolyToPoly 的近边/远边透视公式，
 *   - 长大 = ProgramScreen 的 flipRect + 圆角收敛。
 * 预览只是把三种档并排摆出来让你选；选定哪一档，改 ProgramScreen.kt:1158 的
 * `FLIP_PROFILE`（或由我来改并装到你手机）就切到那一档。
 */

/** 预览台外壳：按钮播放 + 滑块逐帧。 */
@Composable
private fun FlipEntryStage(profileLabel: String, profile: StageProfile, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { progress.animateTo(0f, tween(128)) }
            }) { Text("播放退出") }
            Button(onClick = {
                scope.launch { progress.snapTo(0f); progress.animateTo(1f, tween(300)) }
            }) { Text("播放进入") }
            Text(
                text = "$profileLabel · p=${(progress.value * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = progress.value,
            onValueChange = { v ->
                scope.launch { progress.snapTo(v) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFEDEDF2)),
        ) {
            Stage(profile = profile, progress = progress.value)
        }
    }
}

enum class StageProfile { TURNSTILE, EXPAND, FLIP }

/** 一块「舞台」：窗口全尺寸，按档位画进入动画的当前帧。 */
@Composable
private fun Stage(profile: StageProfile, progress: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        when (profile) {
            StageProfile.TURNSTILE -> {
                // 图页：绕屏幕左外侧 40dp 竖轴从 80° 转回来 + 淡入（生产 TURNSTILE_IN_DEG=80、轴 -40dp、P=600dp）
                val theta = 80f * (1f - FastOutSlowInEasing.transform(progress))
                Box(
                    Modifier
                        .fillMaxSize()
                        .alphaMod(progress)
                        .drawWithContent {
                            val q = turnstileQuad(
                                thetaDeg = theta,
                                originX = with(density) { (-40).dp.toPx() },
                                originY = size.height / 2f,
                                perspective = with(density) { 600.dp.toPx() },
                                width = size.width,
                                height = size.height,
                            )
                            if (q == null) {
                                this@drawWithContent.drawContent()
                            } else {
                                val src = floatArrayOf(
                                    0f, 0f, size.width, 0f,
                                    size.width, size.height, 0f, size.height,
                                )
                                val m = android.graphics.Matrix()
                                m.setPolyToPoly(src, 0, q, 0, 4)
                                drawContext.canvas.nativeCanvas.concat(m)
                                this@drawWithContent.drawContent()
                            }
                        },
                ) { FakeGraphPage() }
                // 列表页（磁贴）原地淡出
                Box(Modifier.fillMaxSize().alphaMod(1f - progress)) { FakeTile() }
            }
            StageProfile.EXPAND -> {
                // 磁贴长大：一块带圆角的「窗口」从磁贴位置长满全屏，节点图在它上面淡入
                val p = FastOutSlowInEasing.transform(progress)
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val inset = 0.18f * size.minDimension * (1f - p)
                            val left = inset
                            val top = inset
                            val right = size.width - inset
                            val bottom = size.height - inset
                            val radius = 16.dp.toPx() * (1f - p * 0.6f)
                            clipPath(
                                Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            Rect(left, top, right, bottom),
                                            CornerRadius(radius, radius),
                                        ),
                                    )
                                },
                            ) { this@drawWithContent.drawContent() }
                        },
                ) { FakeTile() }
                Box(Modifier.fillMaxSize().alphaMod(progress)) { FakeGraphPage() }
            }
            StageProfile.FLIP -> {
                val half = 0.5f
                if (progress < half) {
                    // 正面磁贴 0°→90°
                    val q = progress / half
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent { drawFlipHalf(q, true, this@drawWithContent) },
                    ) { FakeTile() }
                } else {
                    // 背面节点图 90°→180°（q=1 时整个平面正对屏幕，直接原样画）
                    val q = (progress - half) / (1f - half)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                if (q >= 0.999f) this@drawWithContent.drawContent()
                                else drawFlipHalf(q, false, this@drawWithContent)
                            },
                    ) { FakeGraphPage() }
                }
            }
        }
    }
}

private fun Modifier.alphaMod(a: Float): Modifier =
    drawWithContent { drawContext.canvas.saveLayer(size.toRect(), androidx.compose.ui.graphics.Paint().apply { alpha = a.coerceIn(0f, 1f) }); drawContent(); drawContext.canvas.restore() }

private fun androidx.compose.ui.geometry.Size.toRect(): Rect = Rect(0f, 0f, width, height)

/**
 * 生产 FlipFront 的同款透视：近边（左）不动、远边（右）转进屏幕变矮。
 * [front]：q=0 正面平放 → q=1 侧立 90°；背面则是 q=0 侧立 → q=1 再转 90°（合起来 180°，即翻过去）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlipHalf(
    q: Float,
    front: Boolean,
    content: androidx.compose.ui.graphics.drawscope.ContentDrawScope,
) {
    val theta = (PI / 2.0).toFloat() * if (front) q else (1f + q)
    val sinT = sin(theta).toFloat()
    val cosT = cos(theta).toFloat()
    val halfW = size.width / 2f
    val halfH = size.height / 2f
    val d = 1.6f * size.width
    val sNear = d / (d - halfW * sinT)
    val sFar = d / (d + halfW * sinT)
    val far = sFar / sNear
    val src = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height)
    val dst = floatArrayOf(
        halfW - halfW * cosT, halfH - halfH,
        halfW + halfW * cosT * far, halfH - halfH * far,
        halfW + halfW * cosT * far, halfH + halfH * far,
        halfW - halfW * cosT, halfH + halfH,
    )
    val m = android.graphics.Matrix()
    m.setPolyToPoly(src, 0, dst, 0, 4)
    drawContext.canvas.nativeCanvas.concat(m)
    content.drawContent()
}

/** 假磁贴：标题 + 颜色点 + 三个按钮，长得和列表里那张卡片一样。 */
@Composable
private fun FakeTile() {
    Box(
        modifier = Modifier
            .padding(24.dp)
            .size(width = 300.dp, height = 150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFF8E7CFF)))),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("程序 1", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Color(0xFFFFC048), Color(0xFF4DD0E1), Color(0xFF81C784)).forEach { c ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/** 假节点图：点阵画布 + 顶栏 + 一张节点卡片，代表进入后的图页。 */
@Composable
private fun FakeGraphPage() {
    Box(Modifier.fillMaxSize().background(Color(0xFFFEFDFF))) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                var y = step / 2
                while (y < size.height) {
                    drawCircle(Color(0xFFE0DDEB), radius = 2f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFFF2F0FA)),
        )
        Box(
            Modifier
                .padding(28.dp)
                .size(width = 190.dp, height = 84.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF5B8DEF)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("点击", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Color(0xFFFFC048), Color(0xFF4DD0E1), Color(0xFF81C784)).forEach { c ->
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 440, heightDp = 700, name = "转门 TURNSTILE（当前生产档）")
@Composable
private fun PreviewTurnstile() {
    MaterialTheme { FlipEntryStage("转门 TURNSTILE", StageProfile.TURNSTILE) }
}

@Preview(showBackground = true, widthDp = 440, heightDp = 700, name = "磁贴长大 EXPAND")
@Composable
private fun PreviewExpand() {
    MaterialTheme { FlipEntryStage("磁贴长大 EXPAND", StageProfile.EXPAND) }
}

@Preview(showBackground = true, widthDp = 440, heightDp = 700, name = "磁贴翻转 FLIP")
@Composable
private fun PreviewFlip() {
    MaterialTheme { FlipEntryStage("磁贴翻转 FLIP", StageProfile.FLIP) }
}
