package com.finnah.linestop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRecordTest {

    @Test
    fun `авария идёт, пока нет времени конца`() {
        assertTrue(AlarmRecord(id = 1, stopTime = 0L).ongoing)
        assertFalse(AlarmRecord(id = 1, stopTime = 0L, startTime = 10L).ongoing)
    }

    @Test
    fun `длительность берётся из сохранённого значения`() {
        val alarm = AlarmRecord(id = 1, stopTime = 0L, startTime = 999L, durationMs = 1234L)
        assertEquals(1234L, alarm.duration(now = 100_000L))
    }

    @Test
    fun `длительность считается по меткам времени`() {
        val alarm = AlarmRecord(id = 1, stopTime = 1_000L, startTime = 6_000L)
        assertEquals(5_000L, alarm.duration(now = 100_000L))
    }

    @Test
    fun `для идущей аварии длительность считается от текущего времени`() {
        val alarm = AlarmRecord(id = 1, stopTime = 1_000L)
        assertEquals(4_000L, alarm.duration(now = 5_000L))
    }

    @Test
    fun `отрицательная длительность не возникает`() {
        val alarm = AlarmRecord(id = 1, stopTime = 10_000L)
        assertEquals(0L, alarm.duration(now = 5_000L))
    }

    @Test
    fun `название причины из справочника`() {
        val alarm = AlarmRecord(id = 1, stopTime = 0L, causeCode = 11)
        assertEquals("Мойка", alarm.causeLabel)
    }

    @Test
    fun `полный путь причины имеет приоритет`() {
        val alarm = AlarmRecord(
            id = 1, stopTime = 0L, causeCode = 11,
            causePath = "Технологическое оборудование / 1. Нет продукта / 1.1 Мойка"
        )
        assertEquals(
            "Технологическое оборудование / 1. Нет продукта / 1.1 Мойка",
            alarm.causeLabel
        )
    }

    @Test
    fun `другая причина с текстом`() {
        val alarm = AlarmRecord(id = 1, stopTime = 0L, causeCode = 19, causeText = "Порвался ремень")
        assertEquals("Порвался ремень", alarm.causeLabel)
    }

    @Test
    fun `другая причина с полным путём показывает путь`() {
        val path = "Автомат фасовки / Дозировка + фольга / Другая причина:Порвался ремень"
        val alarm = AlarmRecord(
            id = 1, stopTime = 0L, causeCode = 19,
            causeText = "Порвался ремень", causePath = path
        )
        assertEquals(path, alarm.causeLabel)
    }

    @Test
    fun `другая причина без текста`() {
        val alarm = AlarmRecord(id = 1, stopTime = 0L, causeCode = 19)
        assertEquals("Другая причина", alarm.causeLabel)
    }

    @Test
    fun `без причины название пустое`() {
        assertNull(AlarmRecord(id = 1, stopTime = 0L).causeLabel)
    }
}
