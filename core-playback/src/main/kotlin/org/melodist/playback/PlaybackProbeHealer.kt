package org.melodist.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSongVisualMid
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 音频音质探测与元数据/封面异步自愈调度器。
 */
class PlaybackProbeHealer(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val qualityCoordinator: AudioQualityCoordinator,
    private val queueManager: PlaybackQueueManager,
    private val getContext: () -> Context?,
    private val getCurrentSong: () -> Song?,
    private val setCurrentSong: (Song) -> Unit,
    private val getCurrentTier: () -> AudioQualityTier,
    private val setCurrentTier: (AudioQualityTier) -> Unit,
    private val getPreferredTier: () -> AudioQualityTier,
    private val getCurrentTrackSpec: () -> AudioTrackSpec?,
    private val setCurrentTrackSpec: (AudioTrackSpec) -> Unit,
    private val onSwitchTierRequest: (AudioQualityTier) -> Unit,
) {
    /**
     * 触发异步音质嗅探任务。
     *
     * @param song 目标曲目实体
     * @param delayMs 延迟执行毫秒数
     */
    fun launchProbeJob(
        song: Song,
        delayMs: Long,
    ) {
        qualityCoordinator.launchProbeJob(
            song = song,
            delayMs = delayMs,
            currentSongMidProvider = { getCurrentSong()?.songMid },
            currentTierProvider = { getCurrentTier() },
            preferredTierProvider = { getPreferredTier() },
            context = getContext(),
            onTrackSpecBitrateCalculated = { bitrate ->
                val curSpec = getCurrentTrackSpec()
                if (curSpec != null && curSpec.bitrateKbps <= 0) {
                    setCurrentTrackSpec(curSpec.copy(bitrateKbps = bitrate))
                }
            },
            onAutoUpgrade = { onSwitchTierRequest(it) },
        )
    }

    /**
     * 针对缺失封面的在线歌曲触发单曲封面异步修复。
     *
     * @param song 目标曲目实体
     */
    fun launchCoverHealJob(song: Song) {
        if (song.coverUrl.isNotBlank() || song.songMid.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val vsMid = apiService.getSongVisualMid(song.songMid)
                if (!vsMid.isNullOrBlank()) {
                    val singleCover = MusicApiService.getSingleCoverUrl(vsMid)
                    val healed = song.copy(visualMid = vsMid, coverUrl = singleCover)
                    withContext(Dispatchers.Main) {
                        if (getCurrentSong()?.songMid == song.songMid) {
                            setCurrentSong(healed)
                        }
                        queueManager.updateSongInPlaylist(healed)
                    }
                }
            } catch (e: Exception) {
                Log.w("QQMusicPlayback", "Self-heal single cover failed", e)
            }
        }
    }
}
