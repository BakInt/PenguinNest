package org.melodist.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.LyricSnippetParser
import org.melodist.model.SongNameHighlightParser
import org.melodist.model.SongNameSuggestion

class SongSuggestLogicTest {
    @Test
    fun `normalizeSongName trims and collapses whitespace`() {
        assertEquals("月亮惹的祸", normalizeSongName("  月亮惹的祸  "))
        assertEquals("a b c", normalizeSongName("a \t b   c"))
        assertEquals("", normalizeSongName("   "))
    }

    @Test
    fun `mergeSongSuggestions dedupes across sources keeping primary first`() {
        val primary =
            listOf(
                SongNameSuggestion(name = "月亮惹的祸", mid = "a1"),
                SongNameSuggestion(name = "月亮桥", mid = "a2"),
            )
        val secondary =
            listOf(
                SongNameSuggestion(name = "月亮惹的祸", mid = "b1"),
                SongNameSuggestion(name = "痴情惹的祸", mid = "b2", highlight = "痴情<span>x</span>"),
            )
        val merged = mergeSongSuggestions(primary, secondary)

        assertEquals(listOf("月亮惹的祸", "月亮桥", "痴情惹的祸"), merged.map { it.name })
        assertEquals("a1", merged[0].mid)
        assertEquals("b2", merged[2].mid)
        assertTrue(merged[2].highlight.isNotBlank())
    }

    @Test
    fun `mergeSongSuggestions treats whitespace variants as duplicates`() {
        val primary = listOf(SongNameSuggestion(name = "月亮  惹的祸", mid = "a1"))
        val secondary =
            listOf(
                SongNameSuggestion(name = "歌名甲", mid = "b1"),
                SongNameSuggestion(name = "月亮 惹的祸", mid = "bDup"),
                SongNameSuggestion(name = "歌名乙", mid = "b2"),
            )

        val merged = mergeSongSuggestions(primary, secondary)

        assertEquals(listOf("月亮  惹的祸", "歌名甲", "歌名乙"), merged.map { it.name })
    }

    @Test
    fun `mergeSongSuggestions caps at display limit`() {
        val secondary = (1..SUGGEST_MAX_DISPLAY + 5).map { SongNameSuggestion(name = "歌名$it", mid = "b$it") }

        val merged = mergeSongSuggestions(emptyList(), secondary)

        assertEquals(SUGGEST_MAX_DISPLAY, merged.size)
        assertEquals("歌名${SUGGEST_MAX_DISPLAY}", merged.last().name)
    }

    @Test
    fun `mergeSongSuggestions dedupes items sharing the same mid`() {
        val primary = listOf(SongNameSuggestion(name = "月亮惹的祸", mid = "m1"))
        val secondary =
            listOf(
                SongNameSuggestion(name = "月亮惹的祸 (R&B)", mid = "m1"),
                SongNameSuggestion(name = "痴情惹的祸", mid = "m2"),
                SongNameSuggestion(name = "缘分惹的祸", mid = ""),
            )

        val merged = mergeSongSuggestions(primary, secondary)

        assertEquals(listOf("月亮惹的祸", "痴情惹的祸", "缘分惹的祸"), merged.map { it.name })
    }

    @Test
    fun `shouldFetchNextSuggestionPage respects target raw count and max page`() {
        assertTrue(shouldFetchNextSuggestionPage(mergedCount = 10, lastPageRawCount = 30, nextPage = 2))
        assertTrue(shouldFetchNextSuggestionPage(mergedCount = 10, lastPageRawCount = 30, nextPage = SUGGEST_MAX_PAGE))
        assertEquals(false, shouldFetchNextSuggestionPage(mergedCount = SUGGEST_TARGET, lastPageRawCount = 30, nextPage = 2))
        assertEquals(false, shouldFetchNextSuggestionPage(mergedCount = 10, lastPageRawCount = 29, nextPage = 2))
        assertEquals(false, shouldFetchNextSuggestionPage(mergedCount = 10, lastPageRawCount = 30, nextPage = SUGGEST_MAX_PAGE + 1))
    }

    @Test
    fun `parsePlainSongHits keeps only containing names with server highlight`() {
        val response =
            """
            {"code":0,"data":{"keyword":"惹的祸","song":{"curnum":2,"curpage":1,"list":[
              {"songmid":"002abc","songid":1,"songname":"月亮惹的祸",
               "songname_hilight":"月亮<span class=\"c_tx_highlight\">惹的祸</span>",
               "singer":[{"name":"张宇","mid":"s1"}],"albumname":"月亮 太阳"},
              {"songmid":"002xyz","songid":2,"songname":"其他歌","songname_hilight":"","singer":[]},
              {"songmid":"003moo","songid":3,"songname":"Moon 惹的祸版","songname_hilight":"","singer":[]}
            ]}}}
            """.trimIndent()

        val hits = parsePlainSongHits(response, "惹的祸")

        assertEquals(2, hits.size)
        assertEquals("月亮惹的祸", hits[0].name)
        assertEquals("002abc", hits[0].mid)
        assertEquals("月亮<span class=\"c_tx_highlight\">惹的祸</span>", hits[0].highlight)
        assertEquals("Moon 惹的祸版", hits[1].name)
    }

    @Test
    fun `parsePlainSongHits matches keyword case-insensitively and tolerates malformed json`() {
        val response =
            """
            {"code":0,"data":{"song":{"list":[
              {"songmid":"m1","songid":4,"songname":"MoonRiver","songname_hilight":"","singer":[]}
            ]}}}
            """.trimIndent()

        assertEquals(1, parsePlainSongHits(response, "moonriver").size)
        assertEquals(emptyList<SongNameSuggestion>(), parsePlainSongHits("not-json", "惹的祸"))
        assertEquals(emptyList<SongNameSuggestion>(), parsePlainSongHits("", "惹的祸"))
    }

    @Test
    fun `parseLyricSearchResults maps old format song and dedupes by songMid`() {
        val response =
            """
            {"code":0,"data":{"keyword":"苍茫","lyric":{"curnum":2,"curpage":1,"list":[
              {"songmid":"003rJSwm3TechU","songid":9,"songname":"最炫民族风","albumname":"最炫民族风",
               "singer":[{"name":"凤凰传奇"}],"interval":212,
               "content":"最炫民族风 - 凤凰传奇&lt;br/&gt; &lt;strong class=&quot;keyword&quot;&gt;苍茫的天涯是我的爱&lt;/strong&gt;&lt;br/&gt; 绵绵的青山脚下花正开"},
              {"songmid":"003rJSwm3TechU","songid":9,"songname":"最炫民族风",
               "singer":[{"name":"凤凰传奇"}],"content":""}
            ]}}}
            """.trimIndent()

        val results = parseLyricSearchResults(response)

        assertEquals(1, results.size)
        assertEquals("最炫民族风", results[0].song.name)
        assertEquals("003rJSwm3TechU", results[0].song.songMid)
        assertEquals("凤凰传奇", results[0].song.singer)
        assertEquals("苍茫的天涯是我的爱", results[0].snippet)
    }

    @Test
    fun `parseLyricSearchResults tolerates malformed json and missing sections`() {
        assertEquals(emptyList<org.melodist.model.LyricSearchResult>(), parseLyricSearchResults("not-json"))
        assertEquals(emptyList<org.melodist.model.LyricSearchResult>(), parseLyricSearchResults("{\"code\":0,\"data\":{}}"))
    }

    @Test
    fun `unwrapJsonResponse extracts inner json from jsonp wrapper`() {
        assertEquals("""{"code":0}""", unwrapJsonResponse("""callback({"code":0})"""))
        assertEquals("""{"code":0}""", unwrapJsonResponse("""{"code":0}"""))
        assertEquals("""{"code":0}""", unwrapJsonResponse("""jsonp_cb123({"code":0})"""))
        assertEquals("garbage", unwrapJsonResponse("garbage"))
    }

    @Test
    fun `SongNameHighlightParser extracts ranges and tolerates broken tags`() {
        val (text, ranges) = SongNameHighlightParser.parse("月亮<span class=\"c_tx_highlight\">惹的祸</span>")
        assertEquals("月亮惹的祸", text)
        assertEquals(listOf(IntRange(2, 4)), ranges)

        val (plainText, plainRanges) = SongNameHighlightParser.parse("月亮惹的祸")
        assertEquals("月亮惹的祸", plainText)
        assertTrue(plainRanges.isEmpty())

        val (brokenText, brokenRanges) = SongNameHighlightParser.parse("abc<span class=\"c_tx_highlight\">def")
        assertEquals("abc<span class=\"c_tx_highlight\">def", brokenText)
        assertTrue(brokenRanges.isEmpty())

        val (multiText, multiRanges) =
            SongNameHighlightParser.parse(
                "<span class=\"c_tx_highlight\">a</span>b<span class=\"c_tx_highlight\">cd</span>",
            )
        assertEquals("abcd", multiText)
        assertEquals(listOf(IntRange(0, 0), IntRange(2, 3)), multiRanges)
    }

    @Test
    fun `LyricSnippetParser extracts keyword strong content and falls back to plain text`() {
        val content =
            "最炫民族风 - 凤凰传奇&lt;br/&gt; &lt;strong class=&quot;keyword&quot;&gt;苍茫的天涯是我的爱&lt;/strong&gt;&lt;br/&gt; 绵绵的青山脚下花正开"
        assertEquals("苍茫的天涯是我的爱", LyricSnippetParser.extract(content))

        assertEquals("plain lyric line", LyricSnippetParser.extract("plain&lt;br/&gt;lyric line"))
        assertEquals("", LyricSnippetParser.extract(""))
        assertEquals("", LyricSnippetParser.extract("   "))
    }
}
