package org.melodist.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.PlaybackCredentialsManager
import org.melodist.api.PlaybackVipTier
import org.melodist.api.QualityResult
import org.melodist.api.SessionKeeper
import org.melodist.api.SessionState
import org.melodist.api.UserSession
import org.melodist.api.getPlayUrl
import org.melodist.api.getSongVisualMid
import org.melodist.data.AppSettingsManager
import org.melodist.data.LyricCacheManager
import org.melodist.data.download.DownloadManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.ChorusDetector
import org.melodist.model.LyricLine
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.QualityOption
import org.melodist.model.Song
import org.melodist.playback.policy.ListenTogetherTrialPolicy
import org.melodist.playback.policy.PlaybackPolicyCoordinator

enum class PlaybackLoopMode(
    val label: String,
) {
    ListRepeat("列表循环"),
    SingleRepeat("单曲循环"),
    Shuffle("随机播放"),
}

interface PlaybackInterceptor {
    fun onInterceptPlaySong(
        song: Song,
        forceTier: AudioQualityTier?,
        seekToMs: Long,
    ): Boolean = false

    fun onInterceptSwitchTier(tier: AudioQualityTier): Boolean = false

    fun onInterceptTogglePlayPause(): Boolean = false

    fun onInterceptPause(): Boolean = false

    fun onInterceptResume(): Boolean = false

    fun onInterceptSeekTo(positionMs: Long): Boolean = false

    fun onInterceptPlayNext(): Boolean = false

    fun onInterceptPlayPrevious(): Boolean = false

    fun onInterceptCycleLoopMode(): Boolean = false
}

/**
 * 播放器核心事件监听器接口。
 */
interface PlaybackEventListener {
    fun onPlay() {}

    fun onPause() {}

    fun onSeekTo(positionMs: Long) {}

    fun onSongPlay(song: Song) {}
}

object PlaybackManager {
    var playbackInterceptor: PlaybackInterceptor? = null
    private val playbackEventListeners = java.util.concurrent.CopyOnWriteArrayList<PlaybackEventListener>()

    fun addPlaybackEventListener(listener: PlaybackEventListener) {
        playbackEventListeners.add(listener)
    }

    fun removePlaybackEventListener(listener: PlaybackEventListener) {
        playbackEventListeners.remove(listener)
    }

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val apiService = MusicApiService()
    private var appContext: Context? = null

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val exoPlayer: ExoPlayer? get() = playerPipeline.player
    private var playJob: Job? = null
    private var lyricLoadJob: Job? = null
    private var retryJob: Job? = null

    private val remoteStateHolder = PlaybackRemoteStateHolder()
    val isRemoteActive: StateFlow<Boolean> = remoteStateHolder.isRemoteActive
    val remoteDeviceName: StateFlow<String?> = remoteStateHolder.remoteDeviceName
    val remotePrevSong: StateFlow<Song?> = remoteStateHolder.remotePrevSong
    val remoteNextSong: StateFlow<Song?> = remoteStateHolder.remoteNextSong

    fun setRemoteActive(
        active: Boolean,
        deviceName: String? = null,
    ) {
        remoteStateHolder.setRemoteActive(active, deviceName)
    }

    fun clearRemotePlayback() {
        stopSilentKeepAlive()
        resetPlaybackSpeed()
        if (!remoteStateHolder.isRemoteActive.value) return
        remoteStateHolder.clearRemotePlayback()
        _isPlaying.value = false
        _currentSong.value = null
        _chorusSections.value = emptyList()
        _currentPositionMs.value = 0L
        _bufferedPositionMs.value = 0L
        _fileCacheFraction.value = 0f
        _durationMs.value = 0L
    }

    private val favoriteController =
        PlaybackFavoriteController(
            scope = scope,
            apiService = apiService,
            onStateChanged = { savePlaybackState() },
        )
    val favoriteSongMids: StateFlow<Set<String>> = favoriteController.favoriteSongMids
    val songFavoriteToggledEvent: SharedFlow<Pair<Song, Boolean>> = favoriteController.songFavoriteToggledEvent

    private val queueManager =
        PlaybackQueueManager(
            scope = scope,
            apiService = apiService,
            onStateChanged = { savePlaybackState() },
            onPlaySongRequest = { song, forceTier, seekToMs ->
                playSong(song, forceTier = forceTier, seekToMs = seekToMs, isQueueAdvance = true)
            },
            onStopPlaybackRequest = {
                progressTracker.flushActivePlaybackSession()
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                _currentSong.value = null
                applyLyricsWithChorus(emptyList())
                _isPlaying.value = false
            },
        )

    private var pendingTrialSongMid: String? = null
    private var pendingTrialTime: Long = 0L
    private var lastNotifiedTrialSongMid: String? = null

    /** 已就"本地判定无会员"提示过的歌曲，避免每次起播都弹同一条提示。 */
    private var lastNotifiedVipHintSongMid: String? = null

    init {
        scope.launch {
            ListenTogetherManager.isBothVip.collect { bothVip ->
                if (!bothVip) {
                    checkAndNotifyListenTogetherTrial(_currentSong.value)
                }
            }
        }
    }

    fun resetListenTogetherTrialState() {
        pendingTrialSongMid = null
        pendingTrialTime = 0L
        lastNotifiedTrialSongMid = null
    }

    private fun checkListenTogetherTrialClick(song: Song): Boolean {
        val now = System.currentTimeMillis()
        return if (pendingTrialSongMid == song.songMid && (now - pendingTrialTime) < 3000L) {
            pendingTrialSongMid = null
            pendingTrialTime = 0L
            true
        } else {
            pendingTrialSongMid = song.songMid
            pendingTrialTime = now
            notifyUser("VIP音乐需要双方开通后播放，再次点击可播放1分钟试听")
            false
        }
    }

    fun checkAndNotifyListenTogetherTrial(song: Song?) {
        val target = song ?: _currentSong.value ?: return
        if (policyCoordinator.isTrialActive(target)) {
            if (lastNotifiedTrialSongMid != target.songMid) {
                lastNotifiedTrialSongMid = target.songMid
                val isSelfVip = UserSession.profileFlow.value.isVip
                if (isSelfVip) {
                    notifyUser("对方不是vip正在播放试听1分钟")
                } else {
                    notifyUser("您不是vip正在播放试听1分钟")
                }
            }
        }
    }

    val playlist: StateFlow<List<Song>> = queueManager.playlist
    val currentIndex: StateFlow<Int> = queueManager.currentIndex
    val loopMode: StateFlow<PlaybackLoopMode> = queueManager.loopMode
    val isRadioMode: StateFlow<Boolean> = queueManager.isRadioMode
    val queueTag: StateFlow<String?> = queueManager.queueTag
    val sourceContext: StateFlow<PlaybackSourceContext?> = queueManager.sourceContext
    val paginationSource: StateFlow<QueuePaginationSource?> = queueManager.paginationSource
    val isLoadingMoreForQueue: StateFlow<Boolean> = queueManager.isLoadingMoreForQueue

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _preferredTier = MutableStateFlow(AudioQualityTier.HiRes)
    val preferredTier: StateFlow<AudioQualityTier> = _preferredTier.asStateFlow()

    private val _currentTier = MutableStateFlow(AudioQualityTier.Standard)
    val currentTier: StateFlow<AudioQualityTier> = _currentTier.asStateFlow()

    private val _currentTrackSpec = MutableStateFlow<AudioTrackSpec?>(null)
    val currentTrackSpec: StateFlow<AudioTrackSpec?> = _currentTrackSpec.asStateFlow()

    val policyCoordinator by lazy {
        PlaybackPolicyCoordinator(
            scope = scope,
            currentRoomFlow = ListenTogetherManager.currentRoom,
            isBothVipFlow = ListenTogetherManager.isBothVip,
            currentSongFlow = _currentSong,
            policies =
                listOf(
                    ListenTogetherTrialPolicy(
                        isInRoomProvider = { ListenTogetherManager.isInRoom },
                        isBothVipProvider = { ListenTogetherManager.isBothVip.value },
                    ),
                ),
        )
    }

    val isListenTogetherTrialActive: StateFlow<Boolean>
        get() = policyCoordinator.isListenTogetherTrialActive

    fun resolveTierBadgeText(
        song: Song? = null,
        tier: AudioQualityTier = _currentTier.value,
    ): String {
        val s = song ?: _currentSong.value ?: return AudioQualityTier.getBadge(tier)
        return policyCoordinator.resolveTierBadgeText(s, tier)
    }

    val qualityCoordinator by lazy { AudioQualityCoordinator(scope, apiService) }
    val lyricsCoordinator by lazy {
        PlaybackLyricsCoordinator(
            scope = scope,
            apiService = apiService,
            currentSongProvider = { _currentSong.value },
            onLyricsUpdated = { lyrics ->
                scope.launch(Dispatchers.Main) {
                    applyLyricsWithChorus(lyrics)
                    _currentSong.value?.let { updateCurrentMediaMetadata(it) }
                }
            },
            onOffsetCalibrated = { _currentLyricOffsetMs.value = it },
            onLyricsLoadedBroadcast = { _lyricsLoadedFlow.tryEmit(it) },
        )
    }

    val availableTiers: StateFlow<Set<AudioQualityTier>> get() = qualityCoordinator.availableTiers
    val probedQualityOptions: StateFlow<List<QualityOption>> get() = qualityCoordinator.probedQualityOptions
    val isProbingQuality: StateFlow<Boolean> get() = qualityCoordinator.isProbingQuality

    private val _isSwitchingQuality = MutableStateFlow(false)
    val isSwitchingQuality: StateFlow<Boolean> = _isSwitchingQuality.asStateFlow()

    private var switchQualityJob: Job? = null

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _chorusSections = MutableStateFlow<List<ChorusDetector.ChorusSection>>(emptyList())

    /** 当前曲副歌（高潮）区间：由逐行歌词重复结构检测派生，生命周期与歌词一致 */
    val chorusSections: StateFlow<List<ChorusDetector.ChorusSection>> = _chorusSections.asStateFlow()

    /** 歌词与副歌点位统一赋值入口：点位随歌词同生命周期，无当前曲时不画点 */
    private fun applyLyricsWithChorus(lyrics: List<LyricLine>) {
        _lyrics.value = lyrics
        val current = _currentSong.value
        _chorusSections.value =
            if (current == null) {
                emptyList()
            } else {
                ChorusDetector.detect(lyrics, current.durationSeconds * 1000L)
            }
    }

    private val _currentLyricOffsetMs = MutableStateFlow(0L)
    val currentLyricOffsetMs: StateFlow<Long> = _currentLyricOffsetMs.asStateFlow()

    private val _isCurrentTrackFromCache = MutableStateFlow(false)
    val isCurrentTrackFromCache: StateFlow<Boolean> = _isCurrentTrackFromCache.asStateFlow()

    private val _fileCacheFraction = MutableStateFlow(0f)
    val fileCacheFraction: StateFlow<Float> = _fileCacheFraction.asStateFlow()

    private val _lyricsLoadedFlow = MutableSharedFlow<Pair<Song, List<LyricLine>>>(extraBufferCapacity = 8)
    val lyricsLoadedFlow: SharedFlow<Pair<Song, List<LyricLine>>> = _lyricsLoadedFlow.asSharedFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private var currentSongRetryCount = 0
    private var consecutiveErrorCount = 0
    private var audioTrackRetryCount = 0
    private var lastCustomStreamArgs: Triple<Song, String, Map<String, String>>? = null

    private fun handlePlaybackFailure(
        errorMsg: String,
        allowCurrentSongRetry: Boolean = true,
    ) {
        _isLoading.value = false
        _isTransitioning.value = false
        val curr = _currentSong.value

        if (allowCurrentSongRetry && curr != null && currentSongRetryCount < 2) {
            currentSongRetryCount++
            Log.w("QQMusicPlayback", "Playback failure for ${curr.name} (retry $currentSongRetryCount/2): $errorMsg")
            _errorMessage.value = "网络加载波动，正在等待重试 ($currentSongRetryCount/2)..."
            _isLoading.value = true
            retryJob?.cancel()
            retryJob =
                scope.launch {
                    delay(3000L)
                    val targetSong = _currentSong.value
                    if (targetSong?.songMid == curr.songMid) {
                        val custom = lastCustomStreamArgs
                        if (custom != null && custom.first.songMid == curr.songMid) {
                            playCustomStream(custom.first, custom.second, custom.third, seekToMs = _currentPositionMs.value)
                        } else {
                            playSong(curr, forceTier = _currentTier.value, seekToMs = _currentPositionMs.value)
                        }
                    }
                }
            return
        }

        currentSongRetryCount = 0
        audioTrackRetryCount = 0
        consecutiveErrorCount++
        if (consecutiveErrorCount >= 3) {
            Log.w("QQMusicPlayback", "Continuous playback failure reached limit (3), stopping playback.")
            _errorMessage.value = "$errorMsg (多次尝试失败已停止)"
            _isPlaying.value = false
            exoPlayer?.stop()
            consecutiveErrorCount = 0
        } else {
            _errorMessage.value = "$errorMsg (即将尝试下一首 $consecutiveErrorCount/3)"
            if (remoteStateHolder.isRemoteActive.value) {
                Log.w("QQMusicPlayback", "Remote playback is active, skip local error auto-advance.")
                return
            }
            if (ListenTogetherManager.isInRoom) {
                Log.w("QQMusicPlayback", "In listen together room, skip local error auto-advance.")
                return
            }
            scope.launch {
                delay(3000L)
                playNext()
            }
        }
    }

    val isLocalPlaybackActive: Boolean
        get() = exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != androidx.media3.common.Player.STATE_IDLE

    fun shouldHoldForeground(): Boolean {
        if (remoteStateHolder.isRemoteActive.value && _currentSong.value != null) {
            return _isPlaying.value || _isSilentKeepAlive.value
        }
        val player = exoPlayer ?: return false
        return player.playWhenReady || _isPlaying.value || _isLoading.value || _isTransitioning.value
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /**
     * 设置播放器静音状态。
     *
     * @param muted true 为静音，false 为解除静音
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        exoPlayer?.volume = if (muted) 0f else 1f
    }

    private val _isSilentKeepAlive = MutableStateFlow(false)
    val isSilentKeepAlive: StateFlow<Boolean> = _isSilentKeepAlive.asStateFlow()

    fun startSilentKeepAlive() {
        val player = exoPlayer ?: return
        if (_isSilentKeepAlive.value && player.isPlaying) {
            return
        }
        _isSilentKeepAlive.value = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        val silenceSource = SilenceMediaSource(86_400_000_000L)
        player.setMediaSource(silenceSource)
        player.prepare()
        player.play()
    }

    fun pauseSilentKeepAlive() {
        if (!_isSilentKeepAlive.value) return
        exoPlayer?.pause()
    }

    fun stopSilentKeepAlive() {
        if (!_isSilentKeepAlive.value) {
            exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
            return
        }
        _isSilentKeepAlive.value = false
        val player = exoPlayer ?: return
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.stop()
        player.clearMediaItems()
        resetPlaybackSpeed()
    }

    val activeMediaId: String?
        get() = exoPlayer?.currentMediaItem?.mediaId

    val actualAudioPositionMs: Long
        get() = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _currentPositionMs.value

    val actualAudioIsPlaying: Boolean
        get() = exoPlayer?.isPlaying == true || (exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != androidx.media3.common.Player.STATE_ENDED)

    /**
     * 设置播放器回放倍速。
     *
     * @param speed 目标倍速（如 1.0f、1.25f、1.5f）
     */
    fun setPlaybackSpeed(speed: Float) {
        val player = exoPlayer ?: return
        if (Math.abs(player.playbackParameters.speed - speed) > 0.005f) {
            player.setPlaybackSpeed(speed)
        }
    }

    /**
     * 重置播放器回放倍速至标准 1.0x。
     */
    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f)
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun notifyUser(message: String) {
        _errorMessage.value = message
        appContext?.let { ctx ->
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast
                        .makeText(ctx, message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: Exception) {
                Log.w("QQMusicPlayback", "Failed to dispatch user toast notification", e)
            }
        }
    }

    private val usbRouter =
        UsbAudioRouter(
            onDeviceStateChanged = { needReload ->
                val isUsbExclusive = org.melodist.data.AppSettingsManager.settings.value.enableUsbExclusive
                updateUsbExclusiveRouting()
                if (needReload && isUsbExclusive && exoPlayer?.isPlaying == true) {
                    reloadAudioPipeline()
                }
            },
        )
    val activeUsbDeviceName: StateFlow<String?> = usbRouter.activeUsbDeviceName

    private val audioTrackResolver by lazy {
        PlaybackAudioTrackResolver(
            usbRouter = usbRouter,
            getContext = { appContext },
            onReloadAudioPipelineRequest = { reloadAudioPipeline() },
        )
    }

    private val probeHealer by lazy {
        PlaybackProbeHealer(
            scope = scope,
            apiService = apiService,
            qualityCoordinator = qualityCoordinator,
            queueManager = queueManager,
            getContext = { appContext },
            getCurrentSong = { _currentSong.value },
            setCurrentSong = { _currentSong.value = it },
            getCurrentTier = { _currentTier.value },
            setCurrentTier = { _currentTier.value = it },
            getPreferredTier = { _preferredTier.value },
            getCurrentTrackSpec = { _currentTrackSpec.value },
            setCurrentTrackSpec = { _currentTrackSpec.value = it },
            onSwitchTierRequest = { switchTier(it) },
        )
    }

    private val preloadCoordinator by lazy {
        PlaybackPreloadCoordinator(
            scope = scope,
            apiService = apiService,
            queueManager = queueManager,
            lyricsCoordinator = lyricsCoordinator,
            getContext = { appContext },
            getCurrentSong = { _currentSong.value },
            getPreferredTier = { _preferredTier.value },
            clampCellularTier = { tier, song -> clampCellularTier(tier, song) },
            getPreviousSong = { getPreviousSong() },
            getNextSong = { getNextSong() },
        )
    }

    // ==================== 播放增强（无缝播放 / 淡入淡出 / 音量平衡） ====================

    /** 新活动播放器就绪后的回调，供 [PlaybackService] 重建 MediaSession。 */
    var onActivePlayerSwappedListener: (() -> Unit)? = null

    /** 已解析（并可能已入队）的下一首。 */
    private data class PreparedNextTrack(
        val song: Song,
        val target: PlaybackTargetResult,
        val gain: Float,
    )

    private var gaplessPrepared: PreparedNextTrack? = null
    private var gaplessPrepareJob: Job? = null
    private var crossfadePrepared: PreparedNextTrack? = null
    private var crossfadeJob: Job? = null
    private var crossfadeActive = false

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // 音质切换期间，忽略 ExoPlayer 内部重新加载媒体源引起的短暂 isPlaying=false 状态
                if (_isSwitchingQuality.value && !playing && exoPlayer?.playWhenReady == true) {
                    return
                }
                _isPlaying.value = playing
                if (playing) {
                    currentSongRetryCount = 0
                    consecutiveErrorCount = 0
                    audioTrackRetryCount = 0
                } else {
                    progressTracker.flushActivePlaybackSession()
                    savePlaybackProgress(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                try {
                    val current = _currentSong.value ?: return
                    val enriched = PlaybackMetadataCoordinator.enrichFromExoMetadata(current, mediaMetadata) ?: return

                    Log.i("QQMusicPlayback", "Enriched metadata from ExoPlayer: ${enriched.name} - ${enriched.singer} (${enriched.album})")
                    _currentSong.value = enriched
                    queueManager.updateSongInPlaylist(enriched)
                    updateCurrentMediaMetadata(enriched)
                    org.melodist.data.RecentPlaybackManager
                        .recordSong(enriched)
                    savePlaybackState()

                    if (enriched.name != current.name || enriched.singer != current.singer) {
                        lyricsCoordinator.loadLyricsForSong(enriched)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("QQMusicPlayback", "Failed to apply enriched metadata from ExoPlayer", e)
                }
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // 无缝播放：预先入队的下一首由 ExoPlayer 自动续播，这里只做状态同步
                    val prepared = gaplessPrepared
                    if (prepared != null && mediaItem?.mediaId == prepared.song.songMid) {
                        gaplessPrepared = null
                        onAutoAdvancedToSong(prepared)
                        return
                    }
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
                    if (queueManager.loopMode.value != PlaybackLoopMode.SingleRepeat) {
                        handleSongEnded()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        _durationMs.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                        if (_lyrics.value.isNotEmpty()) {
                            _currentSong.value?.let { current ->
                                legacyMetadataSyncCallback?.invoke(current, _lyrics.value)
                            }
                        }
                        if (exoPlayer?.isPlaying == true) {
                            currentSongRetryCount = 0
                            consecutiveErrorCount = 0
                            audioTrackRetryCount = 0
                        }
                        if (_isSwitchingQuality.value) {
                            _isSwitchingQuality.value = false
                            _isPlaying.value = exoPlayer?.isPlaying == true
                        }
                        if (remoteStateHolder.isRemoteActive.value && !_isSilentKeepAlive.value) {
                            val estimatedTvPos = remoteStateHolder.getEstimatedPositionMs(_durationMs.value)
                            val playerPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                            if (estimatedTvPos != null && estimatedTvPos > 250L && Math.abs(estimatedTvPos - playerPos) > 250L) {
                                exoPlayer?.seekTo(estimatedTvPos)
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        _isSwitchingQuality.value = false
                        handleSongEnded()
                    }

                    Player.STATE_BUFFERING -> {
                        if (!_isSwitchingQuality.value) {
                            _isLoading.value = true
                        }
                    }

                    Player.STATE_IDLE -> {
                        _isSwitchingQuality.value = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isLoading.value = false
                Log.e("QQMusicPlayback", "ExoPlayer playback error: ${error.errorCodeName}, cause: ${error.cause?.message}", error)
                val current = _currentSong.value
                val curTier = _currentTier.value
                val fallback = getFallbackTier(curTier)

                val isAudioTrackError =
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                        error.cause is AudioSink.InitializationException ||
                        error.cause is AudioSink.WriteException

                if (isAudioTrackError && audioTrackRetryCount < 1) {
                    audioTrackRetryCount++
                    Log.w("QQMusicPlayback", "AudioTrack/AudioSink error encountered (${error.errorCodeName}). Resetting audio pipeline and retrying...")
                    _errorMessage.value = "音频驱动重置中，正在重试播放..."
                    scope.launch {
                        delay(300L)
                        resetPlayerPipeline(restoreMediaItem = false)
                        if (current != null) {
                            val custom = lastCustomStreamArgs
                            if (custom != null && custom.first.songMid == current.songMid) {
                                playCustomStream(custom.first, custom.second, custom.third, seekToMs = _currentPositionMs.value)
                            } else {
                                playSong(current, forceTier = curTier, seekToMs = _currentPositionMs.value)
                            }
                        }
                    }
                    return
                }

                val isDummyUriFailure =
                    current != null &&
                        (
                            exoPlayer
                                ?.currentMediaItem
                                ?.localConfiguration
                                ?.uri
                                ?.host == "cache.qqmusic.internal"
                        )
                if (isDummyUriFailure) {
                    Log.w("QQMusicPlayback", "Local cache playback failed for ${current.name}, evicting cache and falling back to network")
                    QQMusicCacheManager.evictIncompleteCacheAsync(current.songMid, curTier)
                    scope.launch {
                        delay(200L)
                        playSong(current, forceTier = curTier, seekToMs = _currentPositionMs.value)
                    }
                    return
                }

                if (current != null && fallback != null && !isLocalPlaybackSong(current)) {
                    Log.w("QQMusicPlayback", "Playback failed at tier $curTier, falling back to $fallback")
                    qualityCoordinator.recordTierFailureForSong(current.songMid, curTier)
                    _errorMessage.value = "当前音质播放失败，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                    scope.launch {
                        delay(500L)
                        switchTier(fallback, isAutoFallback = true)
                    }
                } else {
                    handlePlaybackFailure("播放失败: ${error.errorCodeName}")
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) &&
                    !tracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                ) {
                    Log.w("QQMusicPlayback", "onTracksChanged: Audio track exists but none selected (missing decoder)")
                    handlePlaybackFailure("播放失败：当前设备缺少该音频格式解码器")
                    return
                }

                val resolved =
                    audioTrackResolver.resolveSelectedAudioTrack(
                        tracks = tracks,
                        currentSong = _currentSong.value,
                        currentTier = _currentTier.value,
                        probedOptions = probedQualityOptions.value,
                    ) ?: return

                _currentTrackSpec.value = resolved.trackSpec
                if (resolved.shouldApplyTierToSong) {
                    val currentSong = _currentSong.value
                    Log.i(
                        "QQMusicPlayback",
                        "onTracksChanged auto-detected tier: ${resolved.detectedTier}",
                    )
                    _currentTier.value = resolved.detectedTier
                    _currentSong.value = currentSong?.copy(currentTier = resolved.detectedTier)
                }
            }
        }

    private val playerPipeline: PlaybackExoPlayerPipeline =
        PlaybackExoPlayerPipeline(
            getContext = { appContext },
            usbRouter = usbRouter,
            playerListener = playerListener,
            onPlayerCreated = {
                progressTracker.start()
            },
        )

    private val analyticsReporter =
        PlaybackAnalyticsReporter(
            scope = scope,
            apiService = apiService,
            getContext = { appContext },
            getSourceContext = { queueManager.sourceContext.value },
            getCurrentSong = { _currentSong.value },
        )

    private val progressTracker: PlaybackProgressTracker =
        PlaybackProgressTracker(
            scope = scope,
            getPlayer = { exoPlayer },
            isRemoteActive = { remoteStateHolder.isRemoteActive.value },
            getEstimatedRemotePositionMs = { dur -> remoteStateHolder.getEstimatedPositionMs(dur) },
            getCurrentSong = { _currentSong.value },
            getCurrentTier = { _currentTier.value },
            isCurrentTrackFromCache = { _isCurrentTrackFromCache.value },
            getDurationMs = { _durationMs.value },
            isSongFavorite = { mid -> isSongFavorite(mid) },
            onPositionUpdated = { pos -> _currentPositionMs.value = pos },
            onDurationUpdated = { dur -> _durationMs.value = dur },
            onBufferedPositionUpdated = { buf -> _bufferedPositionMs.value = maxOf(_bufferedPositionMs.value, buf) },
            onFileCacheFractionUpdated = { frac -> _fileCacheFraction.value = frac },
            onTrackFromCacheConfirmed = { _isCurrentTrackFromCache.value = true },
            onSavePlaybackProgressRequest = { pos -> savePlaybackProgress(pos) },
            onTriggerPrefetchNextSongRequest = { preloadCoordinator.triggerPrefetchNextSong() },
            onSongActivePlaybackQualified = { song -> analyticsReporter.onSongActivePlaybackQualified(song) },
            onContextActivePlaybackQualified = { analyticsReporter.onContextActivePlaybackQualified() },
            onSongPlaybackSessionFinished = { song, playedMs -> analyticsReporter.onSongPlaybackSessionFinished(song, playedMs) },
            onPlaybackTick = { pos, dur -> onPlaybackProgressTick(pos, dur) },
        )

    private fun buildExoPlayer(context: Context): ExoPlayer = playerPipeline.buildExoPlayer(context)

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        init(context)
        return playerPipeline.getOrCreatePlayer(context)
    }

    fun buildMediaMetadata(
        song: Song,
        remoteDeviceName: String? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaMetadata = PlaybackMediaItemFactory.buildMediaMetadata(song, remoteDeviceName, lyrics)

    private fun buildMediaItem(
        uri: android.net.Uri,
        song: Song,
        tier: AudioQualityTier? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItem(uri, song, tier, lyrics)

    fun buildMediaItemForSong(
        song: Song,
        remoteDeviceName: String? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItemForSong(song, remoteDeviceName, lyrics)

    var legacyMetadataSyncCallback: ((song: Song, lyrics: List<LyricLine>) -> Unit)? = null

    fun updateCurrentMediaMetadata(song: Song) {
        val player = exoPlayer ?: return
        val currentItem = player.currentMediaItem
        if (currentItem != null && currentItem.mediaId == song.songMid && player.currentMediaItemIndex >= 0) {
            val updatedItem = currentItem.buildUpon().setMediaMetadata(buildMediaMetadata(song, lyrics = _lyrics.value)).build()
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
        legacyMetadataSyncCallback?.invoke(song, _lyrics.value)
    }

    /**
     * 启动后台播放服务。
     *
     * @param context 上下文对象
     * @param foreground 是否以显式前台服务形式启动
     */
    fun startPlaybackService(
        context: Context,
        foreground: Boolean = false,
    ) {
        try {
            val intent = Intent(context.applicationContext, PlaybackService::class.java)
            if (foreground) {
                intent.putExtra(PlaybackService.EXTRA_START_FOREGROUND, true)
                androidx.core.content.ContextCompat
                    .startForegroundService(context.applicationContext, intent)
            } else {
                try {
                    context.applicationContext.startService(intent)
                } catch (_: IllegalStateException) {
                    intent.putExtra(PlaybackService.EXTRA_START_FOREGROUND, true)
                    androidx.core.content.ContextCompat
                        .startForegroundService(context.applicationContext, intent)
                }
            }
        } catch (e: Exception) {
            Log.w("QQMusicPlayback", "Failed to start PlaybackService: $e")
        }
    }

    /**
     * 初始化播放管理器单例运行环境，绑定缓存配额提供者并恢复历史持久化状态。
     *
     * @param context 应用程序上下文
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // 每一步单独兜底：缓存/歌词等子系统的故障不应该让播放器整个不可用。
            safeInit("QQMusicCacheManager.init") {
                val profile = PlaybackProfile.detect(context)
                QQMusicCacheManager.init(context, profile)
                org.melodist.data.AppSettingsManager.mediaCacheSizeProvider = { QQMusicCacheManager.getCacheSizeBytes() }
                org.melodist.data.AppSettingsManager.mediaQuotaProvider = { QQMusicCacheManager.activeCacheQuotaBytes }
                org.melodist.data.AppSettingsManager.cachedTrackCountProvider = { QQMusicCacheManager.getCachedKeyCount() }
                org.melodist.data.AppSettingsManager.mediaCacheClearAction = { QQMusicCacheManager.clearAllCache() }
                org.melodist.data.AppSettingsManager.onMediaCacheLimitChangedListener = { limitGb ->
                    QQMusicCacheManager.applyUserQuotaGb(limitGb)
                }
                DownloadManager.cacheExporter = QQMusicCacheManager
            }
            safeInit("LyricCacheManager.init") { LyricCacheManager.init(context) }
            safeInit("LocalLyricAutoMatcher.init") { LocalLyricAutoMatcher.init(context) }
            safeInit("restorePlaybackState") { restorePlaybackState() }
            safeInit("syncFavoriteSongsAsync") {
                if (UserSession.isLoggedIn) {
                    syncFavoriteSongsAsync()
                }
            }
        }
        safeInit("usbRouter.register") { usbRouter.register(context) }
        safeInit("sleepTimerFadeOutListener") {
            SleepTimerManager.onTimerExpiredListener = { onSleepTimerExpired() }
        }
        safeInit("onUsbExclusiveChangedListener") {
            org.melodist.data.AppSettingsManager.onUsbExclusiveChangedListener = {
                resetPlayerPipeline()
            }
        }
        safeInit("buildExoPlayer") {
            if (exoPlayer == null) {
                playerPipeline.buildExoPlayer(context)
                _isPlaying.value = false
                progressTracker.start()
            } else {
                updateUsbExclusiveRouting()
            }
        }
        safeInit("startPlaybackService") { startPlaybackService(context) }
    }

    /**
     * 单步初始化兜底：失败只降级该子系统并记入启动降级日志，不冒泡到调用方（启动路径）。
     *
     * 协程取消必须原样抛出，否则会破坏结构化并发。
     */
    private fun safeInit(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w("QQMusicPlayback", "Playback init step failed: $step", e)
            org.melodist.data.CrashGuard.record("PlaybackManager.$step", e)
        }
    }

    fun resetPlayerPipeline(restoreMediaItem: Boolean = true) = playerPipeline.resetPlayerPipeline(restoreMediaItem)

    fun reloadAudioPipeline() = playerPipeline.reloadAudioPipeline()

    fun updateUsbExclusiveRouting(
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) = playerPipeline.updateUsbExclusiveRouting(targetRate, channelCount, pcmEncoding)

    @Suppress("UnusedParameter")
    fun onAudioPassthroughChanged(enabled: Boolean) {
        // 仅更新配置，在下一次起播时生效
    }

    private fun startProgressLoop() = progressTracker.start()

    /**
     * 将当前播放队列、曲目索引与循环模式持久化保存至本地存储。
     */
    fun savePlaybackState() {
        PlaybackStateStorage.savePlaybackState(
            context = appContext,
            currentSong = _currentSong.value,
            playlist = queueManager.playlist.value,
            favoriteSongMids = favoriteController.favoriteSongMids.value,
            currentIndex = queueManager.currentIndex.value,
            currentPositionMs = _currentPositionMs.value,
            durationMs = _durationMs.value,
            preferredTier = _preferredTier.value,
            loopMode = queueManager.loopMode.value,
            shuffledIndices = queueManager.shuffleQueue.serialize(),
            shuffledPointer = queueManager.shuffleQueue.pointer,
            isRadioMode = queueManager.isRadioMode.value,
        )
    }

    fun savePlaybackProgress(posMs: Long = _currentPositionMs.value) {
        PlaybackStateStorage.savePlaybackProgress(appContext, posMs)
    }

    private fun restorePlaybackState() {
        val restored = PlaybackStateStorage.restorePlaybackState(appContext)
        restored.preferredTier?.let { _preferredTier.value = it }
        favoriteController.restoreFavorites(restored.favoriteSongMids)
        queueManager.restoreState(
            playlist = restored.playlist,
            currentIndex = restored.currentIndex,
            loopMode = restored.loopMode,
            isRadioMode = restored.isRadioMode,
            shuffledIndices = restored.shuffledIndices,
            shuffledPointer = restored.shuffledPointer,
        )
        if (restored.currentSong != null) {
            _currentSong.value = restored.currentSong
            _currentTier.value = restored.currentSong.currentTier
            val cached = LyricCacheManager.getLyrics(restored.currentSong.songMid).orEmpty()
            applyLyricsWithChorus(cached)
            loadLyricsForSong(restored.currentSong)
        }
        _currentPositionMs.value = restored.currentPositionMs
        _durationMs.value = restored.durationMs
    }

    fun setFavoriteSongMids(mids: Set<String>) = favoriteController.setFavoriteSongMids(mids)

    fun addFavoriteSongMids(mids: Collection<String>) = favoriteController.addFavoriteSongMids(mids)

    fun removeFavoriteSongMids(mids: Collection<String>) = favoriteController.removeFavoriteSongMids(mids)

    fun syncFavoriteSongsAsync(forceRefresh: Boolean = false) = favoriteController.syncFavoriteSongsAsync(forceRefresh)

    fun isSongFavoriteSupported(song: Song?): Boolean = favoriteController.isSongFavoriteSupported(song)

    fun isSongFavorite(songMid: String?): Boolean = favoriteController.isSongFavorite(songMid)

    fun setSongFavoriteState(
        songMid: String,
        isFav: Boolean,
    ) = favoriteController.setSongFavoriteState(songMid, isFav, _currentSong.value)

    fun toggleSongFavorite(song: Song) = favoriteController.toggleSongFavorite(song, appContext)

    fun toggleCurrentSongFavorite() {
        val song = _currentSong.value ?: return
        toggleSongFavorite(song)
    }

    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int = 0,
        isRadio: Boolean = false,
        initialSeekToMs: Long = 0L,
        startMuted: Boolean = false,
        forceTier: AudioQualityTier? = null,
        paginationSource: QueuePaginationSource? = null,
        queueTag: String? = null,
        sourceContext: PlaybackSourceContext? = null,
    ) {
        if (ListenTogetherManager.isInRoom) {
            val targetSong = songs.getOrNull(startIndex) ?: songs.firstOrNull()
            if (targetSong != null) {
                if (isLocalPlaybackSong(targetSong)) {
                    notifyUser("请退出一起听后再播放哦")
                    return
                }
                if (targetSong.isVip && !ListenTogetherManager.isBothVip.value && !ListenTogetherManager.isSyncingRemote) {
                    if (!checkListenTogetherTrialClick(targetSong)) {
                        return
                    }
                }
            }
        }
        analyticsReporter.resetContextReportedState()
        setMuted(startMuted)
        queueManager.setPlaylist(
            songs = songs,
            startIndex = startIndex,
            isRadio = isRadio,
            initialSeekToMs = initialSeekToMs,
            forceTier = forceTier,
            paginationSource = paginationSource,
            queueTag = queueTag,
            sourceContext = sourceContext,
        )
    }

    fun setPaginationSource(source: QueuePaginationSource?) = queueManager.setPaginationSource(source)

    suspend fun loadMoreForQueue(): Boolean = queueManager.loadMoreForQueue()

    fun appendPlaylist(
        newSongs: List<Song>,
        targetTag: String? = null,
    ) {
        if (ListenTogetherManager.isInRoom) {
            val filtered = newSongs.filterNot { isLocalPlaybackSong(it) }
            if (filtered.isEmpty() && newSongs.isNotEmpty()) {
                notifyUser("请退出一起听后再播放哦")
                return
            }
            queueManager.appendPlaylist(filtered, targetTag)
            return
        }
        queueManager.appendPlaylist(newSongs, targetTag)
    }

    fun insertNextPlay(song: Song) {
        if (ListenTogetherManager.isInRoom && isLocalPlaybackSong(song)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        queueManager.insertNextPlay(song)
    }

    fun insertAndPlay(
        song: Song,
        seekToMs: Long = 0L,
    ) {
        if (ListenTogetherManager.isInRoom) {
            if (isLocalPlaybackSong(song)) {
                notifyUser("请退出一起听后再播放哦")
                return
            }
            if (song.isVip && !ListenTogetherManager.isBothVip.value && !ListenTogetherManager.isSyncingRemote) {
                if (!checkListenTogetherTrialClick(song)) {
                    return
                }
            }
        }
        queueManager.insertAndPlay(song, seekToMs)
    }

    fun removeFromPlaylist(index: Int) = queueManager.removeFromPlaylist(index)

    fun removeFromPlaylist(songs: List<Song>) = queueManager.removeFromPlaylist(songs, _currentSong.value?.songMid)

    val isSimilarRecommendActive: StateFlow<Boolean> = queueManager.isSimilarRecommendActive
    private val _showQueueSheetFlow = MutableStateFlow(false)
    val showQueueSheetFlow: StateFlow<Boolean> = _showQueueSheetFlow.asStateFlow()

    fun requestOpenQueueSheet() {
        _showQueueSheetFlow.value = true
    }

    fun consumeOpenQueueSheet() {
        _showQueueSheetFlow.value = false
    }

    /**
     * 以指定歌曲为种子激活相似歌曲推荐队列并开始播放。
     *
     * @param anchorSong 作为相似推荐基准的种子歌曲实体
     * @param openQueue 激活后是否触发打开播放队列抽屉面板
     * @return 推荐列表拉取成功并完成激活返回 true，推荐结果为空返回 false
     */
    suspend fun activateSimilarRecommend(
        anchorSong: Song,
        openQueue: Boolean = true,
    ): Boolean {
        val currentPlaying = _currentSong.value
        val result = queueManager.activateSimilarRecommend(anchorSong)
        if (result.isNotEmpty()) {
            if (currentPlaying?.songMid != anchorSong.songMid) {
                playSong(anchorSong)
            }
            if (openQueue) {
                requestOpenQueueSheet()
            }
            return true
        }
        return false
    }

    fun restorePlaylistFromSimilar(): Boolean = queueManager.restorePlaylistFromSimilar()

    fun clearPlaylist() {
        analyticsReporter.resetContextReportedState()
        queueManager.clearPlaylist()
    }

    /**
     * 起播指定歌曲，支持从指定断点续播、本地/在线多源加载以及音质级联降级。
     *
     * @param song 目标曲目实体
     * @param forceTier 强制指定的音质级别；为 null 时采用用户全局首选音质偏好
     * @param seekToMs 起播起始跳转时间戳（毫秒），默认 0L
     */
    fun playSong(
        song: Song,
        forceTier: AudioQualityTier? = null,
        seekToMs: Long = 0L,
        isQueueAdvance: Boolean = false,
    ) {
        val isLocalPlayback = isLocalPlaybackSong(song)
        if (ListenTogetherManager.isInRoom && isLocalPlayback) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        val isInTogether = ListenTogetherManager.isInRoom
        val isBothVip = ListenTogetherManager.isBothVip.value
        val isSyncing = ListenTogetherManager.isSyncingRemote

        if (!isLocalPlayback && song.isVip) {
            if (isInTogether && !isBothVip) {
                if (!isSyncing && !isQueueAdvance) {
                    if (!checkListenTogetherTrialClick(song)) {
                        return
                    }
                }
                checkAndNotifyListenTogetherTrial(song)
            } else if (!isInTogether) {
                val effectiveVipTier = PlaybackCredentialsManager.getEffectiveVipTier()
                if (effectiveVipTier == PlaybackVipTier.NONE) {
                    // 本地会员档位只作提示，不再硬拦截：能否播放最终由服务端返回的直链（purl）决定。
                    // 硬拦的代价是把"凭据未同步/已过期"的真实会员一并挡在门外（见 doc/18 §4.3）。
                    if (lastNotifiedVipHintSongMid != song.songMid) {
                        lastNotifiedVipHintSongMid = song.songMid
                        notifyUser(
                            when (SessionKeeper.state) {
                                SessionState.ReauthRequired -> "登录已过期，正在尝试恢复；失败请重新登录"
                                SessionState.TransientFailure -> "会员状态暂时未同步，正在自动重试"
                                else -> "该歌曲需要会员，正在尝试播放"
                            },
                        )
                    }
                }
            } else {
                lastNotifiedTrialSongMid = null
            }
        } else {
            lastNotifiedTrialSongMid = null
            lastNotifiedVipHintSongMid = null
        }

        stopSilentKeepAlive()
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        lastCustomStreamArgs = null
        val prevSong = _currentSong.value
        val prevPos = _currentPositionMs.value
        val prevDur = _durationMs.value
        val prevTier = _currentTier.value
        val isSongSwitching = (prevSong == null || prevSong.songMid != song.songMid)
        if (isSongSwitching) {
            QQMusicCacheManager.handleTrackSwitchEviction(prevSong?.songMid.orEmpty(), prevPos, prevDur, prevTier)
            _lyrics.value = emptyList()
            _isLoading.value = true
            _currentPositionMs.value = seekToMs
            _bufferedPositionMs.value = seekToMs
        }
        QQMusicCacheManager.onNewSongStarted(song.songMid)
        if (isSongSwitching) {
            currentSongRetryCount = 0
            audioTrackRetryCount = 0
            preloadCoordinator.onSongChanged(song.songMid)
        }
        exoPlayer?.let { player ->
            try {
                player.volume = 0f
                player.stop()
                player.clearMediaItems()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        playJob?.cancel()
        retryJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false
        cancelEnhancementTransitions()

        var effectiveSong = song
        val coverFile =
            if (effectiveSong.coverUrl.startsWith("file://")) {
                java.io.File(effectiveSong.coverUrl.removePrefix("file://").substringBefore('?'))
            } else {
                null
            }
        val isCoverInvalid = coverFile != null && (!coverFile.exists() || coverFile.length() == 0L)
        if (isCoverInvalid) {
            effectiveSong = effectiveSong.copy(coverUrl = "")
        }

        _currentSong.value = effectiveSong
        _currentPositionMs.value = seekToMs
        _bufferedPositionMs.value = seekToMs
        _durationMs.value = policyCoordinator.resolveDurationMs(effectiveSong)

        val initialLyrics = LyricCacheManager.getLyrics(effectiveSong.songMid).orEmpty()
        applyLyricsWithChorus(initialLyrics)
        loadLyricsForSong(effectiveSong)
        prefetchAdjacentCoversAndMetadata()

        if (playbackInterceptor?.onInterceptPlaySong(effectiveSong, forceTier, seekToMs) == true) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            _currentTier.value = forceTier ?: _preferredTier.value
            _isPlaying.value = true
            _isLoading.value = false
            _isTransitioning.value = false
            savePlaybackState()
            loadLyricsForSong(effectiveSong)
            appContext?.let { startPlaybackService(it) }
            return
        }

        playbackEventListeners.forEach { it.onSongPlay(effectiveSong) }
        _isTransitioning.value = true
        appContext?.let { startPlaybackService(it) }

        val rawTargetTier = forceTier ?: _preferredTier.value
        val cellularTier = clampCellularTier(rawTargetTier, effectiveSong)
        val targetTierInit = PlaybackSourceResolver.clampCodecSupportedTier(cellularTier, effectiveSong)
        val completedDownloadTier =
            org.melodist.data.download.DownloadManager
                .getCompletedDownload(effectiveSong.songMid)
                ?.second
        val shouldBypassCache = policyCoordinator.shouldBypassCache(effectiveSong)
        val initialCachedTier =
            if (shouldBypassCache) {
                null
            } else {
                completedDownloadTier
                    ?: QQMusicCacheManager.findHigherOrEqualStereoCachedTier(effectiveSong.songMid, targetTierInit)
                    ?: if (QQMusicCacheManager.isSongTierCached(effectiveSong.songMid, targetTierInit)) targetTierInit else null
            }
        val isDirectCachedInit =
            !shouldBypassCache &&
                (
                    initialCachedTier != null ||
                        effectiveSong.isLocal ||
                        effectiveSong.songMid.startsWith("local_") ||
                        !effectiveSong.localFilePath.isNullOrBlank()
                )
        _isCurrentTrackFromCache.value = isDirectCachedInit
        _fileCacheFraction.value = if (isDirectCachedInit) 1f else 0f
        val policyTier = policyCoordinator.resolveInitialTier(effectiveSong, targetTierInit)
        _currentTier.value = policyTier ?: (initialCachedTier ?: targetTierInit)

        updateCurrentMediaMetadata(effectiveSong)
        org.melodist.data.RecentPlaybackManager
            .recordSong(effectiveSong)
        _currentTrackSpec.value = null

        val list = queueManager.playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            queueManager.setCurrentIndex(foundIndex)
            if (queueManager.loopMode.value == PlaybackLoopMode.Shuffle) {
                queueManager.shuffleQueue.syncTo(foundIndex, list.size, list)
            }
        }
        queueManager.checkPrefetchRadioSongs()

        _isLoading.value = true
        _errorMessage.value = null
        _currentPositionMs.value = seekToMs

        appContext?.let { ctx ->
            CoverMemoryManager.trimMemoryWindow(
                context = ctx,
                prevSong = getPreviousSong(),
                currSong = song,
                nextSong = getNextSong(),
            )
        }

        qualityCoordinator.resetForSong(song)
        if (!PlaybackSourceResolver.isPureLocalSong(song)) {
            if (policyCoordinator.isQualityProbeAllowed(song)) {
                probeHealer.launchProbeJob(song, delayMs = 3000L)
            }
            probeHealer.launchCoverHealJob(song)
        }

        playJob =
            scope.launch {
                val player = exoPlayer ?: return@launch
                val ctx = appContext ?: return@launch
                val rawTargetTier = forceTier ?: _preferredTier.value
                val cellularTier = clampCellularTier(rawTargetTier, song)
                val clampedTier = PlaybackSourceResolver.clampCodecSupportedTier(cellularTier, song)
                val policyTier = policyCoordinator.resolveInitialTier(song, clampedTier)
                val resolvedTargetTier = PlaybackSourceResolver.clampCodecSupportedTier(policyTier ?: clampedTier, song)
                val isForceTrial = policyCoordinator.isForceTrial(song)
                val bypassPrefetch = policyCoordinator.shouldBypassPrefetch(song)

                val resolveTargetDeferred =
                    async(Dispatchers.IO) {
                        PlaybackMediaLoader.resolveTarget(
                            context = ctx,
                            song = song,
                            targetTier = resolvedTargetTier,
                            apiService = apiService,
                            isSongFavorite = { isSongFavorite(it) },
                            metadataBuilder = { buildMediaMetadata(it) },
                            prefetchedUrlInfo = if (forceTier == null && !bypassPrefetch) preloadCoordinator.prefetchedUrlInfo else null,
                            forceTrial = isForceTrial,
                        )
                    }

                if (player.isPlaying && player.volume > 0f) {
                    val initialVol = player.volume
                    player.volume = initialVol * 0.4f
                    delay(12L)
                    player.volume = 0f
                    delay(8L)
                }
                player.stop()
                player.clearMediaItems()

                val target = resolveTargetDeferred.await()

                // 音量平衡：按曲目 ReplayGain 设置本曲增益（无标签/在线流保持 1.0）
                val enhancementGain = resolveReplayGainGain(target)
                playerPipeline.gainController?.apply {
                    reset()
                    setBaseGain(enhancementGain)
                }

                PlaybackPlayerBinder.bindAndPlay(
                    player = player,
                    target = target,
                    seekToMs = seekToMs,
                    isMuted = _isMuted.value,
                    targetVolume = targetVolume,
                    onPlaybackStarted = { actualTier, isCache, fraction ->
                        _currentTier.value = actualTier
                        _isCurrentTrackFromCache.value = isCache
                        _fileCacheFraction.value = fraction
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                        if (preloadCoordinator.prefetchedUrlInfo?.first == song.songMid) {
                            preloadCoordinator.clearPrefetchedUrlInfo()
                        }
                    },
                    onLocalFilePostAction = {
                        launch(Dispatchers.IO) {
                            try {
                                val rawCover =
                                    org.melodist.data.LocalMusicManager
                                        .ensureRawCover(song)
                                if (!rawCover.isNullOrBlank() && _currentSong.value?.songId == song.songId) {
                                    withContext(Dispatchers.Main) {
                                        val current = _currentSong.value
                                        if (current != null && current.songId == song.songId && current.rawCoverUrl != rawCover) {
                                            val updated =
                                                current.copy(
                                                    rawCoverUrl = rawCover,
                                                    coverUrl = if (current.coverUrl.isBlank()) rawCover else current.coverUrl,
                                                )
                                            _currentSong.value = updated
                                            queueManager.updateSongInPlaylist(updated)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("QQMusicPlayback", "Failed to update song details with raw cover", e)
                            }
                        }
                    },
                    onFailure = { msg, retry ->
                        handlePlaybackFailure(msg, retry)
                    },
                )
            }
    }

    /**
     * 反转当前回放状态（正在播放则暂停，已暂停则恢复播放）。
     */
    fun togglePlayPause() {
        if (playbackInterceptor?.onInterceptTogglePlayPause() == true) return
        val player = exoPlayer ?: return
        val currSong = _currentSong.value

        if (player.currentMediaItem == null && currSong != null) {
            playSong(currSong, seekToMs = _currentPositionMs.value)
            return
        }

        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    /**
     * 暂停当前音频回放并同步状态。
     */
    fun pause() {
        if (playbackInterceptor?.onInterceptPause() == true) return
        val player = exoPlayer ?: return
        player.pause()
        progressTracker.flushActivePlaybackSession()
        savePlaybackProgress(player.currentPosition.coerceAtLeast(0L))
        playbackEventListeners.forEach { it.onPause() }
    }

    /**
     * 结算并触发当前正在追踪的曲目播放有效时长上报。
     */
    fun flushActivePlaybackSession() {
        progressTracker.flushActivePlaybackSession()
    }

    /**
     * 恢复或启动当前音频回放。
     */
    fun play() {
        retryJob?.cancel()
        if (playbackInterceptor?.onInterceptResume() == true) return
        val currSong = _currentSong.value
        if (currSong != null && ListenTogetherManager.isInRoom && isLocalPlaybackSong(currSong)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        val player = exoPlayer ?: return
        if (!player.isPlaying) {
            if (playJob?.isActive == true) {
                player.playWhenReady = true
                appContext?.let { startPlaybackService(it) }
            } else if (player.playbackState == androidx.media3.common.Player.STATE_IDLE && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else if (player.currentMediaItem == null && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else {
                player.volume = if (_isMuted.value) 0f else targetVolume
                player.play()
                appContext?.let { startPlaybackService(it) }
            }
        }
        playbackEventListeners.forEach { it.onPlay() }
    }

    private var targetVolume = 1f

    /**
     * 设置播放器音量大小。
     *
     * @param volume 音量比例（0.0f ~ 1.0f）
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        targetVolume = clamped
        exoPlayer?.volume = if (_isMuted.value) 0f else clamped
    }

    /**
     * 播放自定义直链音频流（支持外部流式播放）。
     *
     * @param song 曲目元数据对象
     * @param streamUrl 音频媒体资源 URL
     * @param headers 自定义 HTTP 请求头映射
     * @param seekToMs 起播跳转位置（毫秒）
     */
    fun playCustomStream(
        song: Song,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        seekToMs: Long = 0L,
    ) {
        if (ListenTogetherManager.isInRoom && isLocalPlaybackSong(song)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        lastCustomStreamArgs = Triple(song, streamUrl, headers)
        stopSilentKeepAlive()
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        QQMusicCacheManager.onNewSongStarted(song.songMid)
        if (_currentSong.value?.songMid != song.songMid) {
            currentSongRetryCount = 0
            audioTrackRetryCount = 0
        }
        consecutiveErrorCount = 0
        playJob?.cancel()
        retryJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false
        cancelEnhancementTransitions()

        _currentSong.value = song
        _isTransitioning.value = true
        appContext?.let { startPlaybackService(it) }
        updateCurrentMediaMetadata(song)
        org.melodist.data.RecentPlaybackManager
            .recordSong(song)
        _currentTrackSpec.value = null

        val list = queueManager.playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            queueManager.setCurrentIndex(foundIndex)
        }

        _isLoading.value = true
        _errorMessage.value = null
        applyLyricsWithChorus(emptyList())
        _currentPositionMs.value = seekToMs

        val player = exoPlayer ?: return
        val baseHttpFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent("QQMusicTV/1.0 ConnectStream")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
        if (headers.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(headers)
        }
        val ctx = appContext ?: return
        val dataSourceFactory = DefaultDataSource.Factory(ctx, baseHttpFactory)
        val mediaSource =
            ProgressiveMediaSource
                .Factory(dataSourceFactory, PlaybackEngineFactory.createExtractorsFactory())
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                .createMediaSource(buildMediaItem(android.net.Uri.parse(streamUrl), song))

        if (seekToMs > 0L) {
            player.setMediaSource(mediaSource, seekToMs)
        } else {
            player.setMediaSource(mediaSource)
        }
        player.volume = if (_isMuted.value) 0f else targetVolume
        player.prepare()
        player.play()
        _isTransitioning.value = false
        _isLoading.value = false
        savePlaybackState()
        loadLyricsForSong(song)
    }

    /**
     * 跳转至当前曲目的指定播放时间位置。
     *
     * @param positionMs 目标绝对时间戳（毫秒）
     */
    fun seekTo(positionMs: Long) {
        if (playbackInterceptor?.onInterceptSeekTo(positionMs) == true) return
        exoPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePlaybackProgress(positionMs)
        playbackEventListeners.forEach { it.onSeekTo(positionMs) }
    }

    /**
     * 同步远端设备推送的实时播放状态（曲目、播放状态、进度、时长与队列拓扑）。
     *
     * @param song 远端当前播放曲目
     * @param isPlaying 远端播放状态
     * @param positionMs 远端播放进度（毫秒）
     * @param durationMs 远端音频总时长（毫秒）
     * @param currentIndex 远端当前索引
     * @param loopModeName 循环模式名称
     * @param prevSong 远端上一首曲目
     * @param nextSong 远端下一首曲目
     * @param currentTier 当前音质级别
     * @param availableTiers 可选音质级别集合
     * @param isRadioMode 是否处于电台模式
     */
    fun syncRemotePlaybackState(
        song: Song?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        currentIndex: Int = -1,
        loopModeName: String? = null,
        prevSong: Song? = null,
        nextSong: Song? = null,
        currentTier: AudioQualityTier? = null,
        availableTiers: Set<AudioQualityTier> = emptySet(),
        isRadioMode: Boolean = false,
        lyricOffsetMs: Long = 0L,
    ) {
        val prevLocalSong = _currentSong.value
        if (song != null) {
            _currentSong.value = song
            if (lyricOffsetMs != 0L) {
                _currentLyricOffsetMs.value = lyricOffsetMs
            } else if (org.melodist.data.LyricCacheManager
                    .hasLyricOffsetRecord(song.songMid)
            ) {
                _currentLyricOffsetMs.value =
                    org.melodist.data.LyricCacheManager
                        .getLyricOffsetMs(song.songMid)
            }
            if (prevLocalSong?.songMid != song.songMid) {
                loadLyricsForSong(song)
            }
            if (currentIndex >= 0 && currentIndex < queueManager.playlist.value.size) {
                queueManager.setCurrentIndex(currentIndex)
            } else {
                val idx = queueManager.playlist.value.indexOfFirst { it.songMid == song.songMid }
                if (idx >= 0) {
                    queueManager.setCurrentIndex(idx)
                }
            }
        }
        remoteStateHolder.setRemotePrevSong(prevSong)
        remoteStateHolder.setRemoteNextSong(nextSong)
        queueManager.setRadioMode(isRadioMode)
        if (currentTier != null) {
            _currentTier.value = currentTier
        }
        if (availableTiers.isNotEmpty()) {
            qualityCoordinator.updateAvailableTiers(availableTiers)
        }
        if (!loopModeName.isNullOrBlank()) {
            try {
                queueManager.setLoopMode(PlaybackLoopMode.valueOf(loopModeName))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w("PlaybackLoopMode", "Operation failed", e)
            }
        }
        if (song != null) {
            _isPlaying.value = isPlaying
            _currentPositionMs.value = positionMs
            remoteStateHolder.updateSyncTimeline(positionMs, isPlaying)
            if (durationMs > 0L) {
                _durationMs.value = durationMs
            }
        }
    }

    /**
     * 接管模式下触发切歌时的本地乐观曲目推进
     */
    fun optimisticSwitchRemoteTrack(
        targetSong: Song,
        isNext: Boolean,
    ) {
        if (!remoteStateHolder.isRemoteActive.value) return
        val current = _currentSong.value
        _currentSong.value = targetSong
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        loadLyricsForSong(targetSong)
        val list = queueManager.playlist.value
        val idx = list.indexOfFirst { it.songMid == targetSong.songMid }
        if (idx >= 0) {
            queueManager.setCurrentIndex(idx)
            val newPrev = if (isNext) current else (if (idx - 1 in list.indices) list[idx - 1] else null)
            val newNext = if (!isNext) current else (if (idx + 1 in list.indices) list[idx + 1] else null)
            remoteStateHolder.setRemotePrevSong(newPrev)
            remoteStateHolder.setRemoteNextSong(newNext)
        } else {
            if (isNext) {
                remoteStateHolder.setRemotePrevSong(current)
                remoteStateHolder.setRemoteNextSong(null)
            } else {
                remoteStateHolder.setRemotePrevSong(null)
                remoteStateHolder.setRemoteNextSong(current)
            }
        }
    }

    /**
     * 同步远端设备推送的播放队列与当前曲目索引。
     *
     * @param queue 远端播放队列
     * @param currentIndex 远端当前索引
     */
    fun syncRemoteQueue(
        queue: List<Song>,
        currentIndex: Int,
    ) {
        if (queue.isNotEmpty()) {
            queueManager.syncRemoteQueue(queue, currentIndex)
        }
    }

    /**
     * 异步为指定歌曲拉取并解析在线或本地匹配歌词。
     *
     * @param song 目标曲目
     */
    fun loadLyricsForSong(song: Song) {
        lyricsCoordinator.loadLyricsForSong(song)
    }

    fun setExternalLyrics(
        songMid: String,
        lyrics: List<LyricLine>,
    ) {
        lyricsCoordinator.setExternalLyrics(
            songMid = songMid,
            lyrics = lyrics,
            currentLyrics = _lyrics.value,
            isCurrentSong = _currentSong.value?.songMid == songMid,
        )
    }

    fun isCellularNetwork(): Boolean = PlaybackSourceResolver.isCellularNetwork(appContext)

    fun isLocalPlaybackSong(song: Song?): Boolean {
        if (song == null) return false
        if (lastCustomStreamArgs != null && _currentSong.value?.songMid == song.songMid) {
            return true
        }
        return PlaybackSourceResolver.isLocalPlaybackSong(song)
    }

    fun isPureLocalSong(song: Song?): Boolean {
        if (song == null) return false
        if (lastCustomStreamArgs != null && _currentSong.value?.songMid == song.songMid) {
            return true
        }
        return PlaybackSourceResolver.isPureLocalSong(song)
    }

    fun getAudioQualityRank(tier: AudioQualityTier): Int = PlaybackSourceResolver.getAudioQualityRank(tier)

    fun clampCellularTier(
        requestedTier: AudioQualityTier,
        song: Song?,
    ): AudioQualityTier =
        PlaybackSourceResolver.clampCellularTier(
            requestedTier = requestedTier,
            song = song,
            context = appContext,
            cellularLimit = org.melodist.data.AppSettingsManager.settings.value.cellularQualityTier,
        )

    fun setPreferredQualityTier(tier: AudioQualityTier) {
        val clamped = PlaybackSourceResolver.clampCodecSupportedTier(tier, _currentSong.value)
        _preferredTier.value = clamped
        org.melodist.data.AppSettingsManager
            .updatePreferredQualityTier(clamped)
        savePlaybackState()
        val current = _currentSong.value
        if (current != null) {
            val effectiveTier = PlaybackSourceResolver.clampCodecSupportedTier(clampCellularTier(clamped, current), current)
            if (_currentTier.value != effectiveTier) {
                switchTier(effectiveTier)
            }
        }
    }

    fun getFallbackTier(current: AudioQualityTier): AudioQualityTier? = PlaybackSourceResolver.getFallbackTier(current)

    /**
     * 切换当前播放歌曲的音质级别并续播。
     *
     * @param tier 目标音质级别
     * @param isAutoFallback 是否因播放故障引发的自动降级（为 true 时不覆盖用户全局首选音质偏好）
     */
    fun switchTier(
        tier: AudioQualityTier,
        isAutoFallback: Boolean = false,
    ) {
        val current = _currentSong.value ?: return
        val interceptionReason = policyCoordinator.evaluateSwitchTierInterception(current, tier)
        if (interceptionReason != null) {
            if (!isAutoFallback) {
                notifyUser(interceptionReason)
            }
            return
        }
        val codecClampedTier = PlaybackSourceResolver.clampCodecSupportedTier(tier, current)
        if (codecClampedTier != tier) {
            _errorMessage.value = "当前设备缺少该音频格式解码器，已回退为：${AudioQualityTier.getBadge(codecClampedTier)}"
        }
        val effectiveTier = clampCellularTier(codecClampedTier, current)
        if (effectiveTier != codecClampedTier) {
            _errorMessage.value = "当前为移动网络，已限制为上限音质：${AudioQualityTier.getBadge(effectiveTier)}"
        }
        val previousTier = _currentTier.value
        if (!isAutoFallback) {
            _preferredTier.value = effectiveTier
            org.melodist.data.AppSettingsManager
                .updatePreferredQualityTier(effectiveTier)
            savePlaybackState()
        }

        if (playbackInterceptor?.onInterceptSwitchTier(effectiveTier) == true) {
            _currentTier.value = effectiveTier
            _isSwitchingQuality.value = false
            return
        }

        if (previousTier == effectiveTier && exoPlayer?.currentMediaItem != null) {
            _isSwitchingQuality.value = false
            return
        }

        val isPlayerPlaying = exoPlayer?.isPlaying == true
        val isPlayerValid = exoPlayer?.currentMediaItem != null

        // 保持当前音频流，后台预加载新音质
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = true

        switchQualityJob =
            scope.launch {
                try {
                    val isTargetFullyCached = QQMusicCacheManager.isSongTierFullyCached(current.songMid, effectiveTier)
                    val playUrlInfo =
                        if (isTargetFullyCached) {
                            Log.i("QQMusicPlayback", "Reusing local complete cache for tier switch: ${current.name} ($effectiveTier)")
                            val dummyUri = "https://cache.qqmusic.internal/${current.songMid}?tier=${effectiveTier.name}"
                            org.melodist.api.QualityResult(
                                url = dummyUri,
                                tier = effectiveTier,
                                badge = AudioQualityTier.getBadge(effectiveTier),
                            )
                        } else {
                            withContext(Dispatchers.IO) {
                                try {
                                    apiService.getPlayUrl(current.songMid, mediaMid = current.mediaMid, preferredTier = effectiveTier)
                                } catch (e: Exception) {
                                    Log.w("QQMusicPlayback", "Failed to fetch play url for quality $effectiveTier: ${e.message}", e)
                                    null
                                }
                            }
                        }

                    // 防竞态检查：若返回时已切歌，则丢弃本次结果
                    if (_currentSong.value?.songMid != current.songMid) {
                        return@launch
                    }

                    val rawUrl = playUrlInfo?.url
                    if (playUrlInfo != null && !rawUrl.isNullOrBlank()) {
                        _currentTier.value = playUrlInfo.tier
                        _currentSong.value = _currentSong.value?.copy(currentTier = playUrlInfo.tier)
                        val isTargetFullyCached = QQMusicCacheManager.isSongTierFullyCached(current.songMid, playUrlInfo.tier)
                        _isCurrentTrackFromCache.value = isTargetFullyCached
                        _fileCacheFraction.value = if (isTargetFullyCached) 1f else 0f
                        val player = exoPlayer ?: return@launch

                        // 在新媒体注入播放器前抓取实时播放进度，保持播放位置连续
                        val livePositionMs =
                            if (isPlayerValid) {
                                player.currentPosition.takeIf { it > 0L } ?: _currentPositionMs.value
                            } else {
                                _currentPositionMs.value
                            }

                        player.volume = if (_isMuted.value) 0f else targetVolume
                        val mediaItem = buildMediaItem(android.net.Uri.parse(rawUrl), current, playUrlInfo.tier)
                        if (livePositionMs > 0L) {
                            player.setMediaItem(mediaItem, livePositionMs)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                        _bufferedPositionMs.value = livePositionMs
                        player.prepare()
                        if (isPlayerPlaying || player.playWhenReady) {
                            player.play()
                        }
                        savePlaybackState()
                        Log.i("QQMusicPlayback", "Seamlessly switched to tier ${playUrlInfo.tier} at position ${livePositionMs}ms")
                    } else {
                        _isSwitchingQuality.value = false
                        val fallback = getFallbackTier(effectiveTier)
                        if (fallback != null && fallback != previousTier) {
                            _errorMessage.value = "该音质不可用，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                            switchTier(fallback, isAutoFallback = true)
                        } else {
                            _errorMessage.value = "该音质不可用，已恢复为 ${AudioQualityTier.getBadge(previousTier)}"
                            _preferredTier.value = previousTier
                            if (_currentTier.value != previousTier && !isPlayerValid) {
                                switchTier(previousTier, isAutoFallback = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    _isSwitchingQuality.value = false
                    Log.e("QQMusicPlayback", "Unexpected error during seamless quality switch to $effectiveTier", e)
                }
            }
    }

    fun ensureQualityProbed() {
        val current = _currentSong.value ?: return
        if (!policyCoordinator.isQualityProbeAllowed(current)) return
        qualityCoordinator.ensureQualityProbed(
            song = _currentSong.value,
            currentSongMidProvider = { _currentSong.value?.songMid },
            currentTierProvider = { _currentTier.value },
            preferredTierProvider = { _preferredTier.value },
            context = appContext,
            onAutoUpgrade = { switchTier(it) },
        )
    }

    /**
     * 播放队列中的下一首曲目。
     */
    fun playNext() {
        if (playbackInterceptor?.onInterceptPlayNext() == true) {
            return
        }
        queueManager.playNext()
    }

    /**
     * 播放队列中的上一首曲目。
     */
    fun playPrevious() {
        if (playbackInterceptor?.onInterceptPlayPrevious() == true) {
            return
        }
        queueManager.playPrevious()
    }

    /**
     * 获取上一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getPreviousSong(): Song? = queueManager.getPreviousSong(remoteStateHolder.isRemoteActive.value, remoteStateHolder.remotePrevSong.value)

    /**
     * 获取下一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getNextSong(): Song? = queueManager.getNextSong(remoteStateHolder.isRemoteActive.value, remoteStateHolder.remoteNextSong.value)

    /**
     * 获取电台模式下的动态展示队列（已播历史 + 当前曲 + 下一首预告）
     */
    fun getRadioDisplayPlaylist(): List<Song> =
        queueManager.getRadioDisplayPlaylist(
            isRemoteActive = remoteStateHolder.isRemoteActive.value,
            remoteNextSong = remoteStateHolder.remoteNextSong.value,
        )

    /**
     * 顺次循环切换播放模式（顺序循环 -> 单曲循环 -> 随机播放）。
     */
    fun cycleLoopMode() {
        if (playbackInterceptor?.onInterceptCycleLoopMode() == true) {
            return
        }
        queueManager.cycleLoopMode()
        prefetchAdjacentCoversAndMetadata()
    }

    /**
     * 设置播放队列的循环模式。
     *
     * @param mode 目标循环模式
     */
    fun setLoopMode(mode: PlaybackLoopMode) {
        queueManager.setLoopMode(mode)
        prefetchAdjacentCoversAndMetadata()
    }

    /**
     * 后台预加载相邻曲目（优先下一首）的原始封面图与歌曲信息。
     */
    fun prefetchAdjacentCoversAndMetadata() = preloadCoordinator.prefetchAdjacentCoversAndMetadata()

    private fun handleSongEnded() {
        if (remoteStateHolder.isRemoteActive.value) {
            Log.d("QQMusicPlayback", "Remote playback is active, skip local song ended auto-advance.")
            return
        }
        if (SleepTimerManager.onSongEnded()) {
            _isTransitioning.value = false
            exoPlayer?.seekTo(0L)
            _currentPositionMs.value = 0L
            pause()
            return
        }
        _isTransitioning.value = true
        when {
            !queueManager.isRadioMode.value && !queueManager.hasPendingNextPlay && queueManager.loopMode.value == PlaybackLoopMode.SingleRepeat -> {
                progressTracker.flushActivePlaybackSession()
                _currentPositionMs.value = 0L
                _bufferedPositionMs.value = 0L
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
                _isTransitioning.value = false
            }

            else -> {
                playNext()
            }
        }
    }

    // ==================== 播放增强（无缝播放 / 淡入淡出 / 音量平衡） ====================

    private fun enhancementEnvironment(hasNextSong: Boolean): PlaybackEnhancementEnvironment {
        val settings = AppSettingsManager.settings.value
        return PlaybackEnhancementEnvironment(
            passthroughActive = settings.enableAudioPassthrough || settings.enableUsbExclusive,
            usbExclusive = settings.enableUsbExclusive,
            remoteActive = remoteStateHolder.isRemoteActive.value,
            listenTogetherActive = ListenTogetherManager.isInRoom,
            radioMode = queueManager.isRadioMode.value,
            singleRepeat = queueManager.loopMode.value == PlaybackLoopMode.SingleRepeat,
            qualitySwitching = _isSwitchingQuality.value || _isTransitioning.value,
            sleepTimerWaitingForSongEnd = SleepTimerManager.isWaitingForSongEnd.value,
            hasNextSong = hasNextSong,
        )
    }

    /**
     * 当前环境下真正生效的播放增强能力。
     *
     * 供 UI 提示“当前场景不支持无缝/淡入淡出”等降级信息，也可用于排查问题。
     */
    fun enhancementState(): PlaybackEnhancementState =
        PlaybackEnhancementPolicy.resolve(
            PlaybackEnhancementConfig.current(),
            enhancementEnvironment(getNextSong() != null),
        )

    /**
     * 播放进度 tick，用于在临近曲尾时触发无缝预入队或淡入淡出交接。
     *
     * @param positionMs 当前播放位置
     * @param durationMs 当前曲目总时长
     */
    private fun onPlaybackProgressTick(
        positionMs: Long,
        durationMs: Long,
    ) {
        if (remoteStateHolder.isRemoteActive.value) return
        if (playJob?.isActive == true) return
        val state = enhancementState()
        when {
            state.crossfade -> maybeStartCrossfade(positionMs, durationMs)
            state.gapless -> maybePrepareGaplessNext(positionMs, durationMs)
        }
    }

    private fun resolveTargetTierFor(song: Song): AudioQualityTier {
        val rawTier = _preferredTier.value
        val cellularTier = clampCellularTier(rawTier, song)
        val clampedTier = PlaybackSourceResolver.clampCodecSupportedTier(cellularTier, song)
        val policyTier = policyCoordinator.resolveInitialTier(song, clampedTier)
        return PlaybackSourceResolver.clampCodecSupportedTier(policyTier ?: clampedTier, song)
    }

    /**
     * 解析下一首的播放目标（与主播放链路共用同一套音质/缓存判定）。
     *
     * @param song 目标曲目
     * @return 解析结果，失败返回 null
     */
    private suspend fun resolveNextTrack(song: Song): PreparedNextTrack? {
        val ctx = appContext ?: return null
        val targetTier = resolveTargetTierFor(song)
        val forceTrial = policyCoordinator.isForceTrial(song)
        val bypassPrefetch = policyCoordinator.shouldBypassPrefetch(song)
        val prefetched = if (bypassPrefetch) null else preloadCoordinator.prefetchedUrlInfo?.takeIf { it.first == song.songMid }
        val target =
            withContext(Dispatchers.IO) {
                PlaybackMediaLoader.resolveTarget(
                    context = ctx,
                    song = song,
                    targetTier = targetTier,
                    apiService = apiService,
                    isSongFavorite = { isSongFavorite(it) },
                    metadataBuilder = { buildMediaMetadata(it) },
                    prefetchedUrlInfo = prefetched,
                    forceTrial = forceTrial,
                )
            }
        if (target is PlaybackTargetResult.Failure) {
            Log.w("QQMusicPlayback", "Enhancement: skip ${song.name}, resolve failed: ${target.message}")
            return null
        }
        return PreparedNextTrack(song = song, target = target, gain = resolveReplayGainGain(target))
    }

    /**
     * 计算某一播放目标应使用的 ReplayGain 线性增益。
     *
     * 只有本地/缓存文件才带 REPLAYGAIN 标签；在线流统一返回 1.0。
     */
    private suspend fun resolveReplayGainGain(target: PlaybackTargetResult): Float {
        if (!enhancementState().replayGain) return 1f
        return when (target) {
            is PlaybackTargetResult.LocalFile ->
                withContext(Dispatchers.IO) { ReplayGainRepository.gainFactorForFile(target.localFile.absolutePath) }

            else -> 1f
        }
    }

    private fun actualTierOf(target: PlaybackTargetResult): AudioQualityTier? =
        when (target) {
            is PlaybackTargetResult.LocalFile -> target.actualTier
            is PlaybackTargetResult.LocalStream -> target.actualTier
            is PlaybackTargetResult.Online -> target.actualTier
            is PlaybackTargetResult.Failure -> null
        }

    // ---------- 无缝播放 ----------

    private fun maybePrepareGaplessNext(
        positionMs: Long,
        durationMs: Long,
    ) {
        if (gaplessPrepared != null) return
        if (gaplessPrepareJob?.isActive == true) return
        // 定时停止/一起听等需要"播完即停"的场景不预入队，否则 ExoPlayer 会自动续播
        if (SleepTimerManager.isTimerActive.value) return
        if (!PlaybackEnhancementPolicy.isInPreloadWindow(positionMs, durationMs)) return
        val current = _currentSong.value ?: return
        val next = getNextSong() ?: return
        if (next.songMid == current.songMid) return
        gaplessPrepareJob =
            scope.launch {
                try {
                    val prepared = resolveNextTrack(next) ?: return@launch
                    val player = exoPlayer ?: return@launch
                    if (gaplessPrepared != null) return@launch
                    enqueuePrepared(player, prepared)
                    gaplessPrepared = prepared
                    Log.i("QQMusicPlayback", "Gapless: pre-enqueued ${prepared.song.name}")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("QQMusicPlayback", "Gapless prepare failed", e)
                }
            }
    }

    private fun enqueuePrepared(
        player: ExoPlayer,
        prepared: PreparedNextTrack,
    ) {
        when (val target = prepared.target) {
            is PlaybackTargetResult.LocalFile -> player.addMediaItem(target.mediaItem)
            is PlaybackTargetResult.LocalStream -> player.addMediaSource(target.mediaSource)
            is PlaybackTargetResult.Online -> player.addMediaSource(target.mediaSource)
            is PlaybackTargetResult.Failure -> Unit
        }
    }

    /**
     * 播放器自动过渡到下一首（无缝播放或淡入淡出交接）后的状态同步。
     *
     * 与 [playSong] 不同：媒体源已经在播放，这里只补齐曲目状态、队列索引、歌词与增益，
     * 避免重新走音源解析造成断点。
     *
     * @param prepared 已开始播放的曲目及其解析结果
     */
    private fun onAutoAdvancedToSong(prepared: PreparedNextTrack) {
        if (SleepTimerManager.onSongEnded()) {
            _isTransitioning.value = false
            exoPlayer?.seekTo(0L)
            _currentPositionMs.value = 0L
            pause()
            return
        }
        val song = prepared.song
        progressTracker.flushActivePlaybackSession()
        queueManager.advanceIndexForAutoTransition(song.songMid)
        QQMusicCacheManager.onNewSongStarted(song.songMid)
        preloadCoordinator.onSongChanged(song.songMid)
        _currentSong.value = song
        actualTierOf(prepared.target)?.let { _currentTier.value = it }
        when (val target = prepared.target) {
            is PlaybackTargetResult.LocalFile -> {
                _isCurrentTrackFromCache.value = true
                _fileCacheFraction.value = 1f
            }

            is PlaybackTargetResult.Online -> {
                _isCurrentTrackFromCache.value = target.isDirectCached
                _fileCacheFraction.value = target.fileCacheFraction
            }

            else -> {
                _isCurrentTrackFromCache.value = false
                _fileCacheFraction.value = 0f
            }
        }
        _currentPositionMs.value = 0L
        _bufferedPositionMs.value = 0L
        _durationMs.value = policyCoordinator.resolveDurationMs(song)
        _isLoading.value = false
        _isTransitioning.value = false
        _lyrics.value = emptyList()
        playerPipeline.gainController?.apply {
            reset()
            setBaseGain(prepared.gain)
        }
        updateCurrentMediaMetadata(song)
        org.melodist.data.RecentPlaybackManager.recordSong(song)
        loadLyricsForSong(song)
        savePlaybackState()
        prefetchAdjacentCoversAndMetadata()
        appContext?.let { ctx ->
            CoverMemoryManager.trimMemoryWindow(ctx, getPreviousSong(), song, getNextSong())
        }
        playbackEventListeners.forEach { it.onSongPlay(song) }
    }

    // ---------- 淡入淡出 ----------

    private fun maybeStartCrossfade(
        positionMs: Long,
        durationMs: Long,
    ) {
        if (crossfadeActive || crossfadeJob?.isActive == true) return
        if (SleepTimerManager.isTimerActive.value) return
        if (durationMs <= 0L) return
        val fadeMs = PlaybackEnhancementConfig.current().crossfadeDurationMs
        if (fadeMs <= 0L) return
        val remaining = durationMs - positionMs
        if (remaining <= PlaybackEnhancementPolicy.CROSSFADE_SAFETY_MARGIN_MS) return
        if (remaining > fadeMs) return
        val current = _currentSong.value ?: return
        val next = getNextSong() ?: return
        if (next.songMid == current.songMid) return

        crossfadeActive = true
        crossfadeJob =
            scope.launch {
                try {
                    val ctx = appContext ?: return@launch
                    val prepared = resolveNextTrack(next) ?: return@launch
                    val slot = playerPipeline.ensureStandbyPlayer(ctx)
                    crossfadePrepared = prepared
                    slot.gain.reset()
                    slot.gain.setBaseGain(0f)
                    PlaybackPlayerBinder.bindAndPlay(
                        player = slot.player,
                        target = prepared.target,
                        seekToMs = 0L,
                        isMuted = _isMuted.value,
                        targetVolume = targetVolume,
                        onPlaybackStarted = { _, _, _ -> },
                        onLocalFilePostAction = {},
                        onFailure = { message, _ ->
                            Log.w("QQMusicPlayback", "Crossfade standby failed: $message")
                            playerPipeline.releaseStandbyPlayer()
                            crossfadePrepared = null
                            crossfadeActive = false
                        },
                    )
                    playerPipeline.gainController?.rampTo(
                        targetGain = 0f,
                        durationMs = fadeMs,
                        curve = PlaybackGainController.Curve.EQUAL_POWER_OUT,
                    )
                    slot.gain.rampTo(
                        targetGain = prepared.gain,
                        durationMs = fadeMs,
                        curve = PlaybackGainController.Curve.EQUAL_POWER_IN,
                    )
                    Log.i("QQMusicPlayback", "Crossfade started (${fadeMs}ms) -> ${prepared.song.name}")
                    delay(fadeMs + 150L)
                    commitCrossfade()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("QQMusicPlayback", "Crossfade aborted", e)
                    crossfadePrepared = null
                    crossfadeActive = false
                    playerPipeline.releaseStandbyPlayer()
                }
            }
    }

    /**
     * 淡入淡出收尾：停掉淡出的旧播放器，把已经起播的备用播放器转为活动播放器。
     */
    private fun commitCrossfade() {
        val prepared = crossfadePrepared
        crossfadePrepared = null
        crossfadeActive = false
        if (prepared == null) {
            playerPipeline.releaseStandbyPlayer()
            return
        }
        val old = exoPlayer
        try {
            old?.stop()
            old?.clearMediaItems()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        playerPipeline.gainController?.reset()
        if (!playerPipeline.swapActiveAndStandby()) {
            playerPipeline.releaseStandbyPlayer()
            return
        }
        playerPipeline.gainController?.finishRamp()
        // MediaSession 绑定的播放器实例已更换，需要重建会话
        onActivePlayerSwappedListener?.invoke()
        playerPipeline.releaseStandbyPlayer()
        onAutoAdvancedToSong(prepared)
    }

    /**
     * 取消所有待完成的无缝/淡入淡出过渡（手动切歌、暂停或设置变更时调用）。
     */
    private fun cancelEnhancementTransitions() {
        gaplessPrepareJob?.cancel()
        gaplessPrepareJob = null
        gaplessPrepared = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadePrepared = null
        crossfadeActive = false
        playerPipeline.releaseStandbyPlayer()
        playerPipeline.gainController?.reset()
    }

    // ---------- 定时停止的淡出收尾 ----------

    /**
     * 睡眠定时到达时的处理：开启淡出收尾则先淡出再暂停。
     *
     * @return true 表示已接管本次停止（[SleepTimerManager] 不再进入"播完当前歌曲"等待态）
     */
    private fun onSleepTimerExpired(): Boolean {
        if (!AppSettingsManager.settings.value.enableSleepTimerFadeOut) return false
        scope.launch {
            val controller = playerPipeline.gainController
            if (controller != null && exoPlayer?.isPlaying == true) {
                controller.rampTo(
                    targetGain = 0f,
                    durationMs = 3_000L,
                    curve = PlaybackGainController.Curve.LINEAR,
                )
                delay(3_000L + 150L)
            }
            pause()
            SleepTimerManager.cancelTimer()
            controller?.reset()
        }
        return true
    }

    /**
     * 释放底层 ExoPlayer 播放器管线、注销硬件音频监听并取消活跃协程任务。
     */
    fun release() {
        appContext?.let { usbRouter.unregister(it) }
        savePlaybackState()
        progressTracker.stop()
        playJob?.cancel()
        preloadCoordinator.release()
        playerPipeline.release()
        _isPlaying.value = false
        _isLoading.value = false
        _isSwitchingQuality.value = false
    }
}

data class AudioTrackSpec(
    val format: String = "",
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val bitrateKbps: Int = 0,
)
