package com.finnah.linestop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftRecordTest {

    @Test
    fun `смена активна, пока нет времени окончания`() {
        assertTrue(ShiftRecord(id = 1, operator = "Иванов", startTime = 0L).active)
        assertFalse(ShiftRecord(id = 1, operator = "Иванов", startTime = 0L, endTime = 10L).active)
    }

    @Test
    fun `длительность завершённой смены`() {
        val shift = ShiftRecord(id = 1, operator = "Иванов", startTime = 1_000L, endTime = 4_000L)
        assertEquals(3_000L, shift.duration(now = 100_000L))
    }

    @Test
    fun `длительность активной смены считается от текущего времени`() {
        val shift = ShiftRecord(id = 1, operator = "Иванов", startTime = 1_000L)
        assertEquals(4_000L, shift.duration(now = 5_000L))
    }
}
