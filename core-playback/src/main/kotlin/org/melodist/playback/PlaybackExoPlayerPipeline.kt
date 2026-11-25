package org.melodist.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import org.melodist.data.AppSettingsManager

/**
 * 负责 ExoPlayer 实例的生命周期、USB DAC 独占音频路由与管道重置。
 *
 * 为支持淡入淡出（两首歌重叠播放），管道同时持有两个播放器槽位：
 * - 活动槽位（[player]）承接会话控制、进度上报与状态回调；
 * - 备用槽位（[standbyPlayer]）在交接时用于起播下一首，交接完成后两者角色互换。
 *
 * 每个槽位都有独立的 [PlaybackGainController]，音频处理器链在创建播放器时绑定，
 * 因此交接后调用方必须通过 [gainController] 动态取当前活动槽位的控制器。
 */
class PlaybackExoPlayerPipeline(
    private val getContext: () -> Context?,
    private val usbRouter: UsbAudioRouter,
    private val playerListener: androidx.media3.common.Player.Listener,
    private val onPlayerCreated: (ExoPlayer) -> Unit,
) {
    /** 播放器槽位：播放器实例与它专属的增益控制器。 */
    class Slot(
        val player: ExoPlayer,
        val gain: PlaybackGainController,
    )

    @Volatile
    private var activeSlot: Slot? = null

    @Volatile
    private var standbySlot: Slot? = null

    val player: ExoPlayer?
        get() = activeSlot?.player

    /** 当前活动播放器的增益控制器（淡入淡出/音量平衡都作用在它上面）。 */
    val gainController: PlaybackGainController?
        get() = activeSlot?.gain

    val standbyPlayer: ExoPlayer?
        get() = standbySlot?.player

    val standbyGainController: PlaybackGainController?
        get() = standbySlot?.gain

    private val analyticsListener =
        object : AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                Log.i(
                    "QQMusicPlayback",
                    "AudioTrack initialized: rate=${audioTrackConfig.sampleRate}, enc=${audioTrackConfig.encoding}, ch=${audioTrackConfig.channelConfig}, offload=${audioTrackConfig.offload}",
                )
            }
        }

    /**
     * 构建活动播放器；已有活动播放器时直接返回它。
     *
     * @param context 上下文
     */
    fun buildExoPlayer(context: Context): ExoPlayer {
        activeSlot?.let { return it.player }
        val slot = createSlot(context, attachListener = true, handleAudioFocus = true)
        activeSlot = slot
        onPlayerCreated(slot.player)
        return slot.player
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer = buildExoPlayer(context)

    /**
     * 确保备用播放器存在（淡入淡出交接用）。
     *
     * 备用播放器不参与音频焦点竞争（由活动播放器持有焦点），也不接收状态回调，
     * 只有在交接时才会挂上监听器。
     *
     * @param context 上下文
     */
    fun ensureStandbyPlayer(context: Context): Slot {
        standbySlot?.let { return it }
        val slot = createSlot(context, attachListener = false, handleAudioFocus = false)
        standbySlot = slot
        Log.i("QQMusicPlayback", "Standby player created for crossfade")
        return slot
    }

    /**
     * 交换活动与备用槽位。
     *
     * 调用方需保证新活动播放器已经在播放、旧活动播放器已淡出到静音并停止。
     *
     * @return 交换是否成功
     */
    fun swapActiveAndStandby(): Boolean {
        val active = activeSlot ?: return false
        val standby = standbySlot ?: return false
        active.player.removeListener(playerListener)
        standby.player.addListener(playerListener)
        activeSlot = standby
        standbySlot = active
        updateUsbExclusiveRouting()
        Log.i("QQMusicPlayback", "Player slots swapped for crossfade")
        return true
    }

    /** 释放备用播放器（关闭淡入淡出或重置管道时调用）。 */
    fun releaseStandbyPlayer() {
        standbySlot?.player?.removeListener(playerListener)
        standbySlot?.player?.release()
        standbySlot = null
    }

    fun resetPlayerPipeline(restoreMediaItem: Boolean = true) {
        val context = getContext() ?: return
        val p = activeSlot?.player
        val pos = p?.currentPosition ?: 0L
        val wasPlaying = p?.isPlaying == true
        val item = p?.currentMediaItem

        releaseStandbyPlayer()
        p?.release()
        activeSlot = null
        val slot = createSlot(context, attachListener = true, handleAudioFocus = true)
        activeSlot = slot
        slot.player.apply {
            if (restoreMediaItem && item != null) {
                setMediaItem(item, pos)
                prepare()
                if (wasPlaying) {
                    play()
                }
            }
        }
        onPlayerCreated(slot.player)
        updateUsbExclusiveRouting()
        Log.i("QQMusicPlayback", "Player pipeline reset with updated audio configuration (restoreMediaItem=$restoreMediaItem)")
    }

    fun reloadAudioPipeline() {
        val p = player ?: return
        val pos = p.currentPosition
        val wasPlaying = p.isPlaying
        val item = p.currentMediaItem ?: return
        p.setMediaItem(item, pos)
        p.prepare()
        if (wasPlaying) {
            p.play()
        }
        Log.i("QQMusicPlayback", "Audio pipeline reloaded at position: $pos ms (playing: $wasPlaying)")
    }

    fun updateUsbExclusiveRouting(
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) {
        usbRouter.updateUsbExclusiveRouting(
            context = getContext(),
            player = player,
            isUsbExclusive = AppSettingsManager.settings.value.enableUsbExclusive,
            targetRate = targetRate,
            channelCount = channelCount,
            pcmEncoding = pcmEncoding,
        )
    }

    fun release() {
        releaseStandbyPlayer()
        activeSlot?.player?.release()
        activeSlot = null
    }

    private fun createSlot(
        context: Context,
        attachListener: Boolean,
        handleAudioFocus: Boolean,
    ): Slot {
        val gain = PlaybackGainController()
        val exoPlayer =
            PlaybackEngineFactory.buildExoPlayer(
                context = context,
                listener = if (attachListener) playerListener else NoOpPlayerListener,
                analyticsListener = analyticsListener,
                gainController = gain,
                handleAudioFocus = handleAudioFocus,
            )
        updateUsbExclusiveRouting()
        return Slot(exoPlayer, gain)
    }

    private object NoOpPlayerListener : Player.Listener
}
