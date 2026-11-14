package org.melodist.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

@OptIn(UnstableApi::class)
class CacheKeyFifoEvictorTest {
    private val mb = 1024L * 1024L

    /**
     * 最小 Cache 桩：记录 key -> spans，removeResource 删整键并回调 onSpanRemoved
     * （模拟 SimpleCache 行为：淘汰器的记账完全依赖该回调修正）。
     * lockedKeys 中的键 removeResource 无效果，用于覆盖"分片全部锁定"的跳过路径。
     */
    private class FakeCache(
        private val evictor: CacheKeyFifoEvictor,
        private val lockedKeys: Set<String> = emptySet(),
    ) : Cache {
        val spans = LinkedHashMap<String, MutableList<CacheSpan>>()
        val removedKeys = mutableListOf<String>()

        fun seed(
            key: String,
            bytes: Long,
            firstCachedAtMs: Long,
        ) {
            val span = CacheSpan(key, 0L, bytes, firstCachedAtMs, File("f_$key"))
            assertTrue(span.isCached, "带 file 的 CacheSpan 应视为已缓存分片")
            // 对齐 SimpleCache 时序：先登记 span 再通知淘汰器
            spans.getOrPut(key) { mutableListOf() }.add(span)
            evictor.onSpanAdded(this, span)
        }

        override fun getUid(): Long = 1L

        override fun release() {
            throw UnsupportedOperationException()
        }

        override fun addListener(
            key: String,
            listener: Cache.Listener,
        ): NavigableSet<CacheSpan> = TreeSet()

        override fun removeListener(
            key: String,
            listener: Cache.Listener,
        ) {
        }

        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TreeSet()

        override fun getKeys(): Set<String> = spans.keys.toSet()

        override fun getCacheSpace(): Long = spans.values.sumOf { list -> list.sumOf { it.length } }

        override fun startReadWrite(
            key: String,
            position: Long,
            length: Long,
        ): CacheSpan = throw UnsupportedOperationException()

        override fun startReadWriteNonBlocking(
            key: String,
            position: Long,
            length: Long,
        ): CacheSpan = throw UnsupportedOperationException()

        override fun startFile(
            key: String,
            position: Long,
            maxLength: Long,
        ): File = throw UnsupportedOperationException()

        override fun commitFile(
            file: File,
            length: Long,
        ) {
            throw UnsupportedOperationException()
        }

        override fun releaseHoleSpan(span: CacheSpan) {
            throw UnsupportedOperationException()
        }

        override fun removeResource(key: String) {
            if (key in lockedKeys) return
            val removed = spans.remove(key).orEmpty()
            if (removed.isNotEmpty()) {
                removedKeys.add(key)
                removed.forEach { evictor.onSpanRemoved(this, it) }
            }
        }

        override fun removeSpan(span: CacheSpan) {
            throw UnsupportedOperationException()
        }

        override fun isCached(
            key: String,
            position: Long,
            length: Long,
        ): Boolean =
            spans[key]
                ?.any { it.position <= position && it.position + it.length >= position + length }
                ?: false

        override fun getCachedLength(
            key: String,
            position: Long,
            length: Long,
        ): Long = 0L

        override fun getCachedBytes(
            key: String,
            position: Long,
            length: Long,
        ): Long = 0L

        override fun applyContentMetadataMutations(
            key: String,
            mutations: ContentMetadataMutations,
        ) {
            throw UnsupportedOperationException()
        }

        override fun getContentMetadata(key: String): ContentMetadata = throw UnsupportedOperationException()
    }

    private fun newFixture(
        quotaBytes: Long,
        lockedKeys: Set<String> = emptySet(),
    ): Pair<CacheKeyFifoEvictor, FakeCache> {
        lateinit var cache: FakeCache
        val evictor = CacheKeyFifoEvictor(quotaBytes)
        cache = FakeCache(evictor, lockedKeys)
        evictor.attachCache(cache)
        return evictor to cache
    }

    @Test
    fun `超过配额时按首缓时间整键淘汰最老的条目`() {
        val (evictor, cache) = newFixture((1.2 * 1024L * mb).toLong())
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }

        cache.seed("qqmusic_A_SQ", 500L * mb, 1000L)
        cache.seed("qqmusic_B_SQ", 500L * mb, 2000L)
        cache.seed("qqmusic_C_SQ", 500L * mb, 3000L)

        assertEquals(listOf("qqmusic_A_SQ"), evicted)
        assertEquals(1000L * mb, cache.getCacheSpace())
        assertFalse(cache.spans.containsKey("qqmusic_A_SQ"))
        assertTrue(cache.spans.containsKey("qqmusic_B_SQ"))
        assertTrue(cache.spans.containsKey("qqmusic_C_SQ"))
    }

    @Test
    fun `活跃键受保护改为淘汰次老条目`() {
        val (evictor, cache) = newFixture(1200L * mb)
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }

        cache.seed("qqmusic_A_SQ", 500L * mb, 1000L)
        cache.seed("qqmusic_B_SQ", 500L * mb, 2000L)
        evictor.setActiveKeys(setOf("qqmusic_A_SQ"))
        cache.seed("qqmusic_C_SQ", 500L * mb, 3000L)

        assertEquals(listOf("qqmusic_B_SQ"), evicted)
        assertTrue(cache.spans.containsKey("qqmusic_A_SQ"))
        assertFalse(cache.spans.containsKey("qqmusic_B_SQ"))
    }

    @Test
    fun `运行期调小配额立即淘汰到新目标`() {
        val (evictor, cache) = newFixture(1000L * mb)
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }

        cache.seed("qqmusic_A", 400L * mb, 1000L)
        cache.seed("qqmusic_B", 400L * mb, 2000L)
        assertTrue(evicted.isEmpty())

        evictor.setQuotaBytes(500L * mb)

        assertEquals(listOf("qqmusic_A"), evicted)
        assertEquals(400L * mb, cache.getCacheSpace())
    }

    @Test
    fun `分片全部锁定时跳过该键淘汰下一最老键`() {
        val (evictor, cache) = newFixture(600L * mb, lockedKeys = setOf("qqmusic_LOCKED"))
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }

        cache.seed("qqmusic_LOCKED", 400L * mb, 1000L)
        cache.seed("qqmusic_B", 400L * mb, 2000L)

        assertEquals(listOf("qqmusic_B"), evicted)
        assertTrue(cache.spans.containsKey("qqmusic_LOCKED"))
        assertFalse(cache.spans.containsKey("qqmusic_B"))
    }

    @Test
    fun `记账随 span 移除回调自校正`() {
        val (evictor, cache) = newFixture(1000L * mb)

        cache.seed("qqmusic_A", 600L * mb, 1000L)
        assertEquals(1, evictor.getTrackedKeyCount())

        val span = cache.spans.getValue("qqmusic_A").single()
        cache.spans.remove("qqmusic_A")
        evictor.onSpanRemoved(cache, span)

        assertEquals(0, evictor.getTrackedKeyCount())
    }

    @Test
    fun `读取touch不改变FIFO顺序`() {
        val (evictor, cache) = newFixture(1200L * mb)
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }
        assertFalse(evictor.requiresCacheSpanTouches())

        cache.seed("qqmusic_A", 500L * mb, 1000L)
        cache.seed("qqmusic_B", 500L * mb, 2000L)

        val touchedSpan = CacheSpan("qqmusic_A", 0L, 500L * mb, 9999L, File("f_qqmusic_A"))
        evictor.onSpanTouched(cache, cache.spans.getValue("qqmusic_A").single(), touchedSpan)

        cache.seed("qqmusic_C", 500L * mb, 3000L)

        assertEquals(listOf("qqmusic_A"), evicted)
    }

    @Test
    fun `配额充足时不淘汰`() {
        val (evictor, cache) = newFixture(2000L * mb)
        val evicted = mutableListOf<String>()
        evictor.onKeysEvicted = { evicted.addAll(it) }

        cache.seed("qqmusic_A", 500L * mb, 1000L)
        cache.seed("qqmusic_B", 500L * mb, 2000L)

        assertTrue(evicted.isEmpty())
        assertEquals(2, evictor.getTrackedKeyCount())
    }
}
