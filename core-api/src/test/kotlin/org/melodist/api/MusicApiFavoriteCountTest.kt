package org.melodist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicApiFavoriteCountTest {
    private fun parse(
        payload: String,
        ids: List<Long> = listOf(123L, 456L),
    ): Map<Long, Int> {
        val data = Json.parseToJsonElement(payload).jsonObject
        return parseSongFavoriteCounts(data, ids)
    }

    @Test
    fun `parses flat m_fav_num map`() {
        val result = parse("""{"m_fav_num":{"123":12,"456":1500}}""")
        assertEquals(12, result[123L])
        assertEquals(1500, result[456L])
    }

    @Test
    fun `parses v_fav_num map`() {
        val result = parse("""{"v_fav_num":{"123":8}}""")
        assertEquals(8, result[123L])
    }

    @Test
    fun `parses nested num object`() {
        val result = parse("""{"m_fav_num":{"123":{"num":7}}}""")
        assertEquals(7, result[123L])
    }

    @Test
    fun `parses list form`() {
        val result = parse("""{"list":[{"songId":123,"num":7},{"song_id":456,"count":999}]}""")
        assertEquals(7, result[123L])
        assertEquals(999, result[456L])
    }

    @Test
    fun `ignores ids outside the requested set`() {
        val result = parse("""{"m_fav_num":{"123":12,"999":3}}""")
        assertEquals(mapOf(123L to 12), result)
    }

    @Test
    fun `returns empty map for unrecognized payload`() {
        val result = parse("""{"code":0}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty map when requested ids are empty`() {
        val data = Json.parseToJsonElement("""{"m_fav_num":{"123":12}}""").jsonObject
        assertTrue(parseSongFavoriteCounts(data, emptyList()).isEmpty())
    }

    @Test
    fun `tolerates string numeric values`() {
        val result = parse("""{"m_fav_num":{"123":"42"}}""")
        assertEquals(42, result[123L])
    }
}
