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
 * 该接口在不同后端版本下返回结构略有差异，这里统一做容错解析：
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
    }
    return result
}

private val NESTED_COUNT_KEYS =
    listOf("m_fav_num", "v_fav_num", "fav_num", "favNum", "list", "songs")

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
