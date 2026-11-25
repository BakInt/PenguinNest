package org.melodist.playback

import android.os.SystemClock
import androidx.media3.common.audio.GainProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 单个播放器的增益控制器。
 *
 * 通过 Media3 的 [GainProcessor] 作用在音频处理器链上，与 `Player.volume` 解耦：
 * - 音量平衡（ReplayGain）：把整首歌的基准增益 [setBaseGain] 写进处理器；
 * - 淡入淡出：在同一处理器上叠加一段等功率斜坡 [rampTo]，两首歌分别由各自的控制器承担淡出/淡入，
 *   因此不需要在播放器之间同步采样位置。
 *
 * 斜坡按墙钟时间推进（音频线程每个缓冲回调一次），与采样率、是否跨曲目无关；
 * 播放被暂停时斜坡仍会走完，这对“曲尾淡出 / 曲首淡入”这类短斜坡是可以接受的取舍。
 */
class PlaybackGainController : GainProcessor.GainProvider {
    /** 斜坡曲线。 */
    enum class Curve {
        /** 线性插值，用于“无感”微调。 */
        LINEAR,

        /** 等功率淡出（1 → 0），淡出方与淡入方功率和为 1，听感不塌陷。 */
        EQUAL_POWER_OUT,

        /** 等功率淡入（0 → 1）。 */
        EQUAL_POWER_IN,
    }

    @Volatile
    private var baseGain: Float = 1f

    @Volatile
    private var rampFrom: Float = 1f

    @Volatile
    private var rampTo: Float = 1f

    @Volatile
    private var rampStartMs: Long = 0L

    @Volatile
    private var rampDurationMs: Long = 0L

    @Volatile
    private var rampCurve: Curve = Curve.LINEAR

    /** 当前基准增益（不含正在进行的斜坡）。 */
    val currentBaseGain: Float
        get() = baseGain

    /** 是否处于斜坡过程中。 */
    val isRamping: Boolean
        get() = rampDurationMs > 0L

    /**
     * 设置基准增益，并立即结束正在进行的斜坡。
     *
     * @param gain 线性增益倍数，1.0 表示原始音量
     */
    fun setBaseGain(gain: Float) {
        val safe = sanitizeGain(gain)
        baseGain = safe
        rampFrom = safe
        rampTo = safe
        rampDurationMs = 0L
    }

    /**
     * 从当前基准增益斜坡到目标增益。
     *
     * @param targetGain 目标线性增益
     * @param durationMs 斜坡时长（毫秒），小于等于 0 时立即生效
     * @param curve 曲线类型
     */
    fun rampTo(
        targetGain: Float,
        durationMs: Long,
        curve: Curve = Curve.LINEAR,
    ) {
        val safeTarget = sanitizeGain(targetGain)
        if (durationMs <= 0L) {
            setBaseGain(safeTarget)
            return
        }
        rampFrom = baseGain
        rampTo = safeTarget
        rampCurve = curve
        rampStartMs = SystemClock.elapsedRealtime()
        rampDurationMs = durationMs
    }

    /** 结束斜坡并把基准增益固定在目标值。 */
    fun finishRamp() {
        if (rampDurationMs > 0L) {
            baseGain = rampTo
            rampFrom = rampTo
            rampDurationMs = 0L
        }
    }

    /** 复位为 1.0（原始音量）。 */
    fun reset() {
        rampCurve = Curve.LINEAR
        setBaseGain(1f)
    }

    override fun getGainFactorAtSamplePosition(
        samplePosition: Long,
        sampleRate: Int,
    ): Float {
        val duration = rampDurationMs
        if (duration <= 0L) return baseGain

        val elapsed = SystemClock.elapsedRealtime() - rampStartMs
        if (elapsed >= duration) {
            baseGain = rampTo
            rampFrom = rampTo
            rampDurationMs = 0L
            return rampTo
        }

        val progress = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return when (rampCurve) {
            Curve.LINEAR -> rampFrom + (rampTo - rampFrom) * progress
            Curve.EQUAL_POWER_OUT -> cos(progress * (PI / 2).toFloat())
            Curve.EQUAL_POWER_IN -> sin(progress * (PI / 2).toFloat())
        }.coerceIn(0f, MAX_GAIN)
    }

    override fun isUnityUntil(
        samplePosition: Long,
        sampleRate: Int,
    ): Long = if (rampDurationMs <= 0L && baseGain == 1f) Long.MAX_VALUE else samplePosition

    private fun sanitizeGain(gain: Float): Float =
        if (gain.isNaN() || gain.isInfinite()) 1f else gain.coerceIn(0f, MAX_GAIN)

    companion object {
        /** 增益上限（约 +12dB），避免异常标签把音量推到爆音。 */
        const val MAX_GAIN = 4f

        /** 增益下限（约 -20dB）。 */
        const val MIN_GAIN = 0.1f

        /** 主播放器使用的控制器。 */
        val primary = PlaybackGainController()

        /**
         * 把 dB 值转换成线性增益倍数。
         *
         * @param gainDb ReplayGain 标签中的 dB 值
         */
        fun dbToGain(gainDb: Float): Float =
            if (gainDb.isNaN() || gainDb.isInfinite()) {
                1f
            } else {
                10.0
                    .pow(gainDb / 20.0)
                    .toFloat()
                    .coerceIn(MIN_GAIN, MAX_GAIN)
            }
    }
}
