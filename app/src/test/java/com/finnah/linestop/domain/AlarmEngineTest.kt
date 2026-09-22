package com.finnah.linestop.domain

import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.plc.PlcSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEngineTest {

    private fun snap(
        active: Boolean,
        start: Int = 0,
        ack: Int = 0,
        cause: Int = 0
    ) = PlcSnapshot(
        connected = true,
        alarmActive = active,
        startCounter = start,
        ackCounter = ack,
        lastCause = cause
    )

    // ------------------------------------------------------------- poll

    @Test
    fun `первое чтение без аварии ничего не создаёт`() {
        val result = AlarmEngine.poll(
            alarms = emptyList(),
            prev = snap(false),
            cur = snap(false),
            firstRead = true,
            now = 1_000L,
            nextId = 1L,
            shiftId = null
        )
        assertTrue(result.alarms.isEmpty())
        assertNull(result.event)
        assertNull(result.dialogAlarm)
    }

    @Test
    fun `первое чтение при идущей аварии восстанавливает случай`() {
        val result = AlarmEngine.poll(
            alarms = emptyList(),
            prev = snap(false),
            cur = snap(true, start = 5),
            firstRead = true,
            now = 1_000L,
            nextId = 7L,
            shiftId = 42L
        )
        assertEquals(1, result.alarms.size)
        val alarm = result.alarms[0]
        assertEquals(7L, alarm.id)
        assertEquals(1_000L, alarm.stopTime)
        assertEquals(42L, alarm.shiftId)
        assertEquals(5, alarm.startCounter)
        assertFalse(alarm.closed)
        assertTrue(alarm.ongoing)
        assertTrue(result.event is AlarmEngine.Event.Started)
    }

    @Test
    fun `первое чтение не дублирует уже открытый случай`() {
        val open = AlarmRecord(id = 1L, stopTime = 500L)
        val result = AlarmEngine.poll(
            alarms = listOf(open),
            prev = snap(false),
            cur = snap(true),
            firstRead = true,
            now = 1_000L,
            nextId = 2L,
            shiftId = null
        )
        assertEquals(1, result.alarms.size)
        assertNull(result.event)
    }

    @Test
    fun `фронт сигнала создаёт открытый случай`() {
        val result = AlarmEngine.poll(
            alarms = emptyList(),
            prev = snap(false),
            cur = snap(true, start = 3),
            firstRead = false,
            now = 2_000L,
            nextId = 1L,
            shiftId = 9L
        )
        assertEquals(1, result.alarms.size)
        val alarm = result.alarms[0]
        assertEquals(1L, alarm.id)
        assertEquals(2_000L, alarm.stopTime)
        assertEquals(9L, alarm.shiftId)
        assertTrue(alarm.ongoing)
        assertNull(result.dialogAlarm)
        assertTrue(result.event is AlarmEngine.Event.Started)
    }

    @Test
    fun `спад сигнала закрывает случай по времени и показывает диалог`() {
        val open = AlarmRecord(id = 1L, stopTime = 1_000L)
        val result = AlarmEngine.poll(
            alarms = listOf(open),
            prev = snap(true),
            cur = snap(false, start = 1),
            firstRead = false,
            now = 6_000L,
            nextId = 2L,
            shiftId = null
        )
        val alarm = result.alarms[0]
        assertEquals(6_000L, alarm.startTime)
        assertEquals(5_000L, alarm.durationMs)
        assertTrue(alarm.dialogShown)
        assertEquals(alarm, result.dialogAlarm)
        assertTrue(result.event is AlarmEngine.Event.Ended)
    }

    @Test
    fun `спад без открытого случая ничего не делает`() {
        val closed = AlarmRecord(id = 1L, stopTime = 100L, startTime = 200L, closed = true)
        val result = AlarmEngine.poll(
            alarms = listOf(closed),
            prev = snap(true),
            cur = snap(false),
            firstRead = false,
            now = 6_000L,
            nextId = 2L,
            shiftId = null
        )
        assertNull(result.dialogAlarm)
        assertNull(result.event)
    }

    @Test
    fun `без изменений возвращается тот же список`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 100L))
        val result = AlarmEngine.poll(
            alarms = list,
            prev = snap(false),
            cur = snap(false),
            firstRead = false,
            now = 6_000L,
            nextId = 2L,
            shiftId = null
        )
        assertSame(list, result.alarms)
    }

    @Test
    fun `подтверждение ПЛК снимает признак ожидания`() {
        val pending = AlarmRecord(
            id = 1L, stopTime = 1_000L, startTime = 2_000L, durationMs = 1_000L,
            causeCode = 3, closed = true, ackPending = true
        )
        val result = AlarmEngine.poll(
            alarms = listOf(pending),
            prev = snap(false, ack = 0),
            cur = snap(false, ack = 1, cause = 3),
            firstRead = false,
            now = 9_000L,
            nextId = 2L,
            shiftId = null
        )
        val alarm = result.alarms[0]
        assertFalse(alarm.ackPending)
        assertTrue(alarm.closed)
        assertEquals(3, alarm.causeCode)
        assertTrue(result.event is AlarmEngine.Event.AckConfirmed)
    }

    @Test
    fun `подтверждение ПЛК без ожидающих квитирований игнорируется`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L, closed = true))
        val result = AlarmEngine.poll(
            alarms = list,
            prev = snap(false, ack = 0),
            cur = snap(false, ack = 1),
            firstRead = false,
            now = 9_000L,
            nextId = 2L,
            shiftId = null
        )
        assertSame(list, result.alarms)
        assertNull(result.event)
    }

    @Test
    fun `причина из ПЛК подставляется, если её нет у случая`() {
        val pending = AlarmRecord(id = 1L, stopTime = 1_000L, ackPending = true)
        val result = AlarmEngine.poll(
            alarms = listOf(pending),
            prev = snap(false, ack = 0),
            cur = snap(false, ack = 1, cause = 7),
            firstRead = false,
            now = 9_000L,
            nextId = 2L,
            shiftId = null
        )
        assertEquals(7, result.alarms[0].causeCode)
    }

    // ------------------------------------------------------- acknowledge

    @Test
    fun `выбор причины закрывает случай сразу`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        val path = "Технологическое оборудование / 1. Нет продукта / 1.1 Мойка"
        val after = AlarmEngine.acknowledge(list, 1L, 11, path, null)
        val alarm = after[0]
        assertTrue(alarm.closed)
        assertTrue(alarm.ackPending)
        assertEquals(11, alarm.causeCode)
        assertEquals(path, alarm.causePath)
        assertTrue(alarm.dialogShown)
    }

    @Test
    fun `текст другой причины сохраняется без лишних пробелов`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        val after = AlarmEngine.acknowledge(list, 1L, 19, "… / Другая причина", "  Порвался ремень  ")
        assertEquals("Порвался ремень", after[0].causeText)
    }

    @Test
    fun `пустой текст причины сохраняется как null`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        val after = AlarmEngine.acknowledge(list, 1L, 11, "path", "   ")
        assertNull(after[0].causeText)
    }

    @Test
    fun `выбор причины для неизвестного id ничего не меняет`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        assertSame(list, AlarmEngine.acknowledge(list, 99L, 11, null, null))
    }

    @Test
    fun `закрытие диалога помечает случай`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        val after = AlarmEngine.dismiss(list, 1L)
        assertTrue(after[0].dialogShown)
        assertFalse(after[0].closed)
    }

    @Test
    fun `закрытие диалога для неизвестного id ничего не меняет`() {
        val list = listOf(AlarmRecord(id = 1L, stopTime = 1_000L))
        assertSame(list, AlarmEngine.dismiss(list, 99L))
    }

    @Test
    fun `следующий id на единицу больше максимального`() {
        assertEquals(1L, AlarmEngine.nextId(emptyList()))
        assertEquals(4L, AlarmEngine.nextId(listOf(
            AlarmRecord(id = 1L, stopTime = 0L),
            AlarmRecord(id = 3L, stopTime = 0L)
        )))
    }
}
