package org.melodist.data.acr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Android TV 麦克风录音管理器
 * 基于 AudioRecord 实现 16000Hz 单声道 PCM 采集、动态 RMS 计算与音频前处理 (DC去除 / 预加重 / AGC)
 */
class AudioRecordingManager {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // stop() 等待读取线程退出的上限；超时后不释放录音器，改由读取线程退出时自行释放
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }

    // 读取线程会不加锁读取该引用，且 stop() 会并发写 null，故需 volatile 保证可见性
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    private val pcmBuffer = mutableListOf<Short>()
    private val lock = Any()

    private val _audioEnergy = MutableStateFlow(0f)
    val audioEnergy: StateFlow<Float> = _audioEnergy.asStateFlow()

    val isRunning: Boolean
        get() = isRecording.get()

    /**
     * 开始采集音频。
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lock) {
            if (isRecording.get()) return true

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = max(minBufferSize, 4096)

            var initializedRecord: AudioRecord? = null

            val audioSources =
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                )

            for (source in audioSources) {
                try {
                    val record =
                        AudioRecord(
                            source,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize,
                        )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = record
                        android.util.Log.i(
                            "AudioRecordingManager",
                            "AudioRecord initialized successfully with source=$source, bufferSize=$bufferSize",
                        )
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AudioRecordingManager", "AudioRecord init attempt failed for source=$source: ${e.message}")
                }
            }

            if (initializedRecord == null) {
                android.util.Log.e("AudioRecordingManager", "AudioRecord initialization failed on all sources!")
                return false
            }

            try {
                audioRecord = initializedRecord
                pcmBuffer.clear()
                audioRecord?.startRecording()
                isRecording.set(true)

                recordingThread =
                    Thread({ recordLoop(bufferSize) }, "MicRecordThread").apply {
                        isDaemon = true
                        start()
                    }

                return true
            } catch (e: Exception) {
                android.util.Log.e("AudioRecordingManager", "AudioRecord start failed: ${e.message}", e)
                releaseRecordLocked(initializedRecord)
                isRecording.set(false)
                return false
            }
        }
    }

    private fun recordLoop(bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        var activeRecord: AudioRecord? = null
        try {
            while (isRecording.get()) {
                val record = audioRecord ?: break
                activeRecord = record
                // read() 必须在 try 内：release 与 read 并发时会抛 IllegalStateException，
                // 异常一旦逃出该线程就是进程级崩溃（FATAL EXCEPTION: MicRecordThread）
                val readCount =
                    try {
                        record.read(shortBuffer, 0, shortBuffer.size)
                    } catch (e: Exception) {
                        if (isRecording.get()) {
                            android.util.Log.e("AudioRecordingManager", "AudioRecord.read failed: ${e.message}", e)
                        }
                        break
                    }
                if (readCount > 0) {
                    var sumSquares = 0L
                    synchronized(lock) {
                        for (i in 0 until readCount) {
                            val s = shortBuffer[i]
                            pcmBuffer.add(s)
                            sumSquares += s.toLong() * s.toLong()
                        }
                    }

                    val rms = sqrt((sumSquares.toDouble() / readCount))
                    val normalizedEnergy = (rms / 2500.0).coerceIn(0.0, 1.0).toFloat()
                    _audioEnergy.value = normalizedEnergy
                } else if (readCount < 0) {
                    if (isRecording.get()) {
                        android.util.Log.e("AudioRecordingManager", "AudioRecord.read error: $readCount")
                    }
                    break
                }
            }
        } finally {
            // 线程退出后由自己释放：stop() 若在 join 超时后直接 release，
            // 就可能与仍在阻塞的 read() 竞争，导致 native 崩溃
            releaseRecordLocked(activeRecord)
        }
    }

    /**
     * 释放录音器。仅在线程安全时调用（读取线程已退出），且只释放期望的那个实例，
     * 避免上一轮的延迟释放误伤新一轮已启动的录音器。
     */
    private fun releaseRecordLocked(expected: AudioRecord?) {
        synchronized(lock) {
            val record = audioRecord
            if (record == null || record !== expected) return
            audioRecord = null
            try {
                record.release()
            } catch (e: Exception) {
                Log.w("AudioRecordingManager", "AudioRecord release failed", e)
            }
        }
    }

    /**
     * 检查最近 0.5 秒内是否存在有效声音信号 (RMS > 20)
     */
    fun hasMeaningfulSignal(): Boolean {
        synchronized(lock) {
            val total = pcmBuffer.size
            val windowSamples = 8000 // 0.5s @ 16000Hz
            val start = max(0, total - windowSamples)
            val count = total - start
            if (count <= 0) return false

            var sumSquares = 0L
            for (i in start until total) {
                val s = pcmBuffer[i].toLong()
                sumSquares += s * s
            }
            val rms = sqrt(sumSquares.toDouble() / count)
            return rms > 20.0
        }
    }

    /**
     * 获取当前累积并经过高通滤波、FIR 预加重和双曲正切 AGC 增益处理后的 16000Hz PCM 采样切片
     */
    fun getProcessedSnapshotSamples(): ShortArray {
        synchronized(lock) {
            val size = pcmBuffer.size
            if (size == 0) return ShortArray(0)

            val rawSamples = ShortArray(size)
            for (i in 0 until size) {
                rawSamples[i] = pcmBuffer[i]
            }

            if (size <= 1) return rawSamples

            // DC 偏置去除
            var sum = 0L
            for (s in rawSamples) sum += s
            val dcOffset = (sum / size).toInt()

            // 120Hz 高通滤波 (滤除低频机身震动与环境手震杂音)
            // y[n] = alpha * (y[n-1] + x[n] - x[n-1]), alpha = 0.953 @ 16kHz
            val hpAlpha = 0.953f
            val hpFiltered = FloatArray(size)
            var prevX = (rawSamples[0].toInt() - dcOffset).toFloat()
            var prevY = 0f
            hpFiltered[0] = prevX

            for (i in 1 until size) {
                val curX = (rawSamples[i].toInt() - dcOffset).toFloat()
                val curY = hpAlpha * (prevY + curX - prevX)
                hpFiltered[i] = curY
                prevX = curX
                prevY = curY
            }

            // 标准一阶 FIR 预加重：y[n] = x[n] - preAlpha * x[n-1] (preAlpha = 0.93)
            val preAlpha = 0.93f
            val preEmphasized = FloatArray(size)
            preEmphasized[0] = hpFiltered[0]
            for (i in 1 until size) {
                preEmphasized[i] = hpFiltered[i] - preAlpha * hpFiltered[i - 1]
            }

            // AGC 增益与限幅计算
            val agcWindow = 24000
            val agcStart = max(0, size - agcWindow)
            val agcCount = size - agcStart
            var sumSquares = 0.0
            for (i in agcStart until size) {
                val s = preEmphasized[i].toDouble()
                sumSquares += s * s
            }
            val rms = if (agcCount > 0) sqrt(sumSquares / agcCount) else 0.0

            val gain =
                when {
                    rms >= 15.0 && rms < 2800.0 -> (3600.0 / rms).toFloat().coerceIn(1.0f, 14.0f)
                    else -> 1.0f
                }

            val result = ShortArray(size)
            for (i in 0 until size) {
                val amplified = preEmphasized[i] * gain
                // 双曲正切软限幅：tanh(x / 32767.0) * 32767.0，消除硬削顶破音
                val normalized = amplified / 32767.0
                val softClipped =
                    if (kotlin.math.abs(normalized) > 0.85) {
                        kotlin.math.tanh(normalized) * 32767.0
                    } else {
                        amplified.toDouble()
                    }
                result[i] = softClipped.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            return result
        }
    }

    /**
     * 停止录音并释放资源
     */
    fun stop() {
        isRecording.set(false)
        _audioEnergy.value = 0f

        val record = audioRecord
        try {
            // stop() 是让阻塞中的 read() 尽快返回的官方手段
            record?.stop()
        } catch (e: Exception) {
            Log.w("AudioRecordingManager", "Operation failed", e)
        }

        val thread = recordingThread
        recordingThread = null
        val threadFinished =
            try {
                if (thread == null) {
                    true
                } else {
                    if (thread.isAlive) thread.join(STOP_JOIN_TIMEOUT_MS)
                    !thread.isAlive
                }
            } catch (e: Exception) {
                Log.w("AudioRecordingManager", "Operation failed", e)
                false
            }

        if (threadFinished) {
            // 读取线程已退出（或从未启动），此时释放不会与 read() 竞争
            releaseRecordLocked(record)
        } else {
            // 读取线程仍阻塞在 read()：宁可短暂泄漏一个录音器，也不能与 read() 竞争，
            // 否则会抛 IllegalStateException 甚至触发 native 崩溃；由该线程退出时自行释放
            Log.w("AudioRecordingManager", "record thread still alive after join timeout, deferring release")
        }
    }
}
