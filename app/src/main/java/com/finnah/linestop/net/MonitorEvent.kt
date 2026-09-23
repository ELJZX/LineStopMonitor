package com.finnah.linestop.net

/**
 * Событие, отправляемое на сервер мониторинга.
 *
 * @param time      время события на планшете (мс)
 * @param type      тип события (ALARM_START, ALARM_END, CAUSE_SELECTED, ...)
 * @param level     уровень (INFO / WARN / ERROR)
 * @param category  категория (APP / PLC / ALARM / ACK / SHIFT)
 */
data class MonitorEvent(
    val time: Long,
    val type: String,
    val level: String,
    val category: String,
    val message: String,
    val deviceId: String,
    val alarmId: Long? = null,
    val shiftId: Long? = null,
    val stopTime: Long? = null,
    val startTime: Long? = null,
    val causeCode: Int? = null,
    val causeText: String? = null,
    val causePath: String? = null,
    val durationMs: Long? = null,
    val operator: String? = null,
    val mechanic: String? = null
)

/** Ручной JSON-кодировщик (без внешних зависимостей, полностью тестируемый). */
object EventJson {

    fun encode(event: MonitorEvent): String {
        val sb = StringBuilder()
        appendObject(sb, event)
        return sb.toString()
    }

    fun encodeBatch(events: List<MonitorEvent>): String {
        val sb = StringBuilder("[")
        events.forEachIndexed { index, event ->
            if (index > 0) sb.append(',')
            appendObject(sb, event)
        }
        return sb.append(']').toString()
    }

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }

    private fun appendObject(sb: StringBuilder, e: MonitorEvent) {
        sb.append('{')
        var first = true
        fun field(name: String, rawValue: String) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(name).append("\":").append(rawValue)
        }

        field("time", e.time.toString())
        field("type", quote(e.type))
        field("level", quote(e.level))
        field("category", quote(e.category))
        field("message", quote(e.message))
        field("deviceId", quote(e.deviceId))
        e.alarmId?.let { field("alarmId", it.toString()) }
        e.shiftId?.let { field("shiftId", it.toString()) }
        e.stopTime?.let { field("stopTime", it.toString()) }
        e.startTime?.let { field("startTime", it.toString()) }
        e.causeCode?.let { field("causeCode", it.toString()) }
        e.causeText?.let { field("causeText", quote(it)) }
        e.causePath?.let { field("causePath", quote(it)) }
        e.durationMs?.let { field("durationMs", it.toString()) }
        e.operator?.let { field("operator", quote(it)) }
        e.mechanic?.let { field("mechanic", quote(it)) }
        sb.append('}')
    }
}
