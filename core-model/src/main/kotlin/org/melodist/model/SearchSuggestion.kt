package org.melodist.model

import kotlinx.serialization.Serializable

/**
 * smartbox 单曲联想条目（A 路原始解析结果，仅歌名）
 */
@Serializable
data class SearchSuggestionItem(
    val mid: String,
    val name: String,
)

/**
 * 联想面板条目（统一模型，仅歌名）
 *
 * @param highlight 服务端包含式高亮标记原文（songname_hilight），为空时由 UI 本地包含式高亮
 */
@Serializable
data class SongNameSuggestion(
    val name: String,
    val mid: String = "",
    val highlight: String = "",
)

/**
 * 歌词搜索命中结果（歌曲 + 命中歌词片段）
 */
@Serializable
data class LyricSearchResult(
    val song: Song,
    val snippet: String = "",
)

/**
 * 服务端 songname_hilight 解析。
 * 仅识别固定标记 <span class="c_tx_highlight">…</span>，标记不成对或缺失时按纯文本处理，不透出任何标记语法。
 */
object SongNameHighlightParser {
    private const val SPAN_START = "<span class=\"c_tx_highlight\">"
    private const val SPAN_END = "</span>"

    /**
     * 解析高亮标记为（纯文本, 高亮区间列表），区间为纯文本坐标（左闭右开）。
     */
    fun parse(hilight: String): Pair<String, List<IntRange>> {
        if (hilight.isBlank()) return hilight to emptyList()
        val textBuilder = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < hilight.length) {
            val start = hilight.indexOf(SPAN_START, index)
            if (start == -1) {
                textBuilder.append(hilight.substring(index))
                break
            }
            val contentStart = start + SPAN_START.length
            val end = hilight.indexOf(SPAN_END, contentStart)
            if (end == -1) {
                textBuilder.append(hilight.substring(index))
                break
            }
            textBuilder.append(hilight.substring(index, start))
            val content = hilight.substring(contentStart, end)
            if (content.isNotEmpty()) {
                val rangeStart = textBuilder.length
                textBuilder.append(content)
                ranges += rangeStart until textBuilder.length
            }
            index = end + SPAN_END.length
        }
        return textBuilder.toString() to ranges
    }
}

/**
 * 明文歌词搜索 content 字段解析。
 * content 为 HTML 转义字符串，命中歌词行由 <strong class="keyword">…</strong> 标记。
 */
object LyricSnippetParser {
    private const val STRONG_START = "<strong class=\"keyword\">"
    private const val STRONG_END = "</strong>"
    private const val FALLBACK_SNIPPET_LENGTH = 60

    /**
     * 提取命中歌词片段（已反转义）；无 strong 标记时回退为去标签后的前若干字符。
     */
    fun extract(content: String): String {
        if (content.isBlank()) return ""
        val unescaped = unescapeHtml(content)
        val strongStart = unescaped.indexOf(STRONG_START)
        if (strongStart >= 0) {
            val contentStart = strongStart + STRONG_START.length
            val strongEnd = unescaped.indexOf(STRONG_END, contentStart)
            if (strongEnd > contentStart) {
                return unescaped.substring(contentStart, strongEnd).trim()
            }
        }
        return unescaped
            .replace(TAG_REGEX, " ")
            .trim()
            .take(FALLBACK_SNIPPET_LENGTH)
    }

    private val TAG_REGEX = Regex("<[^>]+>")

    private fun unescapeHtml(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
}
