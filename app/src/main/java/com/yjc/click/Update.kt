package com.yjc.click

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 更新通道。
 *
 * 发布约定（.github/workflows/release.yml 会照这个打标签发 Release）：
 * 版本号写进 tag —— 正式版 `v<versionCode>`（从 main 发），测试版 `b<versionCode>`（从 beta 发）。
 * 客户端只靠 tag 就能知道通道和版本号，不用再去拉代码文件解析。
 */
enum class UpdateChannel(
    val prefKey: String,
    val tagPrefix: String,
    @StringRes val labelRes: Int,
) {
    STABLE("stable", "v", R.string.update_channel_stable),
    BETA("beta", "b", R.string.update_channel_beta),
}

/** 远端的一个可用版本 */
data class RemoteRelease(
    val versionCode: Int,
    val tag: String,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** 更新相关的全局可观察状态：设置页那三行直接读，检查/下载由 UpdateManager 驱动 */
object UpdateStore {

    private const val PREFS_NAME = "update_settings"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_CHANNEL = "channel"

    /** 启动时自动检查（旧版本这里是空按钮，默认开） */
    var autoCheck by mutableStateOf(true)
        private set

    var channel by mutableStateOf(UpdateChannel.BETA)
        private set

    /** 设置行右侧那行小字：检查中 / 已是最新 / 发现新版本 / 下载中 50%… */
    var status by mutableStateOf("")
        private set

    /** 检查到的可用版本（null = 没有更新） */
    var available by mutableStateOf<RemoteRelease?>(null)
        private set

    /** 更新弹窗是否显示（点「稍后」后置 false，但 available 还留着） */
    var dialogVisible by mutableStateOf(false)
        private set

    var downloading by mutableStateOf(false)
        private set

    /** 下载进度 0–100 */
    var progress by mutableStateOf(0)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        autoCheck = prefs.getBoolean(KEY_AUTO, true)
        channel = UpdateChannel.entries.firstOrNull { it.prefKey == prefs.getString(KEY_CHANNEL, null) }
            ?: UpdateChannel.BETA
    }

    fun setAutoCheck(context: Context, value: Boolean) {
        autoCheck = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, value).apply()
    }

    fun setChannel(context: Context, value: UpdateChannel) {
        channel = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANNEL, value.prefKey).apply()
        // 换通道后旧结果作废
        available = null
        dialogVisible = false
    }

    internal fun status(text: String) {
        status = text
    }

    internal fun release(found: RemoteRelease?, showDialog: Boolean) {
        available = found
        dialogVisible = showDialog && found != null
    }

    internal fun download(active: Boolean, percent: Int = 0) {
        downloading = active
        progress = percent
    }

    internal fun dismissDialog() {
        dialogVisible = false
    }
}

/** 检查 / 下载 / 安装的编排（网络与校验在 UpdateChecker 里） */
object UpdateManager {

    private var job: Job? = null

    /** silent = 启动时的自动检查：只更新设置行小字，不主动弹窗打扰 */
    fun check(context: Context, scope: CoroutineScope, silent: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            UpdateStore.status(app.getString(R.string.update_checking))
            UpdateChecker.check(app, UpdateStore.channel)
                .onSuccess { release ->
                    UpdateStore.release(release, showDialog = !silent && release != null)
                    UpdateStore.status(
                        if (release == null) app.getString(R.string.update_latest)
                        else app.getString(R.string.update_found, release.versionName.ifBlank { release.tag })
                    )
                }
                .onFailure {
                    android.util.Log.w("UpdateManager", "check failed", it)
                    UpdateStore.status(app.getString(R.string.update_failed))
                }
        }
    }

    /** 点「安装更新」：下载 → 校验 → 交给系统安装器 */
    fun install(context: Context, scope: CoroutineScope, release: RemoteRelease) {
        if (job?.isActive == true || UpdateStore.downloading) return
        val app = context.applicationContext
        job = scope.launch {
            UpdateStore.download(true, 0)
            val file = UpdateChecker.download(app, release) { percent ->
                UpdateStore.download(true, percent)
                UpdateStore.status(app.getString(R.string.update_downloading, percent))
            }.getOrElse {
                UpdateStore.download(false)
                UpdateStore.status(app.getString(R.string.update_download_failed))
                return@launch
            }
            UpdateStore.download(false)
            // 校验失败也不能把弹窗卡在"下载中"
            val result = runCatching { UpdateChecker.verify(app, file, release) }
                .getOrDefault(VerifyResult.BROKEN)
            android.util.Log.i("UpdateManager", "verify=$result file=${file.name}")
            when (result) {
                VerifyResult.OK -> {
                    UpdateStore.dismissDialog()
                    if (UpdateChecker.install(app, file)) {
                        UpdateStore.status(app.getString(R.string.update_installing))
                    } else {
                        UpdateStore.status(app.getString(R.string.update_need_install_permission))
                    }
                }
                VerifyResult.SIGNATURE -> UpdateStore.status(app.getString(R.string.update_signature_mismatch))
                else -> UpdateStore.status(app.getString(R.string.update_verify_failed))
            }
        }
    }

}
