package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChorusDetectorTest {

    private fun line(
        tsSec: Long,
        text: String,
    ): LyricLine = LyricLine(tsSec * 1000L, text)

    private fun startsOf(sections: List<ChorusDetector.ChorusSection>): List<Long> = sections.map { it.startMs }

    /** 副歌块：4 行，行距 7s（跨度 21s，+8s 尾部估计 = 29s，满足 >=3 行且 >=12s） */
    private fun chorusLines(startSec: Long): List<LyricLine> =
        listOf(
            line(startSec, "夜空中最亮的星 能否听清"),
            line(startSec + 7, "那仰望的人 心底的孤独和叹息"),
            line(startSec + 14, "oh 夜空中最亮的星 是否知道"),
            line(startSec + 21, "曾与我同行的身影 如今在哪里"),
        )

    @Test
    fun `detect finds all chorus occurrences in standard form and skips verse-chorus concatenation artifact`() {
        val verse = listOf(line(0, "主歌第一句"), line(7, "主歌第二句"), line(14, "主歌第三句"), line(21, "主歌第四句"))
        val verse2 = listOf(line(60, "第二遍主歌一"), line(67, "第二遍主歌二"), line(74, "第二遍主歌三"), line(81, "第二遍主歌四"))
        val bridge = listOf(line(120, "桥段一句"), line(126, "桥段两句"))

        val sections = ChorusDetector.detect(verse + chorusLines(30) + verse2 + chorusLines(90) + bridge + chorusLines(135), 180_000L)

        // 主歌与副歌拼接（VC VC）的出现首尾相接被间隔规则剔除，只保留真正的副歌 3 次出现
        assertEquals(listOf(30_000L, 90_000L, 135_000L), startsOf(sections))
        // endMs = 块末行时间戳 + 8s 尾部估计
        assertEquals(135_000L + 21_000L + 8_000L, sections.last().endMs)
    }

    @Test
    fun `detect returns empty when no repeated block exists`() {
        val lines = (0 until 12).map { line(it * 10L, "完全不同的歌词第${it}句") }
        assertTrue(ChorusDetector.detect(lines, 120_000L).isEmpty())
    }

    @Test
    fun `detect ignores bracketed markers punctuation and letter case`() {
        val occurrence1 =
            listOf(
                line(0, "Love story"),
                line(6, "(♪)"),
                line(12, "We were young"),
                line(18, "Time can't take it away"),
            )
        val filler = listOf(line(30, "间奏后的过渡一"), line(36, "间奏后的过渡二"), line(42, "间奏后的过渡三"))
        val occurrence2 =
            listOf(
                line(60, "LOVE STORY!"),
                line(66, "【和声】"),
                line(72, "We were, YOUNG."),
                line(78, "time cant take it away～"),
            )

        val sections = ChorusDetector.detect(occurrence1 + filler + occurrence2, 120_000L)

        assertEquals(listOf(0L, 60_000L), startsOf(sections))
    }

    @Test
    fun `detect filters blocks shorter than three lines`() {
        val hook = listOf(line(0, "重复的短句一"), line(5, "重复的短句二"))
        val filler = (0 until 8).map { line(20L + it * 10, "填充歌词第${it}句") }

        assertTrue(ChorusDetector.detect(hook + filler + hook.map { line(it.timestampMs / 1000 + 110, it.text) }, 300_000L).isEmpty())
    }

    @Test
    fun `detect filters blocks shorter than twelve seconds`() {
        val fast = listOf(line(0, "快节奏第一句"), line(1, "快节奏第二句"), line(2, "快节奏第三句"))
        val filler = (0 until 8).map { line(20L + it * 10, "填充歌词第${it}句") }

        assertTrue(ChorusDetector.detect(fast + filler + fast.map { line(it.timestampMs / 1000 + 110, it.text) }, 300_000L).isEmpty())
    }

    @Test
    fun `detect prefers longer chorus block over shorter repeated verse fragment at equal count`() {
        val verse = listOf(line(0, "主歌第一句"), line(7, "主歌第二句"), line(14, "主歌第三句"), line(21, "主歌第四句"))
        val chorus = listOf(line(40, "副歌第一句"), line(47, "副歌第二句"), line(54, "副歌第三句"), line(61, "副歌第四句"))
        // 第二遍主歌前 3 句与前奏段相同、末句改写（真实歌曲常见）
        val verse2 = listOf(line(80, "主歌第一句"), line(87, "主歌第二句"), line(94, "主歌第三句"), line(101, "第二遍主歌结尾"))
        val chorus2 = chorus.map { line(it.timestampMs / 1000 + 80, it.text) }

        val sections = ChorusDetector.detect(verse + chorus + verse2 + chorus2, 240_000L)

        // 出现次数打平（2 次）时块更长的副歌（29s）胜出前 3 句重复的主歌片段（22s）
        assertEquals(listOf(40_000L, 120_000L), startsOf(sections))
    }

    @Test
    fun `detect caps sections at five occurrences`() {
        val chorus = chorusLines(0)
        val repeated =
            (1..7).flatMap { index ->
                listOf(line(60L + (index - 1) * 40, "过渡句${index}一"), line(66L + (index - 1) * 40, "过渡句${index}二")) + chorusLines(80L + (index - 1) * 40)
            }

        val sections = ChorusDetector.detect(chorus + repeated, 400_000L)

        assertEquals(5, sections.size)
        assertTrue(startsOf(sections).sorted() == startsOf(sections))
    }

    @Test
    fun `detect handles shuffled input and returns sections sorted by start`() {
        val verse = listOf(line(0, "主歌第一句"), line(7, "主歌第二句"), line(14, "主歌第三句"), line(21, "主歌第四句"))
        val verse2 = listOf(line(60, "第二遍主歌一"), line(67, "第二遍主歌二"), line(74, "第二遍主歌三"), line(81, "第二遍主歌四"))
        val shuffled = (verse + chorusLines(30) + verse2 + chorusLines(90)).shuffled()

        val sections = ChorusDetector.detect(shuffled, 180_000L)

        assertEquals(listOf(30_000L, 90_000L), startsOf(sections))
    }

    @Test
    fun `detect returns empty for fewer than six meaningful lines`() {
        val lines = listOf(line(0, "唯一第一句"), line(5, "唯一第二句"), line(10, "唯一第三句"), line(15, "唯一第四句"), line(20, "唯一第五句"))

        assertTrue(ChorusDetector.detect(lines, 60_000L).isEmpty())
    }

    @Test
    fun `detect clamps end to song duration`() {
        val verse = listOf(line(70, "间奏后主歌一"), line(77, "间奏后主歌二"), line(84, "间奏后主歌三"), line(91, "间奏后主歌四"))

        // 曲长 135s：第二遍副歌末行 131s + 8s 尾部 = 139s，超出曲长需收敛
        val sections = ChorusDetector.detect(chorusLines(40) + verse + chorusLines(110), 135_000L)

        assertEquals(listOf(40_000L, 110_000L), startsOf(sections))
        assertEquals(135_000L, sections.last().endMs)
    }
}
