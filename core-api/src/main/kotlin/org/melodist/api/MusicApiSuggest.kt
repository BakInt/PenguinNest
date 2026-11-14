package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.melodist.model.LyricSearchResult
import org.melodist.model.LyricSnippetParser
import org.melodist.model.SearchSuggestionItem
import org.melodist.model.SongNameSuggestion
import java.net.URLEncoder

/*
 * 搜索联想领域扩展（开源方案 Rain120/qq-music-api 架构移植）：
 * - 统一走 c.y.qq.com 明文接口族（getYqqPlainGateway），无需登录、无 AG1 加密
 * - A 路 smartbox 前缀联想（fetchSearchSuggestions）
 * - B 路 search_for_qq_cp t=0 包含式歌名补充 + 翻页补足
 * - 歌词搜索 search_for_qq_cp t=7
 */

/** 联想数量目标：合并去重后不足此数时翻页补足 */
internal const val SUGGEST_TARGET = 12

/** 联想面板展示上限 */
internal const val SUGGEST_MAX_DISPLAY = 30

/** search_for_qq_cp 单页条数（实测服务端上限 30） */
internal const val SUGGEST_PAGE_SIZE = 30

/** 联想翻页封顶页码 */
internal const val SUGGEST_MAX_PAGE = 3

/** 歌词搜索触发门槛：关键词长度不足时不触发（短词模糊匹配噪音大） */
const val LYRIC_SEARCH_MIN_KEYWORD_LENGTH = 4

/** AG1 兜底搜索单页条数（实测 AG1 通道单页 50 可用） */
internal const val AG1_FALLBACK_PAGE_SIZE = 50

private const val TAG = "MusicApiSuggest"

internal const val YQQ_PLAIN_BASE = "https://c.y.qq.com"

/** 明文接口公共查询参数（与 Rain120/qq-music-api 的 commonParams 一致） */
internal const val YQQ_COMMON_QUERY =
    "g_tk=1124214810&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"

private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * 防御性解包：接口在缺省 format=json 或异常分支下会返回 JSONP 包裹 `callback({...})`，
 * 提取括号内 JSON；正常 JSON 原样返回
 */
internal fun unwrapJsonResponse(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) return trimmed
    val open = trimmed.indexOf('(')
    val close = trimmed.lastIndexOf(')')
    if (open >= 0 && close > open) {
        val inner = trimmed.substring(open + 1, close).trim()
        if (inner.startsWith("{")) return inner
    }
    return trimmed
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

/** 归一化歌名用于去重：去首尾空白并合并连续空白 */
internal fun normalizeSongName(name: String): String = name.trim().replace(WHITESPACE_REGEX, " ")

/**
 * 合并 A 路（smartbox）与 B 路（包含式搜索）联想：
 * A 路在前、B 路按原顺序追加；按归一化歌名与 songMid 双维度去重
 * （同名不同歌手只留一条；不同歌名共用同一 songMid 也只留一条，避免 UI 列表 key 碰撞闪退），
 * 展示上限 SUGGEST_MAX_DISPLAY
 */
internal fun mergeSongSuggestions(
    primary: List<SongNameSuggestion>,
    secondary: List<SongNameSuggestion>,
): List<SongNameSuggestion> {
    val seenNames = mutableSetOf<String>()
    val seenMids = mutableSetOf<String>()
    val merged = mutableListOf<SongNameSuggestion>()
    for (item in primary + secondary) {
        val nameKey = normalizeSongName(item.name)
        if (nameKey.isEmpty() || !seenNames.add(nameKey)) continue
        if (item.mid.isNotBlank() && !seenMids.add(item.mid)) continue
        merged += item
        if (merged.size >= SUGGEST_MAX_DISPLAY) break
    }
    return merged
}

/**
 * 是否继续翻页补足联想：合并数不足目标 且 上一页拉满（还有更多结果） 且 未超页数上限
 */
internal fun shouldFetchNextSuggestionPage(
    mergedCount: Int,
    lastPageRawCount: Int,
    nextPage: Int,
): Boolean = mergedCount < SUGGEST_TARGET && lastPageRawCount >= SUGGEST_PAGE_SIZE && nextPage <= SUGGEST_MAX_PAGE

/**
 * 解析 search_for_qq_cp t=0 响应（旧 JSON 格式 data.song.list），
 * 过滤出歌名包含关键词的条目并保留服务端包含式高亮标记
 */
internal fun parsePlainSongHits(
    responseText: String,
    keyword: String,
): List<SongNameSuggestion> {
    if (responseText.isBlank()) return emptyList()
    return try {
        val root = Json.parseToJsonElement(unwrapJsonResponse(responseText)).jsonObject
        val list =
            root["data"]?.jsonObject
                ?.get("song")?.jsonObject
                ?.get("list")?.jsonArray
                ?: return emptyList()
        list.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val name = obj["songname"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (name.isBlank() || !name.contains(keyword, ignoreCase = true)) return@mapNotNull null
                SongNameSuggestion(
                    name = name,
                    mid = obj["songmid"]?.jsonPrimitive?.contentOrNull ?: "",
                    highlight = obj["songname_hilight"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } catch (_: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        ApiLogger.w(TAG, "Plain song hits parse failed, keyword=$keyword", e)
        emptyList()
    }
}

/**
 * 解析 search_for_qq_cp t=7 响应（data.lyric.list，注意与 t=0 的 data.song.list 路径不同），
 * 映射为歌词命中结果并按 songMid 去重
 */
internal fun parseLyricSearchResults(responseText: String): List<LyricSearchResult> {
    if (responseText.isBlank()) return emptyList()
    return try {
        val root = Json.parseToJsonElement(unwrapJsonResponse(responseText)).jsonObject
        val list =
            root["data"]?.jsonObject
                ?.get("lyric")?.jsonObject
                ?.get("list")?.jsonArray
                ?: return emptyList()
        list.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val song = MusicApiService.parseSongFromElement(obj) ?: return@mapNotNull null
                val snippet = LyricSnippetParser.extract(obj["content"]?.jsonPrimitive?.contentOrNull ?: "")
                LyricSearchResult(song = song, snippet = snippet)
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.song.songMid }
    } catch (e: Exception) {
        ApiLogger.w(TAG, "Lyric search parse failed", e)
        emptyList()
    }
}

private suspend fun MusicApiService.fetchPlainSongHits(
    query: String,
    page: Int,
): List<SongNameSuggestion> =
    withContext(Dispatchers.IO) {
        val url =
            "$YQQ_PLAIN_BASE/soso/fcgi-bin/search_for_qq_cp?$YQQ_COMMON_QUERY&format=json" +
                "&ct=24&qqmusic_ver=1298&remoteplace=txt.yqq.song&t=0&aggr=1&cr=1&lossless=0&flag_qc=0" +
                "&w=${encodeQuery(query)}&p=$page&n=$SUGGEST_PAGE_SIZE"
        parsePlainSongHits(getYqqPlainGateway(url), query)
    }

/**
 * 拉取明文 B 路包含式联想；**失败返回 null**（区别于"确实无结果"的空列表），失败原因记录日志
 */
private suspend fun MusicApiService.fetchPlainSongHitsOrNull(
    query: String,
    page: Int,
): List<SongNameSuggestion>? =
    try {
        fetchPlainSongHits(query, page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiLogger.w(TAG, "Plain suggestion fetch failed, query=$query page=$page", e)
        null
    }

/**
 * AG1 兜底：明文 B 路失败时，用主搜索（search_type=0）结果做歌名包含过滤补足联想，
 * 保证任何网络环境下联想数量可达标；失败返回 null
 */
private suspend fun MusicApiService.fetchAg1SongHitsSafe(query: String): List<SongNameSuggestion>? =
    try {
        withContext(Dispatchers.IO) {
            search(query, page = 1, pageSize = AG1_FALLBACK_PAGE_SIZE)
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { SongNameSuggestion(name = it.name, mid = it.songMid) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiLogger.w(TAG, "AG1 suggestion fallback failed, query=$query", e)
        null
    }

/**
 * 歌名联想流（增量发射）：
 * A 路 smartbox 先到先渲染（同样过统一去重，避免 UI 列表 key 碰撞），
 * B 路 search_for_qq_cp 每页到达后合并去重刷新，
 * 合并数不足目标且上一页拉满时继续翻页（封顶 SUGGEST_MAX_PAGE）。
 * B 路请求失败（非"无结果"）时回退 AG1 主搜索包含过滤兜底；
 * 任一路失败记录日志并静默降级；新输入由上游 collectLatest 取消整个流。
 */
fun MusicApiService.streamSongNameSuggestions(query: String): Flow<List<SongNameSuggestion>> =
    flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@flow
        coroutineScope {
            val smartboxDeferred =
                async {
                    try {
                        fetchSearchSuggestions(trimmed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ApiLogger.w(TAG, "Smartbox suggestion fetch failed, query=$trimmed", e)
                        emptyList()
                    }
                }
            val firstPageDeferred = async { fetchPlainSongHitsOrNull(trimmed, 1) }

            val primary =
                mergeSongSuggestions(
                    smartboxDeferred.await().map { item ->
                        SongNameSuggestion(name = item.name, mid = item.mid)
                    },
                    emptyList(),
                )
            if (primary.isNotEmpty()) emit(primary)

            val firstPage = firstPageDeferred.await()
            val secondary =
                if (firstPage != null) {
                    firstPage.toMutableList()
                } else {
                    fetchAg1SongHitsSafe(trimmed).orEmpty().toMutableList()
                }
            if (secondary.isEmpty() && primary.isEmpty()) return@coroutineScope
            var merged = mergeSongSuggestions(primary, secondary)
            if (merged.isNotEmpty()) emit(merged)

            // 翻页补足仅在明文 B 路存活时进行（AG1 兜底为单页一次，不翻页）
            var lastPageRaw = firstPage?.size ?: 0
            var nextPage = 2
            while (firstPage != null && shouldFetchNextSuggestionPage(merged.size, lastPageRaw, nextPage)) {
                val pageHits = fetchPlainSongHitsOrNull(trimmed, nextPage) ?: break
                if (pageHits.isEmpty()) break
                secondary += pageHits
                merged = mergeSongSuggestions(primary, secondary)
                if (merged.isNotEmpty()) emit(merged)
                lastPageRaw = pageHits.size
                nextPage += 1
            }
        }
    }

/**
 * 歌词搜索（search_for_qq_cp t=7）：按歌词片段找歌，未登录可用；失败返回空列表
 */
suspend fun MusicApiService.searchByLyric(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<LyricSearchResult> =
    withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val url =
            "$YQQ_PLAIN_BASE/soso/fcgi-bin/search_for_qq_cp?$YQQ_COMMON_QUERY&format=json" +
                "&ct=24&qqmusic_ver=1298&remoteplace=txt.yqq.lyric&t=7&aggr=1&cr=1&lossless=0&flag_qc=0" +
                "&w=${encodeQuery(trimmed)}&p=$page&n=${pageSize.coerceAtMost(SUGGEST_PAGE_SIZE)}"
        try {
            parseLyricSearchResults(getYqqPlainGateway(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiLogger.w(TAG, "Lyric search failed, query=$trimmed page=$page", e)
            emptyList()
        }
    }
