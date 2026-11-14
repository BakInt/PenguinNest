package org.melodist.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 整键 FIFO 磁盘缓存淘汰器：超过配额时按"首次缓存时间"升序整键（removeResource）删除，
 * 替代 Media3 默认的 LeastRecentlyUsedCacheEvictor（按 span 块、最近使用排序）。
 *
 * 记账完全由 SimpleCache 的 span 增/删回调驱动（含冷启动装载的历史 span，其
 * lastTouchTimestamp 即首缓时间——requiresCacheSpanTouches=false 保证读取不重写），
 * 锁定中 span 的延迟删除等边界经回调自校正；配额运行期可变（setQuotaBytes 立即生效）。
 *
 * 线程约束：不持有内部锁调用 cache.removeResource（SimpleCache 回调持有其自身锁，
 * 反向持锁再入会形成锁序倒挂）；removeResource 同步触发 onSpanRemoved 修正记账。
 */
@OptIn(UnstableApi::class)
class CacheKeyFifoEvictor(initialQuotaBytes: Long) : CacheEvictor {
    private class KeyEntry {
        var totalBytes = 0L
        var firstCachedAtMs = Long.MAX_VALUE
    }

    /** 整键被淘汰后的回调（removeResource 已返回），供业务层清理曲目级账目 */
    var onKeysEvicted: ((List<String>) -> Unit)? = null

    private val lock = ReentrantLock()
    private val entries = HashMap<String, KeyEntry>()
    private val activeKeys = HashSet<String>()
    private var cacheRef: Cache? = null

    @Volatile
    private var quotaBytes = initialQuotaBytes.coerceAtLeast(0L)

    /**
     * 绑定缓存实例（SimpleCache 构造后调用），供配额变更/初始化完成时主动触发淘汰
     */
    fun attachCache(cache: Cache) {
        lock.withLock {
            cacheRef = cache
        }
    }

    /** 更新正在播放/预加载的缓存键集合，淘汰时跳过；随后立即补一轮淘汰检查 */
    fun setActiveKeys(keys: Set<String>) {
        lock.withLock {
            activeKeys.clear()
            activeKeys.addAll(keys)
        }
        enforceQuota()
    }

    /** 运行期变更配额并立即按新配额执行整键淘汰 */
    fun setQuotaBytes(targetQuotaBytes: Long) {
        quotaBytes = targetQuotaBytes.coerceAtLeast(0L)
        enforceQuota()
    }

    /** 按当前配额执行一轮淘汰检查（冷启动装载历史 span 后调用，覆盖 init 时收紧配额的情形） */
    fun enforceQuota() {
        val cache = cacheRef ?: return
        evictToTarget(cache, quotaBytes)
    }

    /** 当前记账键总数（诊断/测试用） */
    fun getTrackedKeyCount(): Int = lock.withLock { entries.size }

    override fun requiresCacheSpanTouches(): Boolean = false

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (span.isCached && span.length > 0L) {
            lock.withLock {
                val entry = entries.getOrPut(span.key) { KeyEntry() }
                entry.totalBytes += span.length
                entry.firstCachedAtMs = minOf(entry.firstCachedAtMs, span.lastTouchTimestamp)
            }
        }
        evictToTarget(cache, quotaBytes)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (!span.isCached || span.length <= 0L) return
        lock.withLock {
            val entry = entries[span.key] ?: return@withLock
            entry.totalBytes -= span.length
            if (entry.totalBytes <= 0L) {
                entries.remove(span.key)
            }
        }
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        // FIFO 语义：读取不改变首缓时间（requiresCacheSpanTouches=false 时通常不会到达此处）
    }

    override fun onCacheInitialized() {
        enforceQuota()
    }

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        maxLength: Long,
    ) {
        // 无需处理：记账由 span 增/删回调驱动
    }

    private fun evictToTarget(
        cache: Cache,
        targetBytes: Long,
    ) {
        val skipped = HashSet<String>()
        val evicted = mutableListOf<String>()
        var iterations = 0
        val maxIterations = lock.withLock { entries.size * 2 + EVICTION_ITERATION_MARGIN }

        while (iterations++ < maxIterations) {
            val totalBytes = lock.withLock { entries.values.sumOf { it.totalBytes } }
            if (totalBytes <= targetBytes) break

            val candidate =
                lock.withLock {
                    entries.entries
                        .filter { it.key !in activeKeys && it.key !in skipped }
                        .minByOrNull { it.value.firstCachedAtMs }
                        ?.key
                } ?: break

            val bytesBefore = lock.withLock { entries[candidate]?.totalBytes ?: 0L }
            try {
                cache.removeResource(candidate)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to evict cache entry for key: $candidate", e)
            }
            val bytesAfter = lock.withLock { entries[candidate]?.totalBytes ?: 0L }

            if (bytesAfter < bytesBefore) {
                evicted.add(candidate)
            } else {
                // 全部分片处于锁定态（写入中），本轮跳过，待其解锁后由回调/下一轮处理
                skipped.add(candidate)
            }
        }

        if (evicted.isNotEmpty()) {
            Log.i(TAG, "FIFO evicted ${evicted.size} cache entries: $evicted")
            onKeysEvicted?.invoke(evicted)
        }
    }

    companion object {
        private const val TAG = "CacheKeyFifoEvictor"
        private const val EVICTION_ITERATION_MARGIN = 32
    }
}
