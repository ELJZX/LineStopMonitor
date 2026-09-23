package com.finnah.linestop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CauseCatalogTest {

    @Test
    fun `четыре категории`() {
        assertEquals(4, CauseCatalog.categories.size)
        assertEquals(
            listOf(
                "Технологическое оборудование",
                "Автомат фасовки",
                "Конечное оборудование",
                "Честный знак"
            ),
            CauseCatalog.categories.map { it.title }
        )
    }

    @Test
    fun `в каждой категории по три пункта`() {
        assertTrue(CauseCatalog.categories.all { it.items.size == 3 })
        assertEquals(12, CauseCatalog.categories.sumOf { it.items.size })
    }

    @Test
    fun `у каждого пункта есть Другая причина`() {
        CauseCatalog.categories.forEach { category ->
            category.items.forEach { item ->
                val other = item.reasons.lastOrNull { it.other }
                assertNotNull("Нет «Другой причины» в ${item.title}", other)
                assertEquals("Другая причина", other!!.title)
                assertEquals(item.index * 10 + 9, other.code)
            }
        }
    }

    @Test
    fun `у каждого пункта минимум две конкретные причины`() {
        CauseCatalog.categories.forEach { category ->
            category.items.forEach { item ->
                assertTrue(
                    "Мало причин в ${item.title}",
                    item.reasons.count { !it.other } >= 2
                )
            }
        }
    }

    @Test
    fun `коды уникальны и помещаются в 16 бит`() {
        val codes = CauseCatalog.allReasons.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in 1..32767 })
    }

    @Test
    fun `поиск причины по коду`() {
        val path = CauseCatalog.find(11)
        assertNotNull(path)
        assertEquals("Технологическое оборудование", path!!.category.title)
        assertEquals("Нет продукта (ожидание творога)", path.item.title)
        assertEquals("Мойка", path.reason.title)
    }

    @Test
    fun `полный путь причины`() {
        assertEquals(
            "Автомат фасовки / Формовка + протяжка / Заклинил главный привод",
            CauseCatalog.path(41)
        )
    }

    @Test
    fun `определение другой причины`() {
        assertTrue(CauseCatalog.isOther(19))
        assertTrue(CauseCatalog.isOther(129))
        assertFalse(CauseCatalog.isOther(11))
        assertFalse(CauseCatalog.isOther(null))
    }

    @Test
    fun `неизвестный код`() {
        assertNull(CauseCatalog.find(9999))
        assertNull(CauseCatalog.path(9999))
        assertNull(CauseCatalog.reasonTitle(9999))
    }

    @Test
    fun `названия причин не пустые`() {
        assertTrue(CauseCatalog.allReasons.all { it.title.isNotBlank() })
        assertTrue(CauseCatalog.categories.all { c -> c.items.all { it.title.isNotBlank() } })
    }
}
