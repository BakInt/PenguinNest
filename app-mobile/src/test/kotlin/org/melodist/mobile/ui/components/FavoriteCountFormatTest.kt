package org.melodist.mobile.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FavoriteCountFormatTest {
    @Test
    fun `null count renders empty placeholder`() {
        assertEquals("", formatFavCount(null))
    }

    @Test
    fun `zero count renders zero`() {
        assertEquals("0", formatFavCount(0))
    }

    @Test
    fun `counts up to 999 render as-is`() {
        assertEquals("1", formatFavCount(1))
        assertEquals("999", formatFavCount(999))
    }

    @Test
    fun `counts above 999 collapse to 999 plus`() {
        assertEquals("999+", formatFavCount(1000))
        assertEquals("999+", formatFavCount(1234567))
    }

    @Test
    fun `negative count is treated as unknown`() {
        assertEquals("", formatFavCount(-1))
    }
}
