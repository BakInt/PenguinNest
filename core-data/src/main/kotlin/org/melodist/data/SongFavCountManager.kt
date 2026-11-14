package org.melodist.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.melodist.api.MusicApiService
import org.melodist.api.getSongFavoriteCounts

/**
 * 单曲收藏数（红心数量）缓存。
 *
 * - 结果按 songId 缓存 10 分钟，最多保留 500 条（LRU 淘汰）；
 * - 同一批请求内的并发调用会被串行化，避免重复打接口；
 * - 接口不可用时返回空 Map，调用方按"数量未知"处理，不影响红心状态展示。
 */
object SongFavCountManager {
    private const val TAG = "SongFavCountManager"
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 500
    private const val BATCH_SIZE = 50

    private data class Entry(val count: Int, val timestamp: Long)

    private val cache =
        object : LinkedHashMap<Long, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > MAX_ENTRIES
        }
    private val fetchMutex = Mutex()
    private val apiService = MusicApiService()

    /**
     * 批量获取收藏数，返回命中（缓存或接口）的 songId -> 收藏数。
     */
    suspend fun getCounts(songIds: Collection<Long>): Map<Long, Int> {
        val ids = songIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, Int>()
        val missing = mutableListOf<Long>()
        synchronized(cache) {
            val now = System.currentTimeMillis()
            for (id in ids) {
                val entry = cache[id]
                if (entry != null && now - entry.timestamp < TTL_MS) {
                    result[id] = entry.count
                } else {
                    missing += id
                }
            }
        }
        if (missing.isEmpty()) return result

        fetchMutex.withLock {
            val stillMissing = mutableListOf<Long>()
            synchronized(cache) {
                val now = System.currentTimeMillis()
                for (id in missing) {
                    val entry = cache[id]
                    if (entry != null && now - entry.timestamp < TTL_MS) {
                        result[id] = entry.count
                    } else {
                        stillMissing += id
                    }
                }
            }
            if (stillMissing.isEmpty()) return@withLock

            for (batch in stillMissing.chunked(BATCH_SIZE)) {
                val counts =
                    try {
                        apiService.getSongFavoriteCounts(batch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch favorite counts for ${batch.size} song(s)", e)
                        emptyMap()
                    }
                if (counts.isEmpty()) continue
                val stamp = System.currentTimeMillis()
                synchronized(cache) {
                    counts.forEach { (id, count) -> cache[id] = Entry(count, stamp) }
                }
                result.putAll(counts)
            }
        }
        return result
    }

    /**
     * 收藏/取消收藏后按增量修正缓存值（仅在已有缓存时生效，不主动请求接口）。
     */
    fun adjust(songId: Long, delta: Int) {
        if (songId <= 0L || delta == 0) return
        synchronized(cache) {
            val entry = cache[songId] ?: return
            cache[songId] = entry.copy(count = (entry.count + delta).coerceAtLeast(0), timestamp = System.currentTimeMillis())
        }
    }

    /**
     * 清空缓存（退出登录等场景调用）。
     */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
