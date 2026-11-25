package org.melodist.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.melodist.data.AppSettingsManager

object PlaybackEngineFactory {
    private const val TAG = "PlaybackEngineFactory"

    fun createRenderersFactory(
        context: Context,
        gainController: PlaybackGainController? = null,
    ): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                val settings = AppSettingsManager.settings.value
                val isExclusive = settings.enableUsbExclusive
                val isPassthrough = settings.enableAudioPassthrough || isExclusive

                @Suppress("DEPRECATION")
                val audioCapabilities =
                    if (isPassthrough) {
                        AudioCapabilities.getCapabilities(context)
                    } else {
                        AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                    }

                val supportsFloatOutput = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                val shouldEnableFloat = (isExclusive || enableFloatOutput) && supportsFloatOutput

                @Suppress("DEPRECATION")
                val builder =
                    DefaultAudioSink
                        .Builder(context)
                        .setAudioCapabilities(audioCapabilities)
                        .setAudioProcessors(buildAudioProcessors(isPassthrough, gainController))
                        .setEnableFloatOutput(shouldEnableFloat)
                        .setEnableAudioTrackPlaybackParams(if (isExclusive) false else enableAudioTrackPlaybackParams)
                return builder.build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
    }

    /**
     * 构造音频处理器链。
     *
     * 直通/独占模式下音频数据由硬件直接输出，处理器链必须为空；
     * 其余模式常驻一个 [androidx.media3.common.audio.GainProcessor]，由
     * [PlaybackGainController] 在运行期调整增益（音量平衡）与斜坡（淡入淡出），
     * 这样开关设置时不需要重建播放器。
     *
     * @param isPassthrough 是否处于音频直通/独占输出
     * @param gainController 增益控制器，为空时不挂载处理器
     */
    private fun buildAudioProcessors(
        isPassthrough: Boolean,
        gainController: PlaybackGainController?,
    ): Array<AudioProcessor> {
        if (isPassthrough || gainController == null) return emptyArray()
        return arrayOf(androidx.media3.common.audio.GainProcessor(gainController))
    }

    /**
     * 构造装配了容错同步与帧对齐能力的全局 ExtractorsFactory。
     */
    fun createExtractorsFactory(): androidx.media3.extractor.ExtractorsFactory =
        androidx.media3.extractor.ExtractorsFactory {
            androidx.media3.extractor
                .DefaultExtractorsFactory()
                .createExtractors()
                .map { extractor ->
                    if (extractor is androidx.media3.extractor.flac.FlacExtractor) {
                        ResilientFlacExtractor(extractor)
                    } else {
                        extractor
                    }
                }.toTypedArray()
        }

    fun buildExoPlayer(
        context: Context,
        listener: Player.Listener,
        analyticsListener: AnalyticsListener? = null,
        gainController: PlaybackGainController? = null,
        handleAudioFocus: Boolean = true,
    ): ExoPlayer {
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                .build()

        val httpDataSourceFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)

        val defaultDataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)
        val cachedDataSourceFactory =
            QQMusicCacheManager.buildCacheDataSourceFactory(context.applicationContext, defaultDataSourceFactory)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(context.applicationContext, createExtractorsFactory())
                .setDataSourceFactory(cachedDataSourceFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        val profile = PlaybackProfile.detect(context)
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    profile.minBufferMs,
                    profile.maxBufferMs,
                    500,
                    1_000,
                ).setPrioritizeTimeOverSizeThresholds(true)
                .build()

        return ExoPlayer
            .Builder(context.applicationContext, createRenderersFactory(context, gainController))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(listener)
                analyticsListener?.let { addAnalyticsListener(it) }
            }
    }
}
