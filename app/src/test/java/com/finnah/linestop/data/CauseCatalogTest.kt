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
    fun `в категориях по три-четыре пункта`() {
        assertEquals(
            listOf(4, 3, 3, 3),
            CauseCatalog.categories.map { it.items.size }
        )
        assertEquals(13, CauseCatalog.categories.sumOf { it.items.size })
    }

    @Test
    fun `добавленные причины и пункт присутствуют`() {
        val uku = CauseCatalog.categories
            .first { it.title == "Автомат фасовки" }
            .items.first { it.title == "Укупорка + выборка" }
        assertTrue(uku.reasons.any { it.title == "Затор на конвеере" })
        assertTrue(uku.reasons.any { it.title == "Емкость охл. воды пустая" })

        val poly = CauseCatalog.categories
            .first { it.title == "Технологическое оборудование" }
            .items.first { it.title == "Смена Полистирола" }
        assertTrue(poly.reasons.any { it.title == "Смена фольги" })
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
    fun `у каждого пункта есть хотя бы одна причина`() {
        CauseCatalog.categories.forEach { category ->
            category.items.forEach { item ->
                assertTrue(
                    "Мало причин в ${item.title}",
                    item.reasons.count { !it.other } >= 1
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
