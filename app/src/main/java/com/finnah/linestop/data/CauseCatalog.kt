package com.finnah.linestop.data

/**
 * Справочник причин остановки: **категория → пункт → причина**.
 *
 * Код причины = номер пункта * 10 + номер причины (1..3).
 * «Другая причина» — код ...9 (оператор вводит текст вручную).
 * Код пишется в ПЛК (D100), поэтому он помещается в 16 бит (макс. 129).
 */
object CauseCatalog {

    data class Reason(val code: Int, val title: String, val other: Boolean = false)

    data class Item(val index: Int, val title: String, val reasons: List<Reason>)

    data class Category(val title: String, val items: List<Item>)

    private fun item(index: Int, title: String, vararg reasons: String): Item =
        Item(
            index = index,
            title = title,
            reasons = reasons.mapIndexed { i, text -> Reason(index * 10 + (i + 1), text) } +
                    Reason(index * 10 + 9, "Другая причина", other = true)
        )

    val categories: List<Category> = listOf(
        Category(
            "Технологическое оборудование",
            listOf(
                item(
                    1, "1. Нет продукта (ожидание творога)",
                    "1.1 Мойка", "1.2 Ожидание творога", "1.3 Промежуточная мойка"
                ),
                item(
                    2, "2. Смена вида ФЯН (мойка между видами)",
                    "2.1 Мойка между видами ФЯН", "2.2 Проталкивание",
                    "2.3 Мойка под «шоколадную крошку»"
                ),
                item(
                    3, "3. Ошибки на клапанах",
                    "3.1 Ошибка во время полной мойки", "3.2 Ошибка во время стерилизации",
                    "3.3 Ошибки во время дезинфекции"
                )
            )
        ),
        Category(
            "Автомат фасовки",
            listOf(
                item(
                    4, "1. Формовка + протяжка",
                    "1.1 Заклинил главный привод", "1.2 Плохое формование ваночки",
                    "1.3 Проблемы с ванной перекиси"
                ),
                item(
                    5, "2. Дозировка + фольга",
                    "2.1 Неправильная дозировка (вес)", "2.2 Ошибка сервопривода баков",
                    "2.3 Некорректное положение фольги"
                ),
                item(
                    6, "3. Укупорка + выборка",
                    "3.1 Забивается горка", "3.2 Неправильная вырубка",
                    "3.3 Ошибка положения вырубки"
                )
            )
        ),
        Category(
            "Конечное оборудование",
            listOf(
                item(
                    7, "1. Шнек + принтер + стрелка",
                    "1.1 Ошибка принтера", "1.2 Забился шнек", "1.3 Забилась стрелка"
                ),
                item(
                    8, "2. Укладчик + этикетка",
                    "2.1 Нет вакуума на присосках", "2.2 Порвалась этикетка",
                    "2.3 Поломка этикеревщика"
                ),
                item(
                    9, "3. Формовка гофролотка",
                    "3.1 Поломка формователя гофролотков", "3.2 Ошибка клеевой системы",
                    "3.3 Проблемы с конвейером лотков"
                )
            )
        ),
        Category(
            "Честный знак",
            listOf(
                item(
                    10, "1. Проблемы с терминалом цифровой маркировки",
                    "1.1 Нет соединения с сервером цифровой маркировки (локальная сеть)",
                    "1.2 Проблемы с приложением «Молвест.Маркировка»",
                    "1.3 Частая отбраковка продукции"
                ),
                item(
                    11, "2. Камера Datalogic",
                    "2.1 Камера не читает коды, счётчик на терминале не увеличивается",
                    "2.2 Нет питания на камере"
                ),
                item(
                    12, "3. Аппликатор",
                    "3.1 Стикер ложится криво на ваночку",
                    "3.2 Датчик подачи этикетки не реагирует на прохождение продукта"
                )
            )
        )
    )

    /** Все причины одним списком. */
    val allReasons: List<Reason> = categories.flatMap { c -> c.items.flatMap { it.reasons } }

    data class Path(val category: Category, val item: Item, val reason: Reason)

    fun find(code: Int?): Path? {
        if (code == null) return null
        categories.forEach { category ->
            category.items.forEach { item ->
                item.reasons.forEach { reason ->
                    if (reason.code == code) return Path(category, item, reason)
                }
            }
        }
        return null
    }

    /** Короткое название причины. */
    fun reasonTitle(code: Int?): String? = find(code)?.reason?.title

    /** Полный путь «Категория / Пункт / Причина» для истории и журнала. */
    fun path(code: Int?): String? = find(code)?.let {
        "${it.category.title} / ${it.item.title} / ${it.reason.title}"
    }

    fun isOther(code: Int?): Boolean = find(code)?.reason?.other == true
}
