package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/*
 * 收藏领域扩展：单曲收藏数（红心数量）查询
 */

/**
 * 批量查询单曲收藏数。
 *
 * 走 `music.musicasset.SongFavRead / GetSongFansNumberById`，入参为 songId 数组。
 * 真实响应形如：
 * ```
 * {"data":{"m_numbers":{"97773":1000001},"m_show":{"97773":"5700w+"}}}
 * ```
 * 其中 `m_numbers` 为原始收藏数（超大值被服务端截断到 [CAPPED_NUMBER]），
 * `m_show` 为服务端展示文案（`1k+` / `5w+` / `5700w+` 等，保留真实量级）。
 *
 * 解析时以 `m_numbers` 为准；当其缺失或触达截断上限时，回退用 `m_show` 还原量级。
 * 同时保留对历史/变体结构的容错：
 * - `data.m_fav_num: { "<songId>": 12 }`
 * - `data.v_fav_num: { "<songId>": 12 }`
 * - `data: { "<songId>": { "num": 12 } }`
 * - `data: [ { "songId": 1, "num": 12 } ]`
 *
 * 无法识别的曲目不会出现在结果中，调用方按"数量未知"处理。
 */
suspend fun MusicApiService.getSongFavoriteCounts(songIds: Collection<Long>): Map<Long, Int> =
    withContext(Dispatchers.IO) {
        val ids = songIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()

        val idsLiteral = ids.joinToString(prefix = "[", postfix = "]", separator = ",")
        val payload =
            """
            {
              "music.musicasset.SongFavRead": {
                "module": "music.musicasset.SongFavRead",
                "method": "GetSongFansNumberById",
                "param": {
                  "v_songId": $idsLiteral
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val svc = root["music.musicasset.SongFavRead"]?.jsonObject
            val data = svc?.get("data") ?: root["data"] ?: return@withContext emptyMap()
            parseSongFavoriteCounts(data, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

/**
 * 解析收藏数响应体，兼容多种字段形态（见 [getSongFavoriteCounts]）。
 */
internal fun parseSongFavoriteCounts(
    data: JsonElement?,
    ids: Collection<Long>,
): Map<Long, Int> {
    val wanted = ids.toSet()
    val result = mutableMapOf<Long, Int>()
    if (wanted.isEmpty()) return result

    fun absorbObject(obj: JsonObject) {
        for ((key, value) in obj) {
            val songId = key.toLongOrNull() ?: continue
            if (songId !in wanted) continue
            val count = value.asFavoriteCount() ?: continue
            result[songId] = count
        }
    }

    fun absorbArray(array: JsonArray) {
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val songId =
                obj["songId"]?.asLongOrNull()
                    ?: obj["song_id"]?.asLongOrNull()
                    ?: obj["id"]?.asLongOrNull()
                    ?: continue
            if (songId !in wanted) continue
            val count =
                obj["num"]?.asFavoriteCount()
                    ?: obj["count"]?.asFavoriteCount()
                    ?: obj["fav_num"]?.asFavoriteCount()
                    ?: obj["favNum"]?.asFavoriteCount()
                    ?: continue
            result[songId] = count
        }
    }

    fun absorb(element: JsonElement?) {
        when (element) {
            is JsonObject -> absorbObject(element)
            is JsonArray -> absorbArray(element)
            else -> Unit
        }
    }

    absorb(data)
    if (data is JsonObject) {
        for (key in NESTED_COUNT_KEYS) {
            absorb(data[key])
        }
        absorbShowCounts(data["m_show"], wanted, result)
    }
    return result
}

/**
 * 用服务端展示文案（`m_show`）补齐 `m_numbers` 缺失或触达截断上限的曲目。
 *
 * 例：`{"97773":"5700w+"}` → `97773L to 57_000_000`。
 */
private fun absorbShowCounts(
    show: JsonElement?,
    wanted: Set<Long>,
    result: MutableMap<Long, Int>,
) {
    val showObj = show as? JsonObject ?: return
    for ((key, value) in showObj) {
        val songId = key.toLongOrNull() ?: continue
        if (songId !in wanted) continue
        val known = result[songId]
        if (known != null && known < CAPPED_NUMBER) continue
        val parsed = parseShowCountText(value.contentOrNullValue()) ?: continue
        result[songId] = parsed
    }
}

/**
 * 解析服务端展示文案为收藏数，支持 `k`（千）、`w`/`万`、`亿` 后缀与可选 `+`。
 *
 * 例：`"1k+"` → 1000，`"5w+"` → 50000，`"5700w+"` → 57000000，`"1.2亿"` → 120000000。
 */
internal fun parseShowCountText(text: String?): Int? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val match = SHOW_COUNT_REGEX.matchEntire(raw) ?: return null
    val base = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit =
        when (match.groupValues[2].lowercase()) {
            "k", "千" -> 1_000.0
            "w", "万" -> 10_000.0
            "亿" -> 100_000_000.0
            else -> 1.0
        }
    val value = base * unit
    if (value <= 0.0) return 0
    return value.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}

private const val CAPPED_NUMBER = 1_000_001

private val SHOW_COUNT_REGEX = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([wWkK千万元亿]?)\s*\+?$""")

private val NESTED_COUNT_KEYS =
    listOf("m_numbers", "m_fav_num", "v_fav_num", "fav_num", "favNum", "list", "songs")

private fun JsonElement.asLongOrNull(): Long? =
    when (this) {
        is JsonPrimitive -> longOrNull ?: contentOrNull?.toLongOrNull()
        else -> null
    }

private fun JsonElement.asFavoriteCount(): Int? =
    when (this) {
        is JsonPrimitive -> intOrNull ?: contentOrNull?.toIntOrNull()
        is JsonObject ->
            this["num"]?.asFavoriteCount()
                ?: this["count"]?.asFavoriteCount()
                ?: this["fav_num"]?.asFavoriteCount()
                ?: this["favNum"]?.asFavoriteCount()
        else -> null
    }

private fun JsonElement.contentOrNullValue(): String? = (this as? JsonPrimitive)?.contentOrNull
