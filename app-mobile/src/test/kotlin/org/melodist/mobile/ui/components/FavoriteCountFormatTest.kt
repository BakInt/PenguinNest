package org.melodist.mobile.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FavoriteCountFormatTest {
    @Test
    fun `null and zero counts render nothing`() {
        assertEquals("", formatFavCount(null))
        assertEquals("", formatFavCount(0))
        assertEquals("", formatFavCount(-1))
    }

    @Test
    fun `counts below one thousand render as-is`() {
        assertEquals("1", formatFavCount(1))
        assertEquals("86", formatFavCount(86))
        assertEquals("128", formatFavCount(128))
        assertEquals("999", formatFavCount(999))
    }

    @Test
    fun `counts between one thousand and ten thousand collapse to k without plus`() {
        assertEquals("1k", formatFavCount(1_000))
        assertEquals("1k", formatFavCount(1_999))
        assertEquals("9k", formatFavCount(9_999))
    }

    @Test
    fun `counts below one million render with wan unit plus suffix`() {
        assertEquals("1万+", formatFavCount(10_000))
        assertEquals("1万+", formatFavCount(10_999))
        assertEquals("1.2万+", formatFavCount(12_345))
        assertEquals("15万+", formatFavCount(150_000))
        assertEquals("99.9万+", formatFavCount(999_999))
    }

    @Test
    fun `counts at or above one million cap at nine hundred ninety nine wan plus`() {
        assertEquals("999万+", formatFavCount(1_000_000))
        assertEquals("999万+", formatFavCount(57_000_000))
        assertEquals("999万+", formatFavCount(Int.MAX_VALUE))
    }

    @Test
    fun `plus suffix appears only on wan band`() {
        listOf(1, 999, 1_000, 9_999).forEach {
            assert(!formatFavCount(it).contains("+")) { "unexpected '+' for $it" }
        }
        listOf(10_000, 12_345, 999_999, 1_000_000).forEach {
            assert(formatFavCount(it).endsWith("+")) { "missing '+' for $it" }
        }
    }
}
