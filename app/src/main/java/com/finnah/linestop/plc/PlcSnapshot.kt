package com.finnah.linestop.plc

/** Снимок состояния контроллера, полученный за один цикл опроса. */
data class PlcSnapshot(
    val connected: Boolean = false,
    val alarmActive: Boolean = false,
    val startCounter: Int = 0,
    val ackCounter: Int = 0,
    val lastCause: Int = 0,
    val lastError: String? = null,
    val lastUpdate: Long = 0L
)
