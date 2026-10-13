package com.yjc.click.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickText

/**
 * 说明类弹窗的公共壳：样式跟「删除手势」那类系统弹窗（MaterialAlertDialogBuilder）对齐 ——
 * 标题用主题的 headlineSmall、正文用主题的 bodyMedium，底栏一行里左边「不再提醒」勾选框、
 * 右边按钮，勾选框不要 48dp 最小触摸区。
 *
 * @param onConfirm 点右边主按钮，参数 = 「不再提醒」是否勾上
 * @param onDismiss 点取消 / 点外面 / 返回键，参数同上
 */
@Composable
private fun PromptDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    @StringRes confirmRes: Int,
    onConfirm: (Boolean) -> Unit,
    onDismiss: (Boolean) -> Unit,
    showIgnore: Boolean = false,
    showCancel: Boolean = true,
) {
    var ignored by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(ignored) },
        title = {
            ClickText(
                stringResource(titleRes),
                MaterialTheme.typography.headlineSmall.fontSize,
                MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            ClickText(
                text = stringResource(messageRes),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIgnore) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { ignored = !ignored },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                        ) {
                            Checkbox(checked = ignored, onCheckedChange = { ignored = it })
                        }
                        ClickText(
                            text = stringResource(R.string.dialog_dont_ask_again),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showCancel) {
                    TextButton(onClick = { onDismiss(ignored) }) {
                        ClickText(
                            stringResource(R.string.cancel),
                            14.sp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onConfirm(ignored) }) {
                    ClickText(
                        stringResource(confirmRes),
                        14.sp,
                        MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

/** 通知权限被拒时的说明框：只有一个「去开启」，点完跳系统通知设置 */
@Composable
internal fun NotificationPermissionPromptDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PromptDialog(
        titleRes = R.string.notification_permission_title,
        messageRes = R.string.notification_permission_message,
        confirmRes = R.string.notification_permission_confirm,
        onConfirm = { onConfirm() },
        onDismiss = { onDismiss() },
        showCancel = false,
    )
}

/**
 * 录制通道被降成静默时的说明框：「去开启」跳该通道设置页；「取消」= 这次照样录。
 * 勾上「不再提醒」以后都不再检查。
 */
@Composable
internal fun RecordingHeadsUpPromptDialog(
    onGoOpen: (Boolean) -> Unit,
    onLater: (Boolean) -> Unit,
) {
    PromptDialog(
        titleRes = R.string.notification_heads_up_title,
        messageRes = R.string.notification_heads_up_message,
        confirmRes = R.string.notification_heads_up_confirm,
        onConfirm = onGoOpen,
        onDismiss = onLater,
        showIgnore = true,
    )
}

/** 上次录完没能自动跳回前台时的说明框（MIUI/HyperOS 的「后台弹出界面」）：给一个直达权限页的入口 */
@Composable
internal fun BackPopupPromptDialog(
    onGoOpen: (Boolean) -> Unit,
    onLater: (Boolean) -> Unit,
) {
    PromptDialog(
        titleRes = R.string.back_popup_title,
        messageRes = R.string.back_popup_message,
        confirmRes = R.string.back_popup_confirm,
        onConfirm = onGoOpen,
        onDismiss = onLater,
        showIgnore = true,
    )
}
