package com.finnah.linestop.data

/**
 * Один зафиксированный случай аварии линии.
 *
 * @param shiftId      смена, во время которой произошла авария (может быть null)
 * @param stopTime     время появления сигнала аварии (мс, часы планшета)
 * @param startTime    время исчезновения сигнала = запуск линии (null, если авария идёт)
 * @param durationMs   длительность аварии в мс
 * @param causeCode    выбранный код причины (null, пока случай не закрыт)
 * @param causeText    свободный текст для "Другая причина"
 * @param closed       true, когда случай закрыт выбранной причиной
 * @param dialogShown  диалог выбора причины уже показывался
 * @param ackPending   причина выбрана, но квитирование ПЛК ещё не подтверждено
 * @param startCounter значение D1 контроллера для этого события (диагностика)
 */
data class AlarmRecord(
    val id: Long = 0L,
    val shiftId: Long? = null,
    val stopTime: Long,
    val startTime: Long? = null,
    val durationMs: Long? = null,
    val causeCode: Int? = null,
    val causeText: String? = null,
    val closed: Boolean = false,
    val dialogShown: Boolean = false,
    val ackPending: Boolean = false,
    val startCounter: Int = 0
) {
    /** Авария ещё идёт (сигнал не пропал). */
    val ongoing: Boolean get() = startTime == null

    /** Длительность для отображения: сохранённая или посчитанная по меткам времени. */
    fun duration(now: Long = System.currentTimeMillis()): Long? = when {
        durationMs != null -> durationMs
        startTime != null -> startTime - stopTime
        else -> (now - stopTime).coerceAtLeast(0L)
    }

    val causeLabel: String?
        get() = causeCode?.let { code ->
            if (code == StopCauses.OTHER_CODE) {
                causeText?.takeIf { it.isNotBlank() } ?: "Другая причина"
            } else {
                StopCauses.titleFor(code)
            }
        }
}
