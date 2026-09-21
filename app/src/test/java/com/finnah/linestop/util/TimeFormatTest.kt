package com.finnah.linestop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `null и отрицательное время дают прочерк`() {
        assertEquals("—", formatDuration(null))
        assertEquals("—", formatDuration(-1L))
        assertEquals("—", formatTime(null))
        assertEquals("—", formatShortTime(null))
    }

    @Test
    fun `секунды`() {
        assertEquals("5 с", formatDuration(5_000L))
        assertEquals("59 с", formatDuration(59_999L))
    }

    @Test
    fun `минуты и секунды`() {
        assertEquals("1 мин 05 с", formatDuration(65_000L))
        assertEquals("10 мин 00 с", formatDuration(600_000L))
    }

    @Test
    fun `часы, минуты и секунды`() {
        assertEquals("1 ч 00 мин 00 с", formatDuration(3_600_000L))
        assertEquals("2 ч 03 мин 04 с", formatDuration(7_384_000L))
    }

    @Test
    fun `формат полной даты`() {
        assertTrue(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""").matches(formatTime(1_700_000_000_000L)))
    }

    @Test
    fun `формат короткого времени`() {
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").matches(formatShortTime(1_700_000_000_000L)))
    }
}
