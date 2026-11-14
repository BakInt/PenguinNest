package org.melodist.playback

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.QualityResult
import org.melodist.api.getPlayUrl
import org.melodist.data.LocalMusicManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放资源与相邻曲目预加载协调器。
 */
class PlaybackPreloadCoordinator(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val queueManager: PlaybackQueueManager,
    private val lyricsCoordinator: PlaybackLyricsCoordinator,
    private val getContext: () -> Context?,
    private val getCurrentSong: () -> Song?,
    private val getPreferredTier: () -> AudioQualityTier,
    private val clampCellularTier: (AudioQualityTier, Song) -> AudioQualityTier,
    private val getPreviousSong: () -> Song?,
    private val getNextSong: () -> Song?,
) {
    private var prefetchJob: Job? = null
    private var prefetchedSongMid: String? = null
    var prefetchedUrlInfo: Pair<String, QualityResult>? = null
        private set

    private val prefetchingTargetMids = ConcurrentHashMap.newKeySet<String>()

    /**
     * 重置已缓存的预取链接信息。
     */
    fun clearPrefetchedUrlInfo() {
        prefetchedUrlInfo = null
        prefetchedSongMid = null
    }

    /**
     * 取消当前正在执行的预取任务。
     */
    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /**
     * 响应播放曲目切换，清理不匹配的预取任务与缓存。
     *
     * @param newSongMid 新曲目标识
     */
    fun onSongChanged(newSongMid: String) {
        if (prefetchedSongMid != newSongMid) {
            cancelPrefetch()
            clearPrefetchedUrlInfo()
        }
    }

    /**
     * 释放所有预取资源并取消活跃任务。
     */
    fun release() {
        cancelPrefetch()
        clearPrefetchedUrlInfo()
    }

    /**
     * 触发下一首歌曲的预拉取（包括歌词、封面及网络播放直链）。
     */
    fun triggerPrefetchNextSong() {
        if (prefetchJob?.isActive == true) return
        val nextSong = getNextSong() ?: return
        val nextMid = nextSong.songMid
        if (nextMid == prefetchedSongMid) return
        prefetchedSongMid = nextMid

        val ctx = getContext() ?: return
        prefetchJob =
            scope.launch(Dispatchers.IO) {
                // 预取下一首歌词至磁盘缓存
                lyricsCoordinator.prefetchLyrics(nextSong)

                // 预取封面至 Coil 磁盘缓存
                val coverUrl = nextSong.coverUrl
                if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                    try {
                        val imageLoader = SingletonImageLoader.get(ctx)
                        val req =
                            ImageRequest
                                .Builder(ctx)
                                .data(coverUrl)
                                .build()
                        imageLoader.enqueue(req)
                        Log.d("QQMusicPlayback", "Prefetched next song cover: ${nextSong.name}")
                    } catch (e: Exception) {
                        Log.w("QQMusicPlayback", "Failed to prefetch cover for ${nextSong.name}", e)
                    }
                }

                // 本地纯文件歌曲：无需预取在线播放数据
                val isPureLocal = !nextSong.localFilePath.isNullOrBlank()
                if (isPureLocal) return@launch

                // 在线歌曲：若本地已有完整缓存，直接跳过网络预取
                val rawTargetTier = getPreferredTier()
                val targetTier =
                    PlaybackSourceResolver.clampCodecSupportedTier(clampCellularTier(rawTargetTier, nextSong), nextSong)
                val higherStereoTier = QQMusicCacheManager.findHigherOrEqualStereoCachedTier(nextSong.songMid, targetTier)
                if (higherStereoTier != null) {
                    Log.d("QQMusicPlayback", "Next song already cached locally ($higherStereoTier) for ${nextSong.name}, skip prefetch")
                    return@launch
                }

                // 在线歌曲：预取下一曲播放直链 URL
                try {
                    val playUrlInfo = apiService.getPlayUrl(nextSong.songMid, mediaMid = nextSong.mediaMid, preferredTier = targetTier)
                    val playUrl = playUrlInfo.url
                    if (!playUrl.isNullOrBlank()) {
                        prefetchedUrlInfo = Pair(nextSong.songMid, playUrlInfo)
                        Log.i("QQMusicPlayback", "Prefetched next song URL: ${nextSong.name}, tier: ${playUrlInfo.tier}")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("QQMusicPlayback", "Prefetch next song failed for ${nextSong.name}", e)
                }
            }
    }

    /**
     * 后台预加载相邻曲目的原始封面图与歌曲元数据。
     */
    fun prefetchAdjacentCoversAndMetadata() {
        val queueTargets = queueManager.getAdjacentSongs(countAhead = 2, countBehind = 1)
        val next = getNextSong()
        val prev = getPreviousSong()
        val targets = (queueTargets + listOfNotNull(next, prev)).distinctBy { it.songMid }
        if (targets.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            for (target in targets) {
                launch {
                    lyricsCoordinator.prefetchLyrics(target)
                }
                if (!prefetchingTargetMids.add(target.songMid)) continue
                try {
                    val isLocal = target.isLocal || !target.localFilePath.isNullOrBlank() || target.songMid.startsWith("local_")

                    if (isLocal) {
                        val rawCover = LocalMusicManager.ensureRawCover(target)
                        if (!rawCover.isNullOrBlank() && rawCover != target.rawCoverUrl) {
                            withContext(Dispatchers.Main) {
                                queueManager.updateSongInPlaylist(target.copy(rawCoverUrl = rawCover))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("QQMusicPlayback", "Error prefetching cover and metadata for ${target.name}", e)
                } finally {
                    prefetchingTargetMids.remove(target.songMid)
                }
            }
            val ctx = getContext()
            if (ctx != null) {
                CoverMemoryManager.trimMemoryWindow(
                    context = ctx,
                    prevSong = prev,
                    currSong = getCurrentSong(),
                    nextSong = next,
                )
            }
        }
    }
}
