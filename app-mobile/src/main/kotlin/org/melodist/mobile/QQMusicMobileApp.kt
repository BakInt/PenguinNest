package org.melodist.mobile

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class QQMusicMobileApp :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // 顺序很关键：先取证（读上次启动残留的崩溃报告），再打本次启动标记。
        // 反过来的话，新标记的时间戳会晚于旧的 crash-last.txt，判定「上次启动崩溃」就会失效。
        pendingStartupCrash = org.melodist.data.CrashGuard.startupCrashReport(this)
        org.melodist.data.CrashGuard.markStartupBegin(this)
        // 崩溃落盘必须最先安装：之后任何一步初始化崩掉都能在 filesDir/crash-last.txt 留下堆栈。
        org.melodist.data.CrashGuard.install(this)
        org.melodist.api.ApiLogger.logger = { priority, tag, message, throwable ->
            if (throwable != null) {
                android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(throwable)}")
            } else {
                android.util.Log.println(priority, tag, message)
            }
        }
        safeInit("AppLifecycleManager.init") { org.melodist.data.AppLifecycleManager.init(this) }
        org.melodist.data.AppLifecycleManager.onTrimMemoryAction = {
            try {
                SingletonImageLoader.get(this@QQMusicMobileApp).memoryCache?.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Operation failed", e)
            }
        }
        safeInit("AppSettingsManager.init") { org.melodist.data.AppSettingsManager.init(this) }
        safeInit("ListeningStatsManager.init") { org.melodist.data.ListeningStatsManager.init(this) }
        safeInit("LyricCacheManager.init") { org.melodist.data.LyricCacheManager.init(this) }
        safeInit("AudioQualityAuditor.cleanOrphanProbeFiles") { org.melodist.playback.AudioQualityAuditor.cleanOrphanProbeFiles(this) }
        org.melodist.data.AppSettingsManager.imageCacheClearAction = {
            try {
                SingletonImageLoader.get(this@QQMusicMobileApp).diskCache?.clear()
                SingletonImageLoader.get(this@QQMusicMobileApp).memoryCache?.clear()
                true
            } catch (_: Exception) {
                false
            }
        }
        safeInit("ColorOsLyricBridge.init") { org.melodist.mobile.coloros.ColorOsLyricBridge.init() }
        try {
            if (packageName == "com.tencent.qqmusic") {
                val serviceIntent =
                    android.content.Intent().apply {
                        setComponent(android.content.ComponentName("com.tencent.qqmusic", "com.tencent.qqmusic.third.api.QQMusicApiService"))
                    }
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Operation failed", e)
        }
    }

    /**
     * 单步初始化兜底：某一步失败只降级（记入启动降级日志），不让整个应用在启动阶段崩溃。
     *
     * 协程取消必须原样抛出，否则会破坏结构化并发。
     */
    private inline fun safeInit(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Startup step failed: $step", e)
            org.melodist.data.CrashGuard.record(step, e)
        }
    }

    companion object {
        private const val TAG = "QQMusicMobileApp"

        /**
         * 上次启动是否死在首帧之前（崩溃文本）；由 [onCreate] 在打本次启动标记**之前**捕获。
         *
         * 放在 Application 里而不是 Activity 里：`Application.onCreate` 若崩（例如 R8 误删导致
         * `NoSuchMethodError`），`MainActivity.onCreate` 根本不会执行，报告就永远读不到。
         */
        @Volatile
        var pendingStartupCrash: String? = null
            private set
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain: Interceptor.Chain ->
                    val original = chain.request()
                    val request =
                        original
                            .newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; QQMusicMobile) AppleWebKit/537.36")
                            .header("Referer", "https://y.qq.com/")
                            .build()
                    chain.proceed(request)
                }.build()

        return ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }.build()
    }
}
