package com.yjc.click

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 下载下来的安装包是否能用 */
enum class VerifyResult { OK, PACKAGE, VERSION, SIGNATURE, BROKEN }

/** 用户在下载途中点了「取消」：从进度回调里抛出，让阻塞的读循环立刻停下 */
class DownloadCancelled : Exception("download cancelled")

/**
 * 更新源 = 本仓库的 GitHub Releases（tag 里编码了通道与版本号，见 UpdateChannel）。
 *
 * 国内直连 api.github.com 经常不通，所以每个请求都先直连、失败再走 gh-proxy 镜像。
 * 只用 HttpURLConnection + org.json，不引第三方网络库。
 */
object UpdateChecker {

    private const val REPO = "regeditexe1230/Click"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val MIRROR = "https://gh-proxy.com/"
    private const val PREFERRED_ASSET = "Click.apk"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 15_000
    private const val TAG = "UpdateChecker"

    private val certFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }

    /** 当前安装的版本号 */
    fun installedVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    /** 当前安装的版本名（弹窗里显示"当前 → 最新"用） */
    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** 查一次有没有新版本；返回 null = 已是最新 */
    suspend fun check(context: Context, channel: UpdateChannel): Result<RemoteRelease?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val releases = JSONArray(readText(API_URL))
                val installed = installedVersionCode(context)
                var best: RemoteRelease? = null
                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    val tag = release.optString("tag_name")
                    if (!tag.startsWith(channel.tagPrefix)) continue
                    val code = tag.removePrefix(channel.tagPrefix).toIntOrNull() ?: continue
                    if (code <= installed) continue
                    if (best != null && code <= best.versionCode) continue
                    val asset = pickAsset(release.optJSONArray("assets")) ?: continue
                    best = RemoteRelease(
                        versionCode = code,
                        tag = tag,
                        versionName = release.optString("name"),
                        notes = release.optString("body"),
                        downloadUrl = asset.first,
                        sizeBytes = asset.second,
                    )
                }
                best
            }
        }

    /** 下载安装包到 cacheDir/updates，返回文件；进度回调 0–100 */
    suspend fun download(
        context: Context,
        release: RemoteRelease,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "Click-${release.versionCode}.apk")
            val temp = File(dir, "Click-${release.versionCode}.apk.tmp")
            saveTo(release.downloadUrl, temp, release.sizeBytes, onProgress)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        }
    }

    /** 校验包名 / 版本号 / 签名：签名和当前安装的不一致是装不上的（debug 包换正式包就会这样），提前给出提示 */
    fun verify(context: Context, file: File, release: RemoteRelease): VerifyResult {
        val manager = context.packageManager
        val archive = archiveInfo(manager, file) ?: return VerifyResult.BROKEN
        if (archive.packageName != context.packageName) return VerifyResult.PACKAGE
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") archive.versionCode
        }
        if (code != release.versionCode) return VerifyResult.VERSION
        val remote = signerDigest(archive)
        val installed = runCatching {
            signerDigest(manager.getPackageInfo(context.packageName, certFlags))
        }.getOrNull()
        if (remote == null || installed == null) return VerifyResult.SIGNATURE
        return if (remote.contentEquals(installed)) VerifyResult.OK else VerifyResult.SIGNATURE
    }

    /** 拉起系统安装器；没有"安装未知应用"权限时先去设置页开 */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            requestInstallPermission(context)
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun archiveInfo(manager: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(certFlags.toLong())
            )
        } else {
            @Suppress("DEPRECATION") manager.getPackageArchiveInfo(file.absolutePath, certFlags)
        }

    /** 签名证书的摘要；两边都取摘要再比，省得比不同长度的数组 */
    private fun signerDigest(info: PackageInfo): ByteArray? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        if (signatures.isNullOrEmpty()) return null
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
    }

    private fun pickAsset(assets: JSONArray?): Pair<String, Long>? {
        if (assets == null) return null
        var fallback: Pair<String, Long>? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            val entry = url to asset.optLong("size")
            if (name.equals(PREFERRED_ASSET, ignoreCase = true)) return entry
            if (fallback == null) fallback = entry
        }
        return fallback
    }

    private fun readText(url: String): String {
        var last: Exception? = null
        for (candidate in listOf(url, MIRROR + url)) {
            try {
                return fetch(candidate)
            } catch (e: Exception) {
                Log.w(TAG, "read failed: $candidate (${e.javaClass.simpleName}: ${e.message})")
                last = e
            }
        }
        throw last ?: IllegalStateException("no url")
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Click-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 安装包走镜像优先：直连 github.com 下资产经常卡住，镜像反而稳 */
    private fun saveTo(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        var last: Exception? = null
        for (candidate in listOf(MIRROR + url, url)) {
            try {
                fetchTo(candidate, target, expectedSize, onProgress)
                return
            } catch (e: DownloadCancelled) {
                // 取消不算失败：删掉半截文件直接抛出去，别再去试下一个地址
                target.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $candidate (${e.javaClass.simpleName}: ${e.message})")
                last = e
                target.delete()
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun fetchTo(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        Log.d(TAG, "download ${url.substringBefore('?')}")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "Click-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            // 镜像走分块传输时没有 Content-Length，用 Release 里带的文件大小兜底
            val length = connection.contentLengthLong
            val total = if (length > 0) length else expectedSize
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
