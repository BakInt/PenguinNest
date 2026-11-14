package org.melodist.model

import kotlin.math.min

/**
 * 副歌（高潮）检测器：基于逐行歌词的重复连续块结构分析。
 *
 * 流行曲式中副歌歌词通常整块重复：找出"出现 >=2 次的连续歌词块"，其每次出现的时间区间即一段副歌。
 * 选块优先级：出现次数多 > 块时长更长（同等次数下排除副歌的后缀子块）> 首次出现更靠后；
 * 块时长限制在 12~60s——下限过滤零散短句重复，上限抑制"主歌+副歌"整段拼接的长块。
 * 出现收集要求相邻出现间至少隔 1 行，进一步抑制周期性拼接伪块（其出现首尾相接）。
 * 说唱、纯器乐、副歌改写等无可重复结构时返回空表（宁缺毋滥）。
 * 纯函数、无 IO；n 为歌词行数（通常 <=100），单次检测耗时微秒~毫秒级。
 */
object ChorusDetector {

    /** 一段副歌出现区间：startMs 为块首行时间戳，endMs 为块末行时间戳 + 末行尾部估计 */
    data class ChorusSection(val startMs: Long, val endMs: Long)

    private const val MIN_BLOCK_LINES = 3
    private const val MIN_BLOCK_SPAN_MS = 12_000L
    private const val MAX_BLOCK_SPAN_MS = 60_000L
    private const val LAST_LINE_TAIL_MS = 8_000L
    private const val MAX_SECTIONS = 5

    private val BRACKETED_SEGMENT = Regex("[（(【\\[][^）)\\]】]*[）)\\]】]")

    fun detect(lines: List<LyricLine>, durationMs: Long): List<ChorusSection> {
        val rows =
            lines
                .asSequence()
                .filter { it.text.isNotBlank() }
                .map { it.timestampMs to normalizeText(it.text) }
                .filter { it.second.isNotEmpty() }
                .sortedBy { it.first }
                .toList()
        if (rows.size < MIN_BLOCK_LINES * 2) return emptyList()

        val n = rows.size
        val ids = IntArray(n)
        val idByText = HashMap<String, Int>()
        for (index in 0 until n) {
            ids[index] = idByText.getOrPut(rows[index].second) { idByText.size }
        }

        var bestCount = 0
        var bestFirstStartMs = -1L
        var bestSpanMs = -1L
        var bestLen = 0
        var bestPositions: List<Int> = emptyList()
        val seenCandidates = HashSet<Long>()

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                // len 上限同时受数组边界与"两次出现不重叠"约束
                val cap = min(n - j, j - i)
                if (cap < MIN_BLOCK_LINES) continue
                var len = 0
                while (len < cap && ids[i + len] == ids[j + len]) len++
                if (len < MIN_BLOCK_LINES) continue
                if (!seenCandidates.add(i.toLong() * (n + 1) + len)) continue

                val spanMs = rows[i + len - 1].first - rows[i].first + LAST_LINE_TAIL_MS
                if (spanMs < MIN_BLOCK_SPAN_MS || spanMs > MAX_BLOCK_SPAN_MS) continue
                val positions = collectOccurrences(ids, i, len)
                if (positions.size < 2) continue

                val firstStartMs = rows[positions.first()].first
                val better =
                    when {
                        positions.size > bestCount -> true
                        positions.size < bestCount -> false
                        spanMs != bestSpanMs -> spanMs > bestSpanMs
                        else -> firstStartMs > bestFirstStartMs
                    }
                if (better) {
                    bestCount = positions.size
                    bestFirstStartMs = firstStartMs
                    bestSpanMs = spanMs
                    bestLen = len
                    bestPositions = positions
                }
            }
        }
        if (bestLen == 0) return emptyList()

        return bestPositions
            .take(MAX_SECTIONS)
            .map { start ->
                val endMs = rows[start + bestLen - 1].first + LAST_LINE_TAIL_MS
                val clamped = if (durationMs > 0) endMs.coerceAtMost(durationMs) else endMs
                ChorusSection(rows[start].first, clamped)
            }
    }

    /**
     * 收集 [origin] 处长度 [len] 的指纹块在整首歌中的全部出现位置（行下标，升序）。
     * 相邻出现之间要求至少隔 1 行：抑制"主歌+副歌"整段拼接的周期性伪块（其出现首尾相接）；
     * 行相邻的合法出现（如结尾双副歌）保留更早的一次。
     */
    private fun collectOccurrences(
        ids: IntArray,
        origin: Int,
        len: Int,
    ): List<Int> {
        val positions = mutableListOf<Int>()
        var k = 0
        val limit = ids.size - len
        var nextAllowed = 0
        while (k <= limit) {
            var matched = true
            for (offset in 0 until len) {
                if (ids[k + offset] != ids[origin + offset]) {
                    matched = false
                    break
                }
            }
            if (matched && k >= nextAllowed) {
                positions.add(k)
                nextAllowed = k + len + 1
                k += len
            } else {
                k++
            }
        }
        return positions
    }

    /** 归一化：剔除括号标记（"(副歌)"、"【间奏】"等），仅保留字母与数字（顺带去标点/空白/装饰符号），再转小写 */
    private fun normalizeText(text: String): String =
        buildString(text.length) {
            for (ch in BRACKETED_SEGMENT.replace(text, " ")) {
                if (ch.isLetterOrDigit()) append(ch)
            }
        }.lowercase()
}
