package com.finnah.linestop.data

/** Смена оператора линии. */
data class ShiftRecord(
    val id: Long = 0L,
    val operator: String,
    val startTime: Long,
    val endTime: Long? = null
) {
    val active: Boolean get() = endTime == null

    fun duration(now: Long = System.currentTimeMillis()): Long =
        (endTime ?: now) - startTime
}
