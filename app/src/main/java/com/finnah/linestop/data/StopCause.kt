package com.finnah.linestop.data

/** Причина остановки, которую выбирает оператор. */
data class StopCause(val code: Int, val title: String)

object StopCauses {

    const val OTHER_CODE = 99

    /**
     * TODO: заменить тексты на реальный список из 10 проблем линии.
     * Сейчас оставлены заглушки 1..10, как просил заказчик.
     */
    val list: List<StopCause> = listOf(
        StopCause(1, "1. Причина остановки №1"),
        StopCause(2, "2. Причина остановки №2"),
        StopCause(3, "3. Причина остановки №3"),
        StopCause(4, "4. Причина остановки №4"),
        StopCause(5, "5. Причина остановки №5"),
        StopCause(6, "6. Причина остановки №6"),
        StopCause(7, "7. Причина остановки №7"),
        StopCause(8, "8. Причина остановки №8"),
        StopCause(9, "9. Причина остановки №9"),
        StopCause(10, "10. Причина остановки №10")
    )

    fun titleFor(code: Int): String =
        list.firstOrNull { it.code == code }?.title ?: if (code == OTHER_CODE) "Другая причина" else "Код $code"
}
