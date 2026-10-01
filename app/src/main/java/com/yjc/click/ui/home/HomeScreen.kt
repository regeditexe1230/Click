package com.yjc.click.ui.home

import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickText
import com.yjc.click.ui.theme.LocalSectionBackground
import com.yjc.click.ui.theme.PlatformEasing
import android.view.animation.AccelerateInterpolator

/** 框/分段控件填充色：见 [LocalSectionBackground]（固定配色 = 旧版 #206750A4，动态取色跟随 primary） */
private val SectionShape = RoundedCornerShape(12.dp)
private val IndicatorShape = RoundedCornerShape(8.dp)
private val SegmentShape = RoundedCornerShape(10.dp)

/** 旧实现：展开/收起都是 250ms，展开用 DecelerateInterpolator(2f)，收起用 AccelerateInterpolator(2f) */
private val ExpandEasing = PlatformEasing(DecelerateInterpolator(2f))
private val CollapseEasing = PlatformEasing(AccelerateInterpolator(2f))

/** 分段控件指示器滑动：250ms DecelerateInterpolator() */
private val IndicatorEasing = PlatformEasing(DecelerateInterpolator())

/**
 * 模拟 android:layout_marginBottom 负值（Compose 的 padding 不接受负数）。
 * 旧布局用 -6dp 让"权限状态/执行模式"等小标题与下方圆角框视觉相接。
 */
private fun Modifier.negativeBottomMargin(margin: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val cut = margin.roundToPx()
    layout(placeable.width, (placeable.height - cut).coerceAtLeast(0)) {
        placeable.place(0, 0)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    statusText: String,
    isSwipeMode: Boolean,
    isGestureMode: Boolean,
    swipeX1: String,
    swipeY1: String,
    swipeX2: String,
    swipeY2: String,
    swipeDuration: String,
    delay: String,
    repeat: String,
    infinite: Boolean,
    recordedStatus: String,
    recordedVisible: Boolean,
    canStart: Boolean,
    showStartOverlay: Boolean,
    canStop: Boolean,
    canRecord: Boolean,
    onEnableService: () -> Unit,
    onEnableOverlay: () -> Unit,
    onModeSelected: (Boolean) -> Unit,
    onSwipeMethodSelected: (Boolean) -> Unit,
    onSwipeX1Change: (String) -> Unit,
    onSwipeY1Change: (String) -> Unit,
    onSwipeX2Change: (String) -> Unit,
    onSwipeY2Change: (String) -> Unit,
    onSwipeDurationChange: (String) -> Unit,
    onDelayChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onInfiniteChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { testTagsAsResourceId = true }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // ---------------- 权限状态 ----------------
        SectionLabel(R.string.permission_status)
        SectionBox {
            ClickText(
                text = statusText,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().testTag("statusText"),
            )
        }

        Button(
            onClick = onEnableService,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("btnEnableService"),
        ) { ButtonLabel(R.string.enable_accessibility_service) }

        Button(
            onClick = onEnableOverlay,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("btnEnableOverlay"),
        ) { ButtonLabel(R.string.enable_overlay_permission) }

        // ---------------- 执行模式 ----------------
        SectionLabel(R.string.execution_mode, topMargin = 16.dp)
        SectionBox {
            SegmentControl(
                labels = listOf(R.string.click, R.string.swipe),
                tags = listOf("radioClick", "radioSwipe"),
                indicatorTag = "modeIndicator",
                selectedIndex = if (isSwipeMode) 1 else 0,
                onSelect = { onModeSelected(it == 1) },
            )

            AnimatedVisibility(
                visible = isSwipeMode,
                enter = expandVertically(animationSpec = tween(250, easing = ExpandEasing)) +
                        fadeIn(animationSpec = tween(250, easing = ExpandEasing)),
                exit = shrinkVertically(animationSpec = tween(250, easing = CollapseEasing)) +
                        fadeOut(animationSpec = tween(250, easing = CollapseEasing)),
            ) {
                SwipeParamsSection(
                    isGestureMode = isGestureMode,
                    swipeX1 = swipeX1, swipeY1 = swipeY1, swipeX2 = swipeX2, swipeY2 = swipeY2,
                    swipeDuration = swipeDuration,
                    recordedStatus = recordedStatus, recordedVisible = recordedVisible,
                    canRecord = canRecord,
                    onSwipeMethodSelected = onSwipeMethodSelected,
                    onSwipeX1Change = onSwipeX1Change, onSwipeY1Change = onSwipeY1Change,
                    onSwipeX2Change = onSwipeX2Change, onSwipeY2Change = onSwipeY2Change,
                    onSwipeDurationChange = onSwipeDurationChange,
                    onRecord = onRecord,
                )
            }
        }

        // ---------------- 执行设置 ----------------
        SectionLabel(R.string.execution_settings, topMargin = 12.dp)
        SectionBox {
            ClickText(
                stringResource(R.string.pre_execution_delay),
                14.sp,
                MaterialTheme.colorScheme.onSurface,
            )
            PlatformField(
                value = delay,
                onValueChange = onDelayChange,
                hintRes = R.string.delay_milliseconds,
                tag = "inputDelay",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            ClickText(
                text = stringResource(R.string.repeat_count),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformField(
                    value = repeat,
                    onValueChange = onRepeatChange,
                    hintRes = R.string.count,
                    tag = "inputRepeat",
                    enabled = !infinite,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = infinite,
                    onCheckedChange = onInfiniteChange,
                    modifier = Modifier.padding(start = 12.dp).testTag("checkInfinite"),
                )
                ClickText(
                    text = stringResource(R.string.infinite_loop),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 旧版用的是 MaterialSwitch 自带文字：轨道(137px)与文字之间间隔 44px，
                    // 这里必须补足同样间距，否则输入框权重会多占 23px、把轨道推右。
                    modifier = Modifier.padding(start = 16.76.dp),
                )
            }
        }

        // ---------------- 启动 / 停止 ----------------
        Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().testTag("btnStartFloating"),
            ) { ButtonLabel(R.string.start_floating_ball) }
            if (showStartOverlay) {
                // 旧实现里盖在禁用按钮上的透明可点层，保证缺权限时仍能触发授权流程
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("btnStartOverlay")
                        .clickableNoIndication(onStart),
                )
            }
        }

        Button(
            onClick = onStop,
            enabled = canStop,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("btnStopFloating"),
        ) { ButtonLabel(R.string.stop_floating_ball) }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** 与旧布局一致的小标题：12sp、colorOnSurfaceVariant、左内缩 12dp + 内边距 6dp、下方 -6dp */
@Composable
private fun SectionLabel(resId: Int, topMargin: Dp = 0.dp) {
    ClickText(
        text = stringResource(resId),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = topMargin, start = 12.dp)
            .negativeBottomMargin(6.dp)
            .padding(horizontal = 6.dp),
    )
}

/** 圆角框：section_border（#206750A4、12dp 圆角）+ 12dp 内边距 */
@Composable
private fun SectionBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(LocalSectionBackground.current)
            .padding(12.dp)
            .testTag("sectionBox"),
        content = content,
    )
}

@Composable
private fun ButtonLabel(resId: Int) {
    Text(text = stringResource(resId), style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/**
 * 分段控件：容器 segment_bg（#206750A4、10dp 圆角），指示器 segment_selected
 * （colorPrimary、8dp 圆角、高 36dp、左边距 4dp、宽度 = 单个分段宽），
 * 位置切换动画 250ms DecelerateInterpolator()。
 */
@Composable
private fun SegmentControl(
    labels: List<Int>,
    tags: List<String>,
    indicatorTag: String,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SegmentShape)
            .background(LocalSectionBackground.current),
    ) {
        val itemWidth = maxWidth / labels.size
        // 旧版指示器 = 项宽 + 4dp 左边距，选到最后一个选项时右边会超出容器被裁掉（实测右侧少 4dp）。
        // 这里只在会溢出时把偏移往回钳 4dp，其余选项位置与旧版完全一致。
        val indicatorInset = 4.dp
        val maxOffset = (maxWidth - itemWidth - indicatorInset).coerceAtLeast(0.dp)
        val rawOffset = if (selectedIndex > 0) itemWidth * selectedIndex else 0.dp
        val targetOffset = if (rawOffset > maxOffset) maxOffset else rawOffset
        val indicatorOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = tween(250, easing = IndicatorEasing),
            label = "segmentIndicator",
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = indicatorInset)
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .height(36.dp)
                .clip(IndicatorShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag(indicatorTag),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            labels.forEachIndexed { index, labelRes ->
                Button(
                    onClick = { onSelect(index) },
                    shape = IndicatorShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tags[index]),
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index == selectedIndex) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** 滑动参数区：分隔线 + "滑动方式" + 分段控件 + 手动参数 / 手势录制两块 */
@Composable
private fun ColumnScope.SwipeParamsSection(
    isGestureMode: Boolean,
    swipeX1: String, swipeY1: String, swipeX2: String, swipeY2: String, swipeDuration: String,
    recordedStatus: String,
    recordedVisible: Boolean,
    canRecord: Boolean,
    onSwipeMethodSelected: (Boolean) -> Unit,
    onSwipeX1Change: (String) -> Unit,
    onSwipeY1Change: (String) -> Unit,
    onSwipeX2Change: (String) -> Unit,
    onSwipeY2Change: (String) -> Unit,
    onSwipeDurationChange: (String) -> Unit,
    onRecord: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ClickText(
            text = stringResource(R.string.swipe_method),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            SegmentControl(
                labels = listOf(R.string.manual_params, R.string.gesture_recording),
                tags = listOf("radioSwipeManual", "radioSwipeGesture"),
                indicatorTag = "swipeMethodIndicator",
                selectedIndex = if (isGestureMode) 1 else 0,
                onSelect = { onSwipeMethodSelected(it == 1) },
            )
        }

        AnimatedVisibility(
            visible = !isGestureMode,
            enter = expandVertically(animationSpec = tween(250, easing = ExpandEasing)) +
                    fadeIn(animationSpec = tween(250, easing = ExpandEasing)),
            exit = shrinkVertically(animationSpec = tween(250, easing = CollapseEasing)) +
                    fadeOut(animationSpec = tween(250, easing = CollapseEasing)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                ParamLabel(R.string.start_point)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    NumberField(
                        value = swipeX1, onValueChange = onSwipeX1Change,
                        hintRes = R.string.start_x, tag = "inputSwipeX1",
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    )
                    NumberField(
                        value = swipeY1, onValueChange = onSwipeY1Change,
                        hintRes = R.string.start_y, tag = "inputSwipeY1",
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
                ParamLabel(R.string.end_point, topMargin = 8.dp)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    NumberField(
                        value = swipeX2, onValueChange = onSwipeX2Change,
                        hintRes = R.string.end_x, tag = "inputSwipeX2",
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    )
                    NumberField(
                        value = swipeY2, onValueChange = onSwipeY2Change,
                        hintRes = R.string.end_y, tag = "inputSwipeY2",
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
                ParamLabel(R.string.duration, topMargin = 8.dp)
                PlatformField(
                    value = swipeDuration,
                    onValueChange = onSwipeDurationChange,
                    hintRes = R.string.swipe_duration,
                    tag = "inputSwipeDuration",
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = isGestureMode,
            enter = expandVertically(animationSpec = tween(250, easing = ExpandEasing)) +
                    fadeIn(animationSpec = tween(250, easing = ExpandEasing)),
            exit = shrinkVertically(animationSpec = tween(250, easing = CollapseEasing)) +
                    fadeOut(animationSpec = tween(250, easing = CollapseEasing)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                if (recordedVisible) {
                    ClickText(
                        text = recordedStatus,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("lblRecordedStatus"),
                    )
                }
                Button(
                    onClick = onRecord,
                    enabled = canRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("btnRecordGesture"),
                ) { ButtonLabel(R.string.record_gesture) }
            }
        }
    }
}

@Composable
private fun ParamLabel(resId: Int, topMargin: Dp = 0.dp) {
    ClickText(
        text = stringResource(resId),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = topMargin),
    )
}

/**
 * 旧版 Widget.Material3.TextInputLayout.OutlinedBox 布局高度 = 边框盒(147px) + 顶部提示区(14px) = 161px；
 * Compose 的 OutlinedTextField 在边框上方留了 8dp(21px)，比旧版多 7px，会把后续元素整体顶下去。
 * 这里从顶部裁掉多出的 7px，边框位置随之回到旧版的 +14px 处（旧版边框顶 = 实测 1204）。
 */
private val FieldLabelTrim = 2.6667.dp

/** 裁掉布局顶部的多余留白：内容上移，占用高度同步减少（Compose 的 padding 不接受负值） */
private fun Modifier.trimTopSpace(trim: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val cut = trim.roundToPx()
    layout(placeable.width, (placeable.height - cut).coerceAtLeast(0)) {
        placeable.place(0, -cut)
    }
}

/** 与旧版 TextInputLayout（OutlinedBox 样式）等价的输入框 */
@Composable
private fun PlatformField(
    value: String,
    onValueChange: (String) -> Unit,
    hintRes: Int,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(stringResource(hintRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.trimTopSpace(FieldLabelTrim).testTag(tag),
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    hintRes: Int,
    tag: String,
    modifier: Modifier = Modifier,
) = PlatformField(
    value = value,
    onValueChange = onValueChange,
    hintRes = hintRes,
    tag = tag,
    modifier = modifier,
    keyboardType = KeyboardType.Decimal,
)
