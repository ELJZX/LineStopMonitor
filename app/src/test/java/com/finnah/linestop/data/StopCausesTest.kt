package com.finnah.linestop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCausesTest {

    @Test
    fun `список содержит десять причин`() {
        assertEquals(10, StopCauses.list.size)
    }

    @Test
    fun `коды идут от 1 до 10 без пропусков`() {
        assertEquals((1..10).toList(), StopCauses.list.map { it.code })
    }

    @Test
    fun `у каждой причины есть название`() {
        assertTrue(StopCauses.list.all { it.title.isNotBlank() })
    }

    @Test
    fun `название по коду`() {
        assertEquals("5. Причина остановки №5", StopCauses.titleFor(5))
    }

    @Test
    fun `другая причина по коду 99`() {
        assertEquals("Другая причина", StopCauses.titleFor(StopCauses.OTHER_CODE))
    }

    @Test
    fun `неизвестный код отображается как есть`() {
        assertEquals("Код 55", StopCauses.titleFor(55))
    }
}
