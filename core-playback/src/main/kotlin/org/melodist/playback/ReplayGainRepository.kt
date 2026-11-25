package org.melodist.playback

import android.util.Log
import org.melodist.api.AudioMetadataParser
import org.melodist.api.ParsedAudioMetadata
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 音量平衡（ReplayGain）增益计算。
 *
 * 只使用文件内写入的 `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_TRACK_PEAK` 标签，不做事后响度估算：
 * 估算会让同一首歌在不同时间听到不同音量，用户很难预期。
 */
object ReplayGainCalculator {
    /** 预增益（dB），默认 0，保留给后续“增益补偿”开关。 */
    const val PREAMP_DB = 0f

    /**
     * 依据标签计算线性增益倍数。
     *
     * @param metadata 解析出的音频元数据
     * @return 线性增益，无标签时返回 1.0（原始音量）
     */
    fun gainFactor(metadata: ParsedAudioMetadata): Float {
        val trackDb = metadata.replayGainTrackDb ?: return 1f
        var gain = PlaybackGainController.dbToGain((trackDb + PREAMP_DB).toFloat())

        // 峰值限制：GainProcessor 之后没有限幅器，增益×峰值超过 1.0 会直接削波
        val peak = metadata.replayGainTrackPeak ?: metadata.replayGainAlbumPeak
        if (peak != null && peak > 0.0) {
            val peakLimit = (1.0 / peak).toFloat()
            if (gain > peakLimit) {
                gain = peakLimit.coerceIn(PlaybackGainController.MIN_GAIN, PlaybackGainController.MAX_GAIN)
            }
        }
        return gain
    }
}

/**
 * 本地音频文件的 ReplayGain 增益查询（带 LRU 缓存）。
 *
 * 只读文件头部标签区：FLAC 的 VORBIS_COMMENT、MP3 的 ID3v2 都位于文件前部，
 * 因此不必把整首无损音频读进内存即可拿到增益标签。
 */
object ReplayGainRepository {
    private const val TAG = "ReplayGainRepository"

    /** 头部读取上限：足够覆盖常见 ID3v2（含封面）+ FLAC 元数据块。 */
    private const val HEADER_READ_LIMIT = 512 * 1024

    private const val CACHE_CAPACITY = 128

    private val cache =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Float>(CACHE_CAPACITY, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > CACHE_CAPACITY
            },
        )

    /**
     * 查询本地文件的 ReplayGain 增益。
     *
     * 该方法会做文件 I/O，必须在 IO 线程调用。
     *
     * @param path 本地音频文件绝对路径
     * @return 线性增益，无标签或读取失败时返回 1.0
     */
    fun gainFactorForFile(path: String?): Float {
        if (path.isNullOrBlank()) return 1f
        cache[path]?.let { return it }

        val gain =
            try {
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) {
                    1f
                } else {
                    val header = readHeader(file)
                    if (header.size < 16) 1f else ReplayGainCalculator.gainFactor(AudioMetadataParser.parse(header))
                }
            } catch (e: Exception) {
                Log.w(TAG, "read replay gain failed: ${e.message}")
                1f
            }
        cache[path] = gain
        return gain
    }

    /** 清空缓存（设置开关或清理缓存后调用）。 */
    fun clearCache() {
        cache.clear()
    }

    private fun readHeader(file: File): ByteArray {
        FileInputStream(file).use { input ->
            val limit = minOf(HEADER_READ_LIMIT.toLong(), file.length()).toInt()
            val buffer = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val count = input.read(buffer, read, limit - read)
                if (count <= 0) break
                read += count
            }
            return if (read == limit) buffer else buffer.copyOf(read)
        }
    }
}
