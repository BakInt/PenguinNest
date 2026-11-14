package org.melodist.mobile

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.melodist.api.ApiLogger
import org.melodist.api.KeepAliveResult
import org.melodist.api.MusicApiService
import org.melodist.api.SessionKeeper
import org.melodist.api.SessionState
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.data.CrashGuard
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.FavoriteArtistsManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.SessionKeepAliveScheduler
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.UserSessionManager
import org.melodist.data.download.DownloadManager
import org.melodist.mobile.ui.StartupCrashScreen
import org.melodist.mobile.ui.navigation.MainNavigationScreen
import org.melodist.mobile.ui.theme.QQMusicMobileTheme
import org.melodist.mobile.util.LocalNetworkPermissionHelper
import org.melodist.playback.DeviceAudioCapability
import org.melodist.playback.PlaybackManager

class MainActivity : ComponentActivity() {
    private val requestPermissionsLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { _ ->
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge 必须在 super.onCreate 之后调用：窗口尚未 attach 时它要操作的
        // insets 控制器还未就绪，属于历史遗留的时序隐患。
        WindowCompat.enableEdgeToEdge(window)

        // 「启动看门狗」：报告由 QQMusicMobileApp.onCreate 在打本次启动标记之前捕获
        // （Application 阶段崩掉时 MainActivity 根本不会执行，所以取证必须放在 Application）。
        val startupCrashReport = QQMusicMobileApp.pendingStartupCrash
        org.melodist.data.CrashGuard.markStartupBegin(this)

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= 37) {
            if (!LocalNetworkPermissionHelper.hasPermission(this)) {
                permissionsToRequest.add(LocalNetworkPermissionHelper.PERMISSION_ACCESS_LOCAL_NETWORK)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }

        // 启动链路的每一步都单独兜底：任何一步抛异常只降级该功能，不让整个应用"打开就崩"。
        safeInit("DeviceAudioCapability.init") {
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    DeviceAudioCapability.init(applicationContext)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "DeviceAudioCapability.init failed", e)
                    CrashGuard.record("DeviceAudioCapability.init", e)
                }
            }
        }
        safeInit("UserSessionManager.init") { UserSessionManager.init(this) }
        safeInit("DailyRecommendCacheManager.init") {
            DailyRecommendCacheManager.init(this) { urls ->
                val appContext = applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    // 错峰延迟 1.5 秒，避开冷启动首屏布局、JIT 编译与主线程渲染高峰
                    kotlinx.coroutines.delay(1500)
                    val imageLoader = SingletonImageLoader.get(appContext)
                    val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                    for (url in urls) {
                        semaphore.withPermit {
                            try {
                                val request =
                                    ImageRequest
                                        .Builder(appContext)
                                        .data(url)
                                        .memoryCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                imageLoader.execute(request)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "Operation failed", e)
                            }
                        }
                    }
                }
            }
        }
        safeInit("AppSettingsManager.init") { org.melodist.data.AppSettingsManager.init(this) }
        safeInit("UserLibraryCacheManager.init") { UserLibraryCacheManager.init(this) }
        safeInit("PlaybackManager.init") { PlaybackManager.init(this) }
        safeInit("FavoriteArtistsManager.init") { FavoriteArtistsManager.init(this) }
        safeInit("LocalMusicManager.init") { LocalMusicManager.init(this) }
        safeInit("DownloadManager.init") { DownloadManager.init(this) }
        safeInit("GuessRecommendManager.init") { org.melodist.data.GuessRecommendManager.init(this) }
        safeInit("GuessRecommendManager.isPlayingPredicate") {
            org.melodist.data.GuessRecommendManager.isPlayingPredicate = { PlaybackManager.isRadioMode.value }
        }
        safeInit("RecommendFeedManager.init") { org.melodist.data.RecommendFeedManager.init(this) }
        safeInit("MillionRecommendManager.init") { org.melodist.data.MillionRecommendManager.init(this) }
        safeInit("RecentPlaybackManager.init") { org.melodist.data.RecentPlaybackManager.init(this) }
        safeInit("SearchKeywordHistoryManager.init") { org.melodist.data.SearchKeywordHistoryManager.init(this) }
        safeInit("MobileConnectManager.init") { org.melodist.mobile.connect.MobileConnectManager.init(this) }

        // 注册后台登录态保活：12 小时一次，保证长效票据不被"闲置超时"作废。
        safeInit("SessionKeepAliveScheduler.start") { SessionKeepAliveScheduler.start(this) }

        safeInit("startup-profile-refresh") {
            if (UserSession.isLoggedIn) {
                lifecycleScope.launch(Dispatchers.IO) {
                    // 启动时凭据可能已过期：先做一次强刷，再带指数退避重试同步档案。
                    // 旧实现只在首次失败后补刷一次就放弃，账号页会一直停留在"未开通"。
                    val backoffDelays = longArrayOf(0L, 3_000L, 8_000L, 20_000L)
                    for ((attempt, delayMs) in backoffDelays.withIndex()) {
                        if (delayMs > 0L) delay(delayMs)
                        try {
                            if (attempt == 0) {
                                // 冷启动先做一次强保活，并按结果分类：只有"服务端明确拒绝/无票据"
                                // 才停止后续重试；网络问题交给退避与后台任务，不在 20 秒内硬放弃。
                                when (val outcome = SessionKeeper.keepAlive(force = true)) {
                                    KeepAliveResult.Success -> Unit

                                    is KeepAliveResult.Transient ->
                                        ApiLogger.w("MainActivity", "Startup keep-alive transient failure: ${outcome.reason}")

                                    is KeepAliveResult.Rejected, KeepAliveResult.NoTicket -> {
                                        ApiLogger.w("MainActivity", "Startup keep-alive rejected; user must re-login")
                                        return@launch
                                    }
                                }
                            }
                            if (MusicApiService().refreshCurrentUserProfile(force = attempt > 0)) {
                                if (attempt > 0) {
                                    ApiLogger.i("MainActivity", "Profile refreshed on startup retry #$attempt")
                                }
                                return@launch
                            }
                        } catch (e: Throwable) {
                            // 捕 Throwable：Error（如 ExceptionInInitializerError）同样不能让进程崩掉。
                            if (e is CancellationException) throw e
                            ApiLogger.w("MainActivity", "Failed to refresh user profile on app start (attempt $attempt)", e)
                        }
                    }
                    ApiLogger.w("MainActivity", "Giving up profile refresh on app start; user may need to re-login")
                }
            }
        }

        try {
            setContent {
                var pendingCrash by remember { mutableStateOf(startupCrashReport) }
                val shownCrash = pendingCrash
                if (shownCrash != null) {
                    // 主界面组合之前先把上次的崩溃现场摆出来：release 包被 R8 剥掉了日志，
                    // 这是用户唯一能拿到、也不需要 adb 的崩溃证据（见 doc/19 §3.4）。
                    StartupCrashScreen(
                        text = shownCrash,
                        onContinue = {
                            org.melodist.data.CrashGuard.markStartupReady(this@MainActivity)
                            pendingCrash = null
                        },
                        onClearAndContinue = {
                            org.melodist.data.CrashGuard.clearLogs(this@MainActivity)
                            org.melodist.data.CrashGuard.markStartupReady(this@MainActivity)
                            pendingCrash = null
                        },
                    )
                } else {
                    val settings by org.melodist.data.AppSettingsManager.settings
                        .collectAsState()
                    QQMusicMobileTheme(
                        themeMode = settings.themeMode,
                        dynamicColor = settings.dynamicColor,
                        colorTheme = settings.colorTheme,
                        customColorHex = settings.customColorHex,
                        amoledDark = settings.amoledDark,
                    ) {
                        // 组合成功即视为启动完成，撤掉「启动进行中」标记。
                        LaunchedEffect(Unit) {
                            org.melodist.data.CrashGuard.markStartupReady(this@MainActivity)
                        }
                        MainNavigationScreen()
                    }
                }
            }
        } catch (t: Throwable) {
            // 首帧组合同步抛异常：换一个最小界面把堆栈显示出来，而不是白屏或直接闪退。
            org.melodist.data.CrashGuard.record("setContent", t)
            setContent {
                StartupCrashScreen(
                    text = org.melodist.data.CrashGuard.renderThrowable(t),
                    onContinue = { finish() },
                    onClearAndContinue = {
                        org.melodist.data.CrashGuard.clearLogs(this)
                        finish()
                    },
                )
            }
        }

        safeInit("handleIncomingIntent") { handleIncomingIntent(intent) }
    }

    override fun onStart() {
        super.onStart()
        // 回前台时若会话不健康（网络异常 / 需重登），立刻补一次保活，不必等 12 小时周期。
        safeInit("SessionKeeper.state") {
            if (UserSession.isLoggedIn && SessionKeeper.state != SessionState.Healthy) {
                SessionKeepAliveScheduler.kick(this)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        safeInit("handleIncomingIntent") { handleIncomingIntent(intent) }
    }

    /**
     * 启动/前台恢复链路的单步兜底：某一步抛异常只降级该功能并记入启动降级日志，
     * 不让异常冒到主线程导致进程崩溃。
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
            if (e is CancellationException) throw e
            Log.w(TAG, "Startup step failed: $step", e)
            CrashGuard.record(step, e)
        }
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (handleExternalAudioIntent(intent)) return
        handleListenTogetherIntent(intent)
    }

    private fun handleExternalAudioIntent(intent: android.content.Intent): Boolean {
        if (intent.action != android.content.Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val type = intent.type.orEmpty()
        val scheme = uri.scheme.orEmpty()

        if (uri.host == "i.y.qq.com" && uri.path?.contains("listen_together") == true) {
            return false
        }

        val isAudioMime =
            type.startsWith("audio/") || type == "application/ogg" || type == "application/x-ogg" || type == "application/flac" || type == "application/x-flac"
        val isAudioScheme = scheme == "content" || scheme == "file"
        val pathLower = uri.path.orEmpty().lowercase()
        val isAudioExt =
            pathLower.endsWith(
                ".mp3",
            ) || pathLower.endsWith(".flac") || pathLower.endsWith(".wav") || pathLower.endsWith(".m4a") || pathLower.endsWith(".aac") ||
                pathLower.endsWith(".ogg") ||
                pathLower.endsWith(".opus")

        if (!isAudioMime && !isAudioExt && !isAudioScheme) return false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    if (scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                } catch (_: SecurityException) {
                }

                var displayName: String? = null
                if (scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                    try {
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) displayName = cursor.getString(idx)
                            }
                        }
                    } catch (_: SecurityException) {
                    }
                }
                val finalDisplayName = displayName?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "外部音频"

                val (inferredTitle, inferredArtist) =
                    org.melodist.data.pipeline.AudioMetadataPipeline
                        .inferTitleArtist(finalDisplayName)
                var title = inferredTitle
                var artist = inferredArtist
                var album = "外部音频"
                var durationSec = 0
                var resolvedCoverUrl = ""
                var sampleRate: Int? = null
                var bitrate: Int? = null

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    val meta =
                        org.melodist.data.pipeline.AudioMetadataPipeline.parseFromRetriever(
                            retriever = retriever,
                            fallbackTitle = title,
                            fallbackArtist = artist,
                            fallbackAlbum = album,
                        )
                    title = meta.title
                    artist = meta.artist
                    album = meta.album
                    durationSec = meta.durationSeconds
                    sampleRate = meta.sampleRate
                    bitrate = meta.bitrate

                    val picBytes = meta.pictureBytes
                    if (picBytes != null && picBytes.size >= 512) {
                        val hash =
                            uri
                                .toString()
                                .hashCode()
                                .toString()
                                .replace("-", "n")
                        val coverFolder = java.io.File(cacheDir, "covers")
                        val savedCoverPath =
                            org.melodist.data.pipeline.AudioMetadataPipeline.saveThumbnailWebp(
                                bytes = picBytes,
                                folder = coverFolder,
                                baseHash = hash,
                                prefix = "external_",
                            )
                        if (!savedCoverPath.isNullOrBlank()) {
                            resolvedCoverUrl = "file://$savedCoverPath"
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to extract metadata for external URI: $uri", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }

                val ext = finalDisplayName.substringAfterLast('.', "flac")
                val inferredTier =
                    if (sampleRate != null && sampleRate > 0) {
                        org.melodist.model.AudioQualityTier.inferFromAudioFormat(
                            sampleRate = sampleRate,
                            mimeType = "audio/$ext",
                            bitrate = bitrate ?: 0,
                        )
                    } else {
                        org.melodist.model.AudioQualityTier.Standard
                    }

                val uriStr = uri.toString()
                val hash = kotlin.math.abs(uriStr.hashCode())
                val song =
                    org.melodist.model.Song(
                        songId = hash.toLong(),
                        songMid = "local_ext_$hash",
                        name = title,
                        singer = artist,
                        album = album,
                        durationSeconds = durationSec,
                        currentTier = inferredTier,
                        coverUrl = resolvedCoverUrl,
                        rawCoverUrl = resolvedCoverUrl,
                        localFilePath = uriStr,
                        mediaMid = uriStr,
                        dateAdded = System.currentTimeMillis() / 1000L,
                    )

                withContext(Dispatchers.Main) {
                    PlaybackManager.setPlaylist(
                        songs = listOf(song),
                        startIndex = 0,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("MainActivity", "Failed to handle external audio intent", e)
            }
        }
        return true
    }

    private fun handleListenTogetherIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.host == "i.y.qq.com" && uri.path?.contains("listen_together") == true) {
            val showId = uri.getQueryParameter("showid").orEmpty()
            val encUin = uri.getQueryParameter("share_encrypt_uin").orEmpty()
            val songId = uri.getQueryParameter("songid")?.toLongOrNull() ?: 0L
            if (showId.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.Main) {
                    android.widget.Toast
                        .makeText(this@MainActivity, "正在加入一起听房间...", android.widget.Toast.LENGTH_SHORT)
                        .show()
                    val result =
                        org.melodist.playback.ListenTogetherManager
                            .joinRoom(showId, encUin, songId)
                    result
                        .onSuccess {
                            android.widget.Toast
                                .makeText(this@MainActivity, "成功加入一起听", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }.onFailure {
                            android.widget.Toast
                                .makeText(this@MainActivity, "加入失败: ${it.message}", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 落盘走 SharedPreferences/JSON，磁盘满或状态异常都可能抛；这里不兜底会在退到后台时崩。
        safeInit("savePlaybackState") { PlaybackManager.savePlaybackState() }
        safeInit("savePlaybackProgress") { PlaybackManager.savePlaybackProgress() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        safeInit("AppLifecycleManager.onTrimMemory") {
            if (level >= TRIM_MEMORY_UI_HIDDEN) {
                org.melodist.data.AppLifecycleManager
                    .onTrimMemoryAction
                    ?.invoke(level)
            }
        }
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
