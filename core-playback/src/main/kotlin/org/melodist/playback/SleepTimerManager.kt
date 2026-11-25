package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object SleepTimerManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownJob: Job? = null

    private var targetTimestampMs: Long = 0L

    private val _isTimerActive = MutableStateFlow(false)
    val isTimerActive: StateFlow<Boolean> = _isTimerActive.asStateFlow()

    private val _selectedMinutes = MutableStateFlow<Int?>(null)
    val selectedMinutes: StateFlow<Int?> = _selectedMinutes.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _isWaitingForSongEnd = MutableStateFlow(false)
    val isWaitingForSongEnd: StateFlow<Boolean> = _isWaitingForSongEnd.asStateFlow()

    /**
     * 倒计时到点且正在播放时的回调。
     *
     * 由 [PlaybackManager] 注入，用于“淡出后再停止”的收尾；回调返回 true 表示它已接管
     * 本次停止（内部负责淡出结束后暂停播放并调用 [cancelTimer]）。返回 false 或未注入时，
     * 退回“播完当前歌曲再停”的旧行为。
     */
    var onTimerExpiredListener: (() -> Boolean)? = null

    fun startTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelTimer()
            return
        }
        countdownJob?.cancel()
        _selectedMinutes.value = minutes
        _isTimerActive.value = true
        _isWaitingForSongEnd.value = false
        targetTimestampMs = System.currentTimeMillis() + minutes * 60 * 1000L
        _remainingSeconds.value = minutes * 60L

        countdownJob =
            scope.launch {
                while (isActive) {
                    delay(1000L)
                    val now = System.currentTimeMillis()
                    val diff = targetTimestampMs - now
                    if (diff <= 0L) {
                        _remainingSeconds.value = 0L
                        val fadeOutOnce = onTimerExpiredListener
                        if (fadeOutOnce != null && PlaybackManager.isPlaying.value && fadeOutOnce()) {
                            // 已交给淡出收尾逻辑：它会在淡出完成后暂停播放并取消定时器
                            break
                        }
                        _isWaitingForSongEnd.value = true
                        if (!PlaybackManager.isPlaying.value) {
                            cancelTimer()
                        }
                        break
                    } else {
                        _remainingSeconds.value = (diff + 999L) / 1000L
                    }
                }
            }
    }

    fun cancelTimer() {
        countdownJob?.cancel()
        countdownJob = null
        targetTimestampMs = 0L
        _isTimerActive.value = false
        _selectedMinutes.value = null
        _remainingSeconds.value = 0L
        _isWaitingForSongEnd.value = false
    }

    fun onSongEnded(): Boolean {
        if (!_isTimerActive.value) return false
        val now = System.currentTimeMillis()
        if (_isWaitingForSongEnd.value || (targetTimestampMs > 0L && now >= targetTimestampMs)) {
            cancelTimer()
            return true
        }
        return false
    }
}
