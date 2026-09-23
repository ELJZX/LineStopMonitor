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
                    1, "Нет продукта (ожидание творога)",
                    "Мойка", "Ожидание творога", "Промежуточная мойка"
                ),
                item(
                    2, "Смена вида ФЯН (мойка между видами)",
                    "Мойка между видами ФЯН", "Проталкивание",
                    "Мойка под «шоколадную крошку»"
                ),
                item(
                    3, "Ошибки на клапанах",
                    "Ошибка во время полной мойки", "Ошибка во время стерилизации",
                    "Ошибки во время дезинфекции"
                )
            )
        ),
        Category(
            "Автомат фасовки",
            listOf(
                item(
                    4, "Формовка + протяжка",
                    "Заклинил главный привод", "Плохое формование ваночки",
                    "Проблемы с ванной перекиси"
                ),
                item(
                    5, "Дозировка + фольга",
                    "Неправильная дозировка (вес)", "Ошибка сервопривода баков",
                    "Некорректное положение фольги"
                ),
                item(
                    6, "Укупорка + выборка",
                    "Забивается горка", "Неправильная вырубка",
                    "Ошибка положения вырубки"
                )
            )
        ),
        Category(
            "Конечное оборудование",
            listOf(
                item(
                    7, "Шнек + принтер + стрелка",
                    "Ошибка принтера", "Забился шнек", "Забилась стрелка"
                ),
                item(
                    8, "Укладчик + этикетка",
                    "Нет вакуума на присосках", "Порвалась этикетка",
                    "Поломка этикеревщика"
                ),
                item(
                    9, "Формовка гофролотка",
                    "Поломка формователя гофролотков", "Ошибка клеевой системы",
                    "Проблемы с конвейером лотков"
                )
            )
        ),
        Category(
            "Честный знак",
            listOf(
                item(
                    10, "Проблемы с терминалом цифровой маркировки",
                    "Нет соединения с сервером цифровой маркировки (локальная сеть)",
                    "Проблемы с приложением «Молвест.Маркировка»",
                    "Частая отбраковка продукции"
                ),
                item(
                    11, "Камера Datalogic",
                    "Камера не читает коды, счётчик на терминале не увеличивается",
                    "Нет питания на камере"
                ),
                item(
                    12, "Аппликатор",
                    "Стикер ложится криво на ваночку",
                    "Датчик подачи этикетки не реагирует на прохождение продукта"
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
