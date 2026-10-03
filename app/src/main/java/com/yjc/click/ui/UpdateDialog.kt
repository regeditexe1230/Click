package com.yjc.click.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.RemoteRelease
import com.yjc.click.UpdateChecker
import com.yjc.click.ui.theme.ClickText

/**
 * 有可用更新：当前版本 → 最新 + 更新日志 + 安装 / 稍后（下载中变取消）
 * 挂在壳上而不是设置页里，这样启动时自动查到新版本也能弹
 */
@Composable
internal fun UpdateDialog(
    release: RemoteRelease,
    downloading: Boolean,
    progress: Int,
    @StringRes errorRes: Int,
    ignored: Boolean,
    onIgnoreChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val currentVersion = remember { UpdateChecker.installedVersionName(context) }
    val notes = remember(release.notes) { releaseNotes(release.notes) }
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        title = {
            ClickText(stringResource(R.string.update_available), 18.sp, MaterialTheme.colorScheme.onSurface)
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ClickText(currentVersion, 14.sp, MaterialTheme.colorScheme.onSurfaceVariant)
                    Image(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(16.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    ClickText(
                        release.versionName.ifBlank { release.tag },
                        14.sp,
                        MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (notes.isNotBlank()) {
                    ClickText(
                        text = notes,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
                if (errorRes != 0) {
                    ClickText(
                        text = stringResource(errorRes),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // 下载中不显示：这时候弹窗只管进度和取消
                if (!downloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable { onIgnoreChange(!ignored) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ignored, onCheckedChange = onIgnoreChange)
                        ClickText(
                            text = stringResource(R.string.update_ignore_version),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !downloading) {
                ClickText(
                    text = if (downloading) {
                        stringResource(R.string.update_downloading, progress)
                    } else {
                        stringResource(R.string.update_install)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            if (downloading) {
                TextButton(onClick = onCancelDownload) {
                    ClickText(
                        stringResource(R.string.cancel),
                        14.sp,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TextButton(onClick = onLater) {
                    ClickText(
                        stringResource(R.string.update_later),
                        14.sp,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * 更新日志正文：去掉 markdown 记号，丢掉 GitHub 自动生成的 compare 链接
 * （只有那行链接时等于没写日志，不如不显示），剩下的按原样显示。
 */
private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]+\\)")
private val compareUrl = Regex("^https?://\\S*github\\.com/\\S*/compare/\\S*$")

private fun releaseNotes(raw: String): String = raw.lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("Full Changelog") }
    .filterNot { compareUrl.matches(it) }
    .map { it.removePrefix("### ").removePrefix("## ").removePrefix("# ") }
    .map { markdownLink.replace(it, "$1").replace("**", "") }
    .joinToString("\n")
    .trim()
