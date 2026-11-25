package org.melodist.playback

import org.melodist.data.AppSettings

/**
 * 播放增强（无缝播放 / 淡入淡出 / 音量平衡）的用户配置快照。
 *
 * 只表达“用户想要什么”，是否真正生效由 [PlaybackEnhancementPolicy] 结合播放环境判定。
 */
data class PlaybackEnhancementConfig(
    val gaplessEnabled: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val replayGainEnabled: Boolean = false,
) {
    /** 淡入淡出是否开启（秒数大于 0 视为开启）。 */
    val crossfadeEnabled: Boolean
        get() = crossfadeSeconds > 0

    /** 淡入淡出时长（毫秒）。 */
    val crossfadeDurationMs: Long
        get() = crossfadeSeconds.coerceIn(0, MAX_CROSSFADE_SECONDS) * 1000L

    companion object {
        const val MIN_CROSSFADE_SECONDS = 1
        const val MAX_CROSSFADE_SECONDS = 15

        val Disabled = PlaybackEnhancementConfig()

        /**
         * 从全局设置读取播放增强配置。
         *
         * @param settings 当前应用设置
         */
        fun fromSettings(settings: AppSettings): PlaybackEnhancementConfig =
            PlaybackEnhancementConfig(
                gaplessEnabled = settings.enableGaplessPlayback,
                crossfadeSeconds =
                    if (settings.enableCrossfade) {
                        settings.crossfadeSeconds.coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS)
                    } else {
                        0
                    },
                replayGainEnabled = settings.enableReplayGain,
            )

        /** 直接读取当前全局设置。 */
        fun current(): PlaybackEnhancementConfig = fromSettings(org.melodist.data.AppSettingsManager.settings.value)
    }
}

/**
 * 影响播放增强是否可用的运行时环境。
 *
 * 任一项命中即按 [PlaybackEnhancementPolicy] 的降级矩阵关掉对应能力，避免出现音频路由冲突、
 * 时间线错乱或与远控/一起听的状态不同步。
 */
data class PlaybackEnhancementEnvironment(
    /** 音频直通（32bit 浮点 / USB 独占）时音频处理器链被绕过。 */
    val passthroughActive: Boolean = false,
    val usbExclusive: Boolean = false,
    /** 远控接管：播放指令来自另一台设备，本地无法预解析下一首。 */
    val remoteActive: Boolean = false,
    /** 一起听：强制同步播放进度，禁止重叠播放。 */
    val listenTogetherActive: Boolean = false,
    /** 电台模式：下一首来自推荐接口，无法提前解析。 */
    val radioMode: Boolean = false,
    /** 单曲循环：无缝可用（重复同一首），淡入淡出不可用。 */
    val singleRepeat: Boolean = false,
    /** 正在切换音质，播放器会被重建。 */
    val qualitySwitching: Boolean = false,
    /** 睡眠定时器处于“播完当前歌曲”等待态。 */
    val sleepTimerWaitingForSongEnd: Boolean = false,
    /** 队列或电台是否还有下一首。 */
    val hasNextSong: Boolean = true,
)

/** 播放增强的实际生效状态。 */
data class PlaybackEnhancementState(
    val gapless: Boolean = false,
    val crossfade: Boolean = false,
    val replayGain: Boolean = false,
) {
    companion object {
        val Disabled = PlaybackEnhancementState()
    }
}

/**
 * 播放增强降级矩阵。
 *
 * 设计原则：宁可退回原生硬切歌，也不要在直通/USB 独占、远控、一起听等场景下偷偷改变音频链路，
 * 否则会出现爆音、双声道错位或与对端进度不同步这类难以复现的问题。
 */
object PlaybackEnhancementPolicy {
    /** 距曲尾多久开始为下一首预解析并入队。 */
    const val GAPLESS_PREROLL_MS = 20_000L

    /** 淡入淡出触发点相对曲尾的额外安全余量。 */
    const val CROSSFADE_SAFETY_MARGIN_MS = 500L

    /**
     * 计算当前环境下的实际生效能力。
     *
     * @param config 用户配置
     * @param env 运行时环境
     */
    fun resolve(
        config: PlaybackEnhancementConfig,
        env: PlaybackEnhancementEnvironment,
    ): PlaybackEnhancementState =
        PlaybackEnhancementState(
            gapless = isGaplessAllowed(config, env),
            crossfade = isCrossfadeAllowed(config, env),
            replayGain = isReplayGainAllowed(config, env),
        )

    /** 无缝播放是否可用。 */
    fun isGaplessAllowed(
        config: PlaybackEnhancementConfig,
        env: PlaybackEnhancementEnvironment,
    ): Boolean =
        config.gaplessEnabled &&
            !env.passthroughActive &&
            !env.usbExclusive &&
            !env.remoteActive &&
            !env.listenTogetherActive &&
            !env.radioMode &&
            !env.qualitySwitching

    /** 淡入淡出是否可用（需同时有下一首，且不处于“播完当前”等待态）。 */
    fun isCrossfadeAllowed(
        config: PlaybackEnhancementConfig,
        env: PlaybackEnhancementEnvironment,
    ): Boolean =
        config.crossfadeEnabled &&
            env.hasNextSong &&
            !env.singleRepeat &&
            !env.passthroughActive &&
            !env.usbExclusive &&
            !env.remoteActive &&
            !env.listenTogetherActive &&
            !env.radioMode &&
            !env.qualitySwitching &&
            !env.sleepTimerWaitingForSongEnd

    /** 音量平衡是否可用（直通/独占时音频处理器链被绕过）。 */
    fun isReplayGainAllowed(
        config: PlaybackEnhancementConfig,
        env: PlaybackEnhancementEnvironment,
    ): Boolean =
        config.replayGainEnabled &&
            !env.passthroughActive &&
            !env.usbExclusive

    /**
     * 判断当前进度是否已进入“可以为下一首做预入队”的窗口。
     *
     * @param positionMs 当前播放位置
     * @param durationMs 当前歌曲总时长
     * @param prerollMs 提前量
     */
    fun isInPreloadWindow(
        positionMs: Long,
        durationMs: Long,
        prerollMs: Long = GAPLESS_PREROLL_MS,
    ): Boolean {
        if (durationMs <= 0L || positionMs < 0L) return false
        val remaining = durationMs - positionMs
        return remaining in 1..prerollMs
    }
}
