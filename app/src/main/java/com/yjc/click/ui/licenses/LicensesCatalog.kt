package com.yjc.click.ui.licenses

import android.content.Context

/**
 * 许可协议：整个应用只涉及这两份——本应用是 GPL-3.0，打进包的第三方组件全部是 Apache-2.0。
 * 全文放在 assets/licenses/ 下，选中某个组件时直接读出来显示，不用联网。
 */
enum class LicenseId(val label: String, val assetPath: String) {
    APACHE_2_0("Apache-2.0", "licenses/Apache-2.0.txt"),
    GPL_3_0("GPL-3.0", "licenses/GPL-3.0.txt"),
}

/** 一个开源组件：[name] 列表里显示的名字、[author] 版权方、[license] 适用协议。 */
data class LicenseLibrary(val name: String, val author: String, val license: LicenseId)

private const val AOSP = "The Android Open Source Project"
private const val GOOGLE = "Google LLC"
private const val JETBRAINS = "JetBrains"

/**
 * 真正打进安装包的第三方组件清单。
 *
 * 名单来自 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
 * （KMP 的 -android/-jvm/-common 变体按同一构件合并，-bom 去掉；测试依赖不进包所以不列），
 * 与 app/build.gradle.kts 里的依赖保持一致——依赖变了要同步这份名单。
 */
val thirdPartyLibraries: List<LicenseLibrary> = listOf(
    LicenseLibrary("AndroidX Activity", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Activity Compose", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Activity KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Annotation", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Annotation Experimental", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX AppCompat", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX AppCompat Resources", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Arch Core", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Arch Core Runtime", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Autofill", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX CardView", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Collection", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Collection KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Animation", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Animation Core", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Foundation", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Foundation Layout", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Material Icons Core", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Material Ripple", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Material 3", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Runtime", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose Runtime Saveable", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Geometry", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Graphics", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Text", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Tooling Preview", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Unit", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Compose UI Util", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Concurrent Futures", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ConstraintLayout", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ConstraintLayout Solver", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX CoordinatorLayout", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Core", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Core KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX CursorAdapter", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX CustomView", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX CustomView PoolingContainer", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX DocumentFile", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX DrawerLayout", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX DynamicAnimation", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Emoji2", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Emoji2 Views Helper", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Fragment", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Graphics Path", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Interpolator", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Legacy Support Core Utils", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Common", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Common Java 8", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle LiveData", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle LiveData Core", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle LiveData Core KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Process", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Runtime", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Runtime Compose", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle Runtime KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle ViewModel", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle ViewModel KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Lifecycle ViewModel SavedState", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Loader", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX LocalBroadcastManager", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Print", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ProfileInstaller", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX RecyclerView", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ResourceInspection Annotation", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX SavedState", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX SavedState KTX", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Startup Runtime", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Tracing", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX Transition", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX VectorDrawable", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX VectorDrawable Animated", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX VersionedParcelable", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ViewPager", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("AndroidX ViewPager2", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("Material Components for Android", AOSP, LicenseId.APACHE_2_0),
    LicenseLibrary("Error Prone Annotations", GOOGLE, LicenseId.APACHE_2_0),
    LicenseLibrary("Guava ListenableFuture", GOOGLE, LicenseId.APACHE_2_0),
    LicenseLibrary("JetBrains Annotations", JETBRAINS, LicenseId.APACHE_2_0),
    LicenseLibrary("Kotlin Standard Library", JETBRAINS, LicenseId.APACHE_2_0),
    LicenseLibrary("Kotlin Coroutines", JETBRAINS, LicenseId.APACHE_2_0),
    LicenseLibrary("Kotlin Coroutines Core", JETBRAINS, LicenseId.APACHE_2_0),
)

/** 许可全文：读一次缓存起来（GPL 全文 35 KB，反复读没必要），读不到就显示空。 */
object LicenseTexts {
    private val cache = HashMap<LicenseId, String>()

    fun of(context: Context, id: LicenseId): String = cache.getOrPut(id) {
        runCatching {
            context.assets.open(id.assetPath).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
}
