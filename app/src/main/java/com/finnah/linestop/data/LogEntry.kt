package com.finnah.linestop.data

/** Запись подробного журнала событий. */
data class LogEntry(
    val id: Long = 0L,
    val time: Long,
    val level: String,
    val category: String,
    val message: String,
    val alarmId: Long? = null,
    val shiftId: Long? = null
) {
    companion object {
        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"

        const val CAT_APP = "APP"
        const val CAT_PLC = "PLC"
        const val CAT_ALARM = "ALARM"
        const val CAT_ACK = "ACK"
        const val CAT_SHIFT = "SHIFT"
    }
}
