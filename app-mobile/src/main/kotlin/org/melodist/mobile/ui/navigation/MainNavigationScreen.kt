package org.melodist.mobile.ui.navigation

import android.util.Log
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.data.AppSettingsManager
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateResult
import org.melodist.mobile.BuildConfig
import org.melodist.mobile.ui.acr.AcrPillBar
import org.melodist.mobile.ui.acr.AcrRecognitionController
import org.melodist.mobile.ui.acr.AcrResultCard
import org.melodist.mobile.ui.acr.MobileAcrUiState
import org.melodist.mobile.ui.acr.MobileAcrViewModel
import org.melodist.mobile.ui.album.AlbumDetailScreen
import org.melodist.mobile.ui.album.FavoriteAlbumsScreen
import org.melodist.mobile.ui.artist.ArtistDetailScreen
import org.melodist.mobile.ui.auth.MobileLoginDialog
import org.melodist.mobile.ui.home.HomeBody
import org.melodist.mobile.ui.home.HomeFeaturePageScreen
import org.melodist.mobile.ui.library.LibraryScreen
import org.melodist.mobile.ui.navigation.AppNavigationController
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.mobile.ui.navigation.ScreenDestination
import org.melodist.mobile.ui.player.PlayerContainer
import org.melodist.mobile.ui.player.PlayerQueueBottomSheet
import org.melodist.mobile.ui.playlist.PlaylistDetailScreen
import org.melodist.mobile.ui.recent.RecentAlbumsScreen
import org.melodist.mobile.ui.recent.RecentPlaylistsScreen
import org.melodist.mobile.ui.search.SearchScreen
import org.melodist.mobile.ui.search.SearchScreenState
import org.melodist.mobile.ui.settings.MobileSettingsScreen
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class MobileAcrSuccessData(
    val song: Song,
    val offsetSeconds: Double,
    val anchorRealtimeMs: Long,
    val durationText: String,
)

private const val MAX_BACK_DEPTH = 3

@Composable
fun MainNavigationScreen(modifier: Modifier = Modifier) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val screenStack = remember { mutableStateListOf<ScreenDestination>() }
    var isSearching by remember { mutableStateOf(false) }

    fun pushDestination(
        destination: ScreenDestination,
        clearStack: Boolean,
    ) {
        val baseDepth = if (isSearching) 1 else 0
        if (clearStack) {
            isSearching = false
            screenStack.forEachIndexed { index, dest ->
                saveableStateHolder.removeState("screen_detail_${baseDepth + index + 1}_$dest")
            }
            screenStack.clear()
            screenStack.add(destination)
        } else {
            while (screenStack.size >= MAX_BACK_DEPTH) {
                val removed = screenStack.removeAt(0)
                saveableStateHolder.removeState("screen_detail_${baseDepth + 1}_$removed")
            }
            screenStack.add(destination)
        }
    }

    val navController =
        remember {
            object : AppNavigationController {
                override fun navigateToPlaylist(
                    playlist: Playlist,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.PlaylistDetail(playlist), clearStack)
                }

                override fun navigateToArtist(
                    artistMid: String,
                    artistName: String,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.ArtistDetail(artistMid, artistName), clearStack)
                }

                override fun navigateToAlbum(
                    albumMid: String,
                    albumName: String,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.AlbumDetail(albumMid, albumName), clearStack)
                }

                override fun navigateToFavoriteAlbums(clearStack: Boolean) {
                    pushDestination(ScreenDestination.FavoriteAlbums, clearStack)
                }

                override fun navigateToRecentAlbums(clearStack: Boolean) {
                    pushDestination(ScreenDestination.RecentAlbums, clearStack)
                }

                override fun navigateToRecentPlaylists(clearStack: Boolean) {
                    pushDestination(ScreenDestination.RecentPlaylists, clearStack)
                }

                override fun navigateToHomeFeature(
                    filter: HomeFilter,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.HomeFeaturePage(filter), clearStack)
                }

                override fun navigateBack(): Boolean =
                    if (screenStack.isNotEmpty()) {
                        val poppedIndex = screenStack.size - 1
                        val popped = screenStack.removeAt(poppedIndex)
                        val baseDepth = if (isSearching) 1 else 0
                        saveableStateHolder.removeState("screen_detail_${baseDepth + poppedIndex + 1}_$popped")
                        true
                    } else if (isSearching) {
                        isSearching = false
                        true
                    } else {
                        false
                    }

                override val currentDestination: ScreenDestination?
                    get() = screenStack.lastOrNull()
            }
        }

    val drawerState = rememberMusicFreeDrawerState()
    val drawerScope = rememberCoroutineScope()
    // 底部 Tab 选中状态：冷启动按设置的“启动页”初始化；提升到顶层，推入详情页后返回仍保持
    val appSettings by AppSettingsManager.settings.collectAsState()
    var homeTab by rememberSaveable { mutableStateOf(appSettings.startupTab.toHomeTab()) }

    var showLoginDialog by remember { mutableStateOf(false) }
    var isFullPlayerExpanded by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var newVersionDialogResult by remember { mutableStateOf<UpdateResult.NewVersion?>(null) }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        // 延迟执行初始化任务
        delay(2500)
        withContext(Dispatchers.IO) {
            val targetKw = if (context.packageName == "com.tencent.qqmusic") "originos" else "mobile"
            val result =
                UpdateChecker.checkUpdateDaily(
                    context = context,
                    currentVersion = BuildConfig.QQMUSIC_VERSION_NAME,
                    targetKeyword = targetKw,
                    force = false,
                )
            if (result is UpdateResult.NewVersion) {
                withContext(Dispatchers.Main) {
                    newVersionDialogResult = result
                }
            }
        }
    }

    // ACR 识曲 ViewModel：交给 ViewModelStore 托管，随窗口销毁走 onCleared() 停止录音，
    // 避免 remember { } 持有导致录音器在 Activity 重建后残留
    val acrViewModel: MobileAcrViewModel = viewModel { MobileAcrViewModel() }
    val acrUiState by acrViewModel.uiState.collectAsState()
    // 搜索状态持有者（跨详情页导航保留搜索关键词与滚动位置）
    val searchScreenState = remember { SearchScreenState() }
    // 关闭搜索的统一收口：isSearching 变 false 时（系统返回、抽屉跳转等任意退出路径）
    // 清空搜索栏输入与结果状态；仅清输入内容与结果，搜索历史（SearchKeywordHistoryManager）保留
    LaunchedEffect(isSearching) {
        if (!isSearching) {
            searchScreenState.reset()
        }
    }
    // 识曲结果缓存
    var acrSuccessData by remember { mutableStateOf<MobileAcrSuccessData?>(null) }
    // 识别成功卡片展示状态
    var showAcrResultCard by remember { mutableStateOf(false) }
    var isAcrCardInteracting by remember { mutableStateOf(false) }
    // 是否正在挂载无 UI 识别控制器
    var isAcrControllerActive by remember { mutableStateOf(false) }

    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn

    // 登录态变化时由登录流程自身完成档案同步（见 MobileLoginDialog.syncSessionAfterLogin），
    // 此处不重复触发，避免两个协程并发刷新相互覆盖会员信息。

    // 识别结果处理
    LaunchedEffect(acrUiState) {
        val state = acrUiState
        when (state) {
            is MobileAcrUiState.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isAcrControllerActive = false
                val durationSec = state.matchDurationSeconds
                val durationText =
                    if (durationSec <= 0.1f) {
                        "3秒识别成功"
                    } else {
                        val secText = "%.1f".format(Locale.CHINA, durationSec).removeSuffix(".0")
                        "${secText}秒识别成功"
                    }
                acrSuccessData =
                    MobileAcrSuccessData(
                        song = state.song,
                        offsetSeconds = state.offsetSeconds,
                        anchorRealtimeMs = state.anchorRealtimeMs,
                        durationText = durationText,
                    )
            }

            is MobileAcrUiState.Failed -> {
                isAcrControllerActive = false
                showAcrResultCard = false
                isAcrCardInteracting = false
                // 失败原因必须让用户看见，否则界面上表现为「点了没反应」
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                delay(2000L)
                acrViewModel.reset()
            }

            is MobileAcrUiState.PermissionRequired -> {
                isAcrControllerActive = false
                showAcrResultCard = false
                isAcrCardInteracting = false
                Toast.makeText(context, "需要麦克风权限才能听歌识曲", Toast.LENGTH_SHORT).show()
                acrViewModel.reset()
            }

            else -> {}
        }
    }

    // 结果卡片定时收起
    LaunchedEffect(acrSuccessData, isAcrCardInteracting) {
        if (acrSuccessData != null) {
            showAcrResultCard = true
            if (!isAcrCardInteracting) {
                delay(8000L)
                showAcrResultCard = false
            }
        } else {
            showAcrResultCard = false
        }
    }

    val pageBackAnimatable = remember { Animatable(0f) }
    var isPageBackActive by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var settingsBackProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = showSettingsScreen) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                settingsBackProgress = backEvent.progress
                backSwipeEdge = backEvent.swipeEdge
            }
            showSettingsScreen = false
        } catch (_: CancellationException) {
            settingsBackProgress = 0f
        } finally {
            settingsBackProgress = 0f
        }
    }

    BackHandler(enabled = !showSettingsScreen && acrSuccessData != null) {
        acrSuccessData = null
        showAcrResultCard = false
        acrViewModel.reset()
    }

    // 底部 Tab：非首页 Tab 按返回先回到首页 Tab
    BackHandler(
        enabled =
            !showSettingsScreen &&
                acrSuccessData == null &&
                screenStack.isEmpty() &&
                !isSearching &&
                homeTab != HomeTab.Home,
    ) {
        homeTab = HomeTab.Home
    }

    PredictiveBackHandler(
        enabled = !showSettingsScreen && acrSuccessData == null && (screenStack.isNotEmpty() || isSearching),
    ) { progressFlow ->
        try {
            isPageBackActive = true
            progressFlow.collect { backEvent ->
                pageBackAnimatable.snapTo(backEvent.progress)
                backSwipeEdge = backEvent.swipeEdge
            }
            navController.navigateBack()
        } catch (_: CancellationException) {
            pageBackAnimatable.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
        } finally {
            pageBackAnimatable.snapTo(0f)
            isPageBackActive = false
        }
    }

    // 抽屉打开时返回优先关闭抽屉（doc/10 §4.5；后注册者优先于上方全部 handler）。
    // 但只在抽屉确实是顶层时才接管：抽屉点条目后不再自动关闭，若不加门控会把设置页/识曲/推入页面的
    // 返回一并抢走，用户就无法从覆盖层「返回抽屉」（doc/16 §3）。
    BackHandler(
        enabled =
            drawerState.isOpen &&
                screenStack.isEmpty() &&
                !isSearching &&
                !showSettingsScreen &&
                acrSuccessData == null &&
                !isAcrControllerActive,
    ) {
        drawerScope.launch { drawerState.close() }
    }

    val activeScreen: AppScreen =
        when {
            screenStack.isNotEmpty() -> {
                val baseDepth = if (isSearching) 1 else 0
                AppScreen.Detail(
                    destination = screenStack.last(),
                    depth = baseDepth + screenStack.size,
                )
            }

            isSearching -> {
                AppScreen.Search
            }

            else -> {
                AppScreen.Home
            }
        }

    val previousScreen: AppScreen? =
        when {
            screenStack.size > 1 -> {
                val baseDepth = if (isSearching) 1 else 0
                AppScreen.Detail(
                    destination = screenStack[screenStack.size - 2],
                    depth = baseDepth + screenStack.size - 1,
                )
            }

            screenStack.size == 1 -> {
                if (isSearching) AppScreen.Search else AppScreen.Home
            }

            isSearching -> {
                AppScreen.Home
            }

            else -> {
                null
            }
        }

    // 抽屉打开时「进出首页」的转场标记：此时 Home 层（含抽屉）整层保持静止、由另一层独占运动，
    // 视觉上即「新页面滑入盖在仍打开的抽屉前面」，返回时原样退回抽屉（doc/16 §2.2）。
    // 用 isOpen（settled）而非 targetValue：拖动过程中不会变化，避免破坏抽屉跟手零重组（doc/10 §4.5）。
    val drawerHoldsHome =
        drawerState.isOpen && (activeScreen is AppScreen.Home || previousScreen is AppScreen.Home)

    CompositionLocalProvider(LocalAppNavigation provides navController) {
        Box(modifier = modifier.fillMaxSize()) {
            // 底部 Tab 栏仅在首页级界面显示，推入搜索/详情后隐藏
            val tabBar: (@Composable () -> Unit)? =
                if (screenStack.isEmpty() && !isSearching) {
                    {
                        MusicTabBar(
                            selected = homeTab,
                            onSelect = { homeTab = it },
                            showLabels = appSettings.showTabBarLabels,
                        )
                    }
                } else {
                    null
                }

            PlayerContainer(
                // 设置页为覆盖层，转场只由设置页自身承担；根内容保持静止，避免两段位移叠加
                modifier = Modifier.fillMaxSize(),
                isFullPlayerExpanded = isFullPlayerExpanded,
                onFullPlayerExpandChange = { isFullPlayerExpanded = it },
                bottomBar = tabBar,
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    // 预测返回手势底层预览
                    if (isPageBackActive && previousScreen != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val progress = pageBackAnimatable.value
                                        val scale = 0.92f + (progress * 0.08f)
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 0.6f + (progress * 0.4f)
                                        val sign = if (backSwipeEdge == BackEventCompat.EDGE_RIGHT) 1f else -1f
                                        translationX = sign * size.width * (1f - progress) * 0.08f
                                    },
                        ) {
                            RenderAppScreen(
                                screen = previousScreen,
                                innerPadding = innerPadding,
                                navController = navController,
                                onSearchClick = { isSearching = true },
                                onBackFromSearch = {
                                    isSearching = false
                                    searchScreenState.reset()
                                },
                                searchState = searchScreenState,
                                onOpenSettings = { showSettingsScreen = true },
                                onOpenLogin = { showLoginDialog = true },
                                onOpenPlayer = { isFullPlayerExpanded = true },
                                acrViewModel = acrViewModel,
                                acrUiState = acrUiState,
                                acrSuccessData = acrSuccessData,
                                // 抽屉条目与顶栏按钮共用：先收起抽屉、回到主页面，再启动识曲，
                                // 使「正在识别」胶囊与结果卡回到用户可见的层（doc/16 §2.2 2026-11-14 修订）
                                onActivateAcr = {
                                    drawerScope.launch { drawerState.close() }
                                    isAcrControllerActive = true
                                },
                                onCancelAcr = {
                                    isAcrControllerActive = false
                                    acrViewModel.reset()
                                },
                                onCloseAcr = {
                                    acrSuccessData = null
                                    showAcrResultCard = false
                                    isAcrCardInteracting = false
                                    acrViewModel.reset()
                                },
                                onExpandAcr = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                    isFullPlayerExpanded = true
                                },
                                onPlayAcrCard = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                },
                                onCollapseAcrCard = { showAcrResultCard = false },
                                onAcrCardInteractionChange = { isAcrCardInteracting = it },
                                showAcrResultCard = showAcrResultCard,
                                homeTab = homeTab,
                                drawerState = drawerState,
                                drawerScope = drawerScope,
                                drawerGesturesEnabled = !showSettingsScreen && !isAcrControllerActive,
                            )
                        }
                    }

                    // 顶层页面跟随手势缩放与位移
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val progress = pageBackAnimatable.value
                                    if (isPageBackActive && progress > 0f) {
                                        val scale = 1f - (progress * 0.08f)
                                        scaleX = scale
                                        scaleY = scale
                                        val sign = if (backSwipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                                        translationX = sign * size.width * (progress * 0.35f)
                                        shape = RoundedCornerShape((progress * 24).dp)
                                        clip = true
                                    }
                                },
                    ) {
                        AnimatedContent(
                            targetState = activeScreen,
                            transitionSpec = {
                                val isForward = targetState.depth >= initialState.depth
                                if (isForward) {
                                    (
                                        slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                        ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                    ) togetherWith
                                        (
                                            if (drawerHoldsHome) {
                                                // 抽屉打开时 Home 层冻结在下方：只留 1px 位移不可见地占满
                                                // 300ms 转场时长（目标值与当前值相等的出场动画会被视为已结束、
                                                // 内容被立即移除），抽屉因此全程停在原位被新页面「盖住」
                                                slideOutHorizontally(
                                                    targetOffsetX = { 1 },
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                )
                                            } else {
                                                slideOutHorizontally(
                                                    targetOffsetX = { fullWidth -> -fullWidth / 3 },
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                                            }
                                        )
                                } else {
                                    (
                                        if (drawerHoldsHome) {
                                            // 返回：抽屉层原样淡入原位（不参与任何位移/淡化），
                                            // 由上层页面右移退出后把它露出来
                                            slideInHorizontally(
                                                initialOffsetX = { 1 },
                                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            )
                                        } else {
                                            slideInHorizontally(
                                                initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                        }
                                    ) togetherWith
                                        (
                                            slideOutHorizontally(
                                                targetOffsetX = { fullWidth -> fullWidth },
                                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                                        )
                                }
                            },
                            label = "main_screen_transition",
                            modifier = Modifier.fillMaxSize(),
                        ) { targetScreen ->
                            saveableStateHolder.SaveableStateProvider(key = targetScreen.saveableKey) {
                                RenderAppScreen(
                                    screen = targetScreen,
                                    // 抽屉打开时 Home 层冻结在下层，其余页面绘制在它上方，
                                    // 保证是「新页面盖住抽屉」而不是被静止的抽屉压住（doc/16 §2.2）
                                    modifier =
                                        Modifier.zIndex(
                                            if (drawerHoldsHome && targetScreen !is AppScreen.Home) 1f else 0f,
                                        ),
                                    innerPadding = innerPadding,
                                    navController = navController,
                                    onSearchClick = { isSearching = true },
                                    onBackFromSearch = {
                                        isSearching = false
                                        searchScreenState.reset()
                                    },
                                    searchState = searchScreenState,
                                    onOpenSettings = { showSettingsScreen = true },
                                    onOpenLogin = { showLoginDialog = true },
                                    onOpenPlayer = { isFullPlayerExpanded = true },
                                    acrViewModel = acrViewModel,
                                    acrUiState = acrUiState,
                                    acrSuccessData = acrSuccessData,
                                    // 抽屉条目与顶栏按钮共用：先收起抽屉、回到主页面，再启动识曲，
                                    // 使「正在识别」胶囊与结果卡回到用户可见的层（doc/16 §2.2 2026-11-14 修订）
                                    onActivateAcr = {
                                        drawerScope.launch { drawerState.close() }
                                        isAcrControllerActive = true
                                    },
                                    onCancelAcr = {
                                        isAcrControllerActive = false
                                        acrViewModel.reset()
                                    },
                                    onCloseAcr = {
                                        acrSuccessData = null
                                        showAcrResultCard = false
                                        isAcrCardInteracting = false
                                        acrViewModel.reset()
                                    },
                                    onExpandAcr = {
                                        val data = acrSuccessData
                                        if (data != null) {
                                            val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                            val prepLatencyMs = 850L
                                            val seekMs =
                                                ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                    .coerceAtLeast(0L)
                                            PlaybackManager.insertAndPlay(
                                                song = data.song,
                                                seekToMs = seekMs,
                                            )
                                            showAcrResultCard = false
                                        }
                                        isFullPlayerExpanded = true
                                    },
                                    onPlayAcrCard = {
                                        val data = acrSuccessData
                                        if (data != null) {
                                            val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                            val prepLatencyMs = 850L
                                            val seekMs =
                                                ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                    .coerceAtLeast(0L)
                                            PlaybackManager.insertAndPlay(
                                                song = data.song,
                                                seekToMs = seekMs,
                                            )
                                            showAcrResultCard = false
                                        }
                                    },
                                    onCollapseAcrCard = { showAcrResultCard = false },
                                    onAcrCardInteractionChange = { isAcrCardInteracting = it },
                                    showAcrResultCard = showAcrResultCard,
                                    homeTab = homeTab,
                                    drawerState = drawerState,
                                    drawerScope = drawerScope,
                                    drawerGesturesEnabled = !showSettingsScreen && !isAcrControllerActive,
                                )
                            }
                        }
                    }
                }
            }

            if (showLoginDialog) {
                MobileLoginDialog(
                    onDismissRequest = { showLoginDialog = false },
                    onLoginSuccess = { showLoginDialog = false },
                )
            }

            newVersionDialogResult?.let { newVersion ->
                org.melodist.mobile.ui.components.NewVersionDialog(
                    newVersion = newVersion,
                    onDismissRequest = { newVersionDialogResult = null },
                )
            }

            val requestOpenQueue by PlaybackManager.showQueueSheetFlow.collectAsState()
            if (requestOpenQueue && !isFullPlayerExpanded) {
                PlayerQueueBottomSheet(
                    onDismissRequest = { PlaybackManager.consumeOpenQueueSheet() },
                )
            }

            // 识曲控制器
            if (isAcrControllerActive) {
                AcrRecognitionController(
                    viewModel = acrViewModel,
                    onDismiss = { isAcrControllerActive = false },
                )
            }

            AnimatedVisibility(
                visible = showSettingsScreen,
                // 与主导航（AnimatedContent 的前进动画）保持一致：从右边缘横向推入，而非纵向弹出
                enter =
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                exit =
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (settingsBackProgress > 0f) {
                                val scale = 1f - (settingsBackProgress * 0.08f)
                                scaleX = scale
                                scaleY = scale
                                val sign = if (backSwipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                                translationX = sign * size.width * (settingsBackProgress * 0.35f)
                                shape = RoundedCornerShape((settingsBackProgress * 28).dp)
                                clip = true
                                alpha = 1f - (settingsBackProgress * 0.2f)
                            }
                        },
            ) {
                MobileSettingsScreen(
                    onBack = { showSettingsScreen = false },
                    onOpenLogin = { showLoginDialog = true },
                )
            }

            // 识曲进行中按返回 = 取消识曲。此前该状态没有任何 handler 生效
            //（抽屉 handler 被 !isAcrControllerActive 门控掉，页面 handler 也不满足条件），
            // 返回会落到 Activity 默认行为直接退出应用（doc/16 §2.4 2026-11-14 修订）。
            // 声明在最后：后注册者优先，确保盖过抽屉与页面返回。
            BackHandler(enabled = isAcrControllerActive && !showSettingsScreen) {
                isAcrControllerActive = false
                acrViewModel.reset()
            }
        }
    }
}

private sealed interface AppScreen {
    val depth: Int
    val saveableKey: String

    data object Home : AppScreen {
        override val depth: Int = 0
        override val saveableKey: String = "screen_home"
    }

    data object Search : AppScreen {
        override val depth: Int = 1
        override val saveableKey: String = "screen_search"
    }

    data class Detail(
        val destination: ScreenDestination,
        override val depth: Int,
    ) : AppScreen {
        override val saveableKey: String
            get() = "screen_detail_${depth}_$destination"
    }
}

@Composable
private fun RenderAppScreen(
    screen: AppScreen,
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    navController: AppNavigationController,
    onSearchClick: () -> Unit,
    onBackFromSearch: () -> Unit,
    searchState: SearchScreenState,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenPlayer: () -> Unit,
    acrViewModel: MobileAcrViewModel,
    acrUiState: MobileAcrUiState,
    acrSuccessData: MobileAcrSuccessData?,
    onActivateAcr: () -> Unit,
    onCancelAcr: () -> Unit,
    onCloseAcr: () -> Unit,
    onExpandAcr: () -> Unit,
    onPlayAcrCard: () -> Unit,
    onCollapseAcrCard: () -> Unit,
    onAcrCardInteractionChange: (Boolean) -> Unit,
    showAcrResultCard: Boolean,
    homeTab: HomeTab,
    drawerState: MusicFreeDrawerState,
    drawerScope: kotlinx.coroutines.CoroutineScope,
    drawerGesturesEnabled: Boolean,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (screen) {
            is AppScreen.Home -> {
                MusicFreeModalDrawer(
                    state = drawerState,
                    // 设置页/识曲为根级覆盖层，抽屉留在打开态被它们盖住；期间禁用抽屉手势，
                    // 避免上层界面上的横拖/空白点击穿透过去拖动或关闭抽屉（doc/16 §2.3）
                    gesturesEnabled = drawerGesturesEnabled,
                    drawerContent = {
                        MusicFreeDrawerContent(
                            onOpenSettings = onOpenSettings,
                            onNavigateFeature = { filter ->
                                navController.navigateToHomeFeature(filter)
                            },
                            onActivateAcr = onActivateAcr,
                        )
                    },
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding(),
                    ) {
                        // MusicFree 顶栏：汉堡菜单 + 假搜索栏 + 识曲
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    drawerScope.launch { drawerState.open() }
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = "打开菜单",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            val isAcrActive =
                                acrUiState is MobileAcrUiState.Listening ||
                                    acrSuccessData != null

                            AnimatedContent(
                                targetState = isAcrActive,
                                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                                label = "search_bar_morph",
                                modifier = if (isAcrActive) Modifier else Modifier.weight(1f),
                            ) { acrActive ->
                                if (acrActive) {
                                    Surface(
                                        onClick = onSearchClick,
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = "搜索",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .clickable { onSearchClick() },
                                        shape = RoundedCornerShape(22.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border =
                                            androidx.compose.foundation.BorderStroke(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            ),
                                    ) {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = "搜索",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "点击搜索",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedContent(
                                targetState = isAcrActive,
                                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                                label = "acr_button_morph",
                                modifier = if (isAcrActive) Modifier.weight(1f) else Modifier,
                            ) { acrActive ->
                                if (acrActive) {
                                    val pillUiState =
                                        if (acrSuccessData != null) {
                                            MobileAcrUiState.Success(
                                                song = acrSuccessData.song,
                                                offsetSeconds = acrSuccessData.offsetSeconds,
                                                anchorRealtimeMs = acrSuccessData.anchorRealtimeMs,
                                            )
                                        } else {
                                            acrUiState
                                        }
                                    AcrPillBar(
                                        uiState = pillUiState,
                                        onCancel = onCancelAcr,
                                        onExpand = onExpandAcr,
                                        onClose = onCloseAcr,
                                        showBriefSuccess = showAcrResultCard,
                                        briefSuccessText = acrSuccessData?.durationText ?: "识别成功",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    FilledTonalIconButton(
                                        onClick = onActivateAcr,
                                        modifier = Modifier.size(44.dp),
                                        colors =
                                            IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                contentColor = MaterialTheme.colorScheme.primary,
                                            ),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = "听歌识曲",
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // 识曲成功结果卡片
                        AnimatedVisibility(
                            visible = showAcrResultCard && acrSuccessData != null,
                            enter =
                                slideInVertically(
                                    initialOffsetY = { -it },
                                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                                ) +
                                    scaleIn(
                                        initialScale = 0.88f,
                                        transformOrigin = TransformOrigin(0.5f, 0f),
                                        animationSpec = tween(380, easing = FastOutSlowInEasing),
                                    ) + fadeIn(tween(280)),
                            exit =
                                slideOutVertically(
                                    targetOffsetY = { -it },
                                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                                ) +
                                    scaleOut(
                                        targetScale = 0.88f,
                                        transformOrigin = TransformOrigin(0.5f, 0f),
                                        animationSpec = tween(380, easing = FastOutSlowInEasing),
                                    ) + fadeOut(tween(240)),
                        ) {
                            val song = acrSuccessData?.song
                            if (song != null) {
                                val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
                                val isPlaying by PlaybackManager.isPlaying.collectAsState()
                                val isThisSongPlaying = currentPlayingSong?.songMid == song.songMid && isPlaying

                                AcrResultCard(
                                    song = song,
                                    isPlaying = isThisSongPlaying,
                                    onPlayClick = onPlayAcrCard,
                                    onCollapse = onCollapseAcrCard,
                                    onInteractionStateChange = onAcrCardInteractionChange,
                                    onPrepareSong = {
                                        val data = acrSuccessData
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                    },
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }

                        // 底部 Tab 内容：首页 / 我的收藏
                        when (homeTab) {
                            HomeTab.Home -> {
                                HomeBody(
                                    contentPadding = innerPadding,
                                    onNavigateFeature = { filter ->
                                        navController.navigateToHomeFeature(filter)
                                    },
                                    onOpenPlaylist = { playlist ->
                                        navController.navigateToPlaylist(playlist)
                                    },
                                    onRequireLogin = onOpenLogin,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                )
                            }

                            HomeTab.Library -> {
                                LibraryScreen(
                                    contentPadding = innerPadding,
                                    onRequireLogin = onOpenLogin,
                                    onOpenPlaylist = { playlist ->
                                        navController.navigateToPlaylist(playlist)
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            is AppScreen.Search -> {
                SearchScreen(
                    contentPadding = innerPadding,
                    onBack = onBackFromSearch,
                    searchState = searchState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                )
            }

            is AppScreen.Detail -> {
                when (val currentScreen = screen.destination) {
                    is ScreenDestination.PlaylistDetail -> {
                        PlaylistDetailScreen(
                            playlist = currentScreen.playlist,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }

                    is ScreenDestination.ArtistDetail -> {
                        ArtistDetailScreen(
                            artistMid = currentScreen.artistMid,
                            artistName = currentScreen.artistName,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }

                    is ScreenDestination.AlbumDetail -> {
                        AlbumDetailScreen(
                            albumMid = currentScreen.albumMid,
                            albumName = currentScreen.albumName,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }

                    is ScreenDestination.FavoriteAlbums -> {
                        FavoriteAlbumsScreen(
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                            onAlbumClick = { album ->
                                navController.navigateToAlbum(album.mid, album.name)
                            },
                        )
                    }

                    is ScreenDestination.RecentAlbums -> {
                        RecentAlbumsScreen(
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                            onAlbumClick = { albumMid, albumName ->
                                navController.navigateToAlbum(albumMid, albumName)
                            },
                        )
                    }

                    is ScreenDestination.RecentPlaylists -> {
                        RecentPlaylistsScreen(
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                            onPlaylistClick = { playlist ->
                                navController.navigateToPlaylist(playlist)
                            },
                        )
                    }

                    is ScreenDestination.HomeFeaturePage -> {
                        HomeFeaturePageScreen(
                            filter = currentScreen.filter,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                            onRequireLogin = onOpenLogin,
                            onOpenPlayer = onOpenPlayer,
                            onOpenPlaylist = { playlist ->
                                navController.navigateToPlaylist(playlist)
                            },
                        )
                    }
                }
            }
        }
    }
}
