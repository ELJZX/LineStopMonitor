package com.finnah.linestop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicCatalogTest {

    @Test
    fun `список механиков задан`() {
        assertEquals(
            listOf(
                "Ильченко А.А.",
                "Бирюков П.Н.",
                "Полуказаков Р.Н.",
                "Мусеев Ю.В."
            ),
            MechanicCatalog.mechanics
        )
    }

    @Test
    fun `механики не пустые и уникальные`() {
        assertTrue(MechanicCatalog.mechanics.all { it.isNotBlank() })
        assertEquals(
            MechanicCatalog.mechanics.size,
            MechanicCatalog.mechanics.toSet().size
        )
    }
}
