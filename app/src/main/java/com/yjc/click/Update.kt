package com.yjc.click

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    private const val KEY_IGNORED = "ignored_version"

    /** 启动时自动检查（旧版本这里是空按钮，默认开） */
    var autoCheck by mutableStateOf(true)
        private set

    // 默认走正式版：没手动选过通道的人都只收 v* 的正式包
    var channel by mutableStateOf(UpdateChannel.STABLE)
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

    /** 弹窗里的错误提示（字符串资源 id，0 = 没有错误）：断网 / 超时 / 服务器 / 下载失败 */
    var error by mutableStateOf(0)
        private set

    /** 勾过「此版本不再提醒」的 versionCode（0 = 没勾过）：自动检查到这个版本就不弹窗了 */
    var ignoredVersion by mutableStateOf(0)
        private set

    /** 启动时的自动检查只自动弹一次窗，这里记住本进程是否已经弹过 */
    private var autoPrompted = false

    var downloading by mutableStateOf(false)
        private set

    /** 下载进度 0–100 */
    var progress by mutableStateOf(0)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        autoCheck = prefs.getBoolean(KEY_AUTO, true)
        channel = UpdateChannel.entries.firstOrNull { it.prefKey == prefs.getString(KEY_CHANNEL, null) }
            ?: UpdateChannel.STABLE
        ignoredVersion = prefs.getInt(KEY_IGNORED, 0)
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

    /** 弹窗里勾/取消「此版本不再提醒」：只记一个 versionCode，0 = 不忽略任何版本 */
    fun setIgnoredVersion(context: Context, code: Int) {
        ignoredVersion = code
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_IGNORED, code).apply()
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

    internal fun error(res: Int) {
        error = res
    }

    /** 自动检查查到新版本时来问一次：返回 true = 这次该自动弹窗（同一进程只给一次） */
    internal fun claimAutoPrompt(): Boolean {
        if (autoPrompted) return false
        autoPrompted = true
        return true
    }
}

/** 检查 / 下载 / 安装的编排（网络与校验在 UpdateChecker 里） */
object UpdateManager {

    private var job: Job? = null

    /** 用户点了「取消」：下载循环每读一块看一眼，看到就抛 DownloadCancelled 停下 */
    @Volatile
    private var cancelRequested = false

    /** auto = 启动时的自动检查：查到新版本就自动弹窗（一次进程只自动弹一次） */
    fun check(context: Context, scope: CoroutineScope, auto: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            // 重新检查就把上一次的下载错误清掉，免得旧提示跟着新弹窗一起出现
            UpdateStore.error(0)
            UpdateStore.status(app.getString(R.string.update_checking))
            UpdateChecker.check(app, UpdateStore.channel)
                .onSuccess { release ->
                    // 自动检查只自动弹一次窗；手动点检查、切分支、开开关都是每次都弹
                    // 勾过「此版本不再提醒」的版本，自动检查不再弹（设置行的小字照常更新）
                    val ignored = release != null && release.versionCode == UpdateStore.ignoredVersion
                    val prompt = release != null && (!auto || (!ignored && UpdateStore.claimAutoPrompt()))
                    UpdateStore.release(release, showDialog = prompt)
                    UpdateStore.status(
                        if (release == null) app.getString(R.string.update_latest)
                        else app.getString(R.string.update_found, release.versionName.ifBlank { release.tag })
                    )
                }
                .onFailure {
                    android.util.Log.w("UpdateManager", "check failed", it)
                    UpdateStore.status(app.getString(errorRes(it) ?: R.string.update_failed))
                }
        }
    }

    /** 点「安装更新」：下载 → 校验 → 交给系统安装器 */
    fun install(context: Context, scope: CoroutineScope, release: RemoteRelease) {
        if (job?.isActive == true || UpdateStore.downloading) return
        val app = context.applicationContext
        cancelRequested = false
        job = scope.launch {
            UpdateStore.error(0)
            UpdateStore.download(true, 0)
            val file = UpdateChecker.download(app, release) { percent ->
                // 点了取消就立刻收手：不然阻塞中的读循环会把包偷偷下完
                if (cancelRequested) throw DownloadCancelled()
                UpdateStore.download(true, percent)
                UpdateStore.status(app.getString(R.string.update_downloading, percent))
            }.getOrElse { cause ->
                // 取消的界面状态由 cancelDownload 收好，这里不用管
                if (cause is DownloadCancelled) return@launch
                UpdateStore.download(false)
                val res = errorRes(cause) ?: R.string.update_download_failed
                UpdateStore.error(res)
                UpdateStore.status(app.getString(res))
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

    /** 下载途中点「取消」：停下载，清掉进度和错误，行状态回到"发现新版本" */
    fun cancelDownload(context: Context) {
        cancelRequested = true
        job?.cancel()
        job = null
        UpdateStore.download(false)
        UpdateStore.error(0)
        val app = context.applicationContext
        UpdateStore.available?.let { release ->
            UpdateStore.status(
                app.getString(R.string.update_found, release.versionName.ifBlank { release.tag })
            )
        }
    }

    /** 网络异常分类：断网 / 超时 / 服务器出错，认不出来就返回 null 交给调用方兜底 */
    @StringRes
    private fun errorRes(cause: Throwable): Int? = when (cause) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            R.string.update_error_network
        is SocketTimeoutException -> R.string.update_error_timeout
        is IllegalStateException -> if (cause.message?.startsWith("HTTP") == true) {
            R.string.update_error_server
        } else {
            null
        }
        else -> null
    }

}
