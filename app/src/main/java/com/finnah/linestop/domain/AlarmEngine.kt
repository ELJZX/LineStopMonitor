package com.finnah.linestop.domain

import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.plc.PlcSnapshot

/**
 * Чистый конечный автомат аварий. Не зависит от Android и ПЛК —
 * полностью покрывается unit-тестами (см. AlarmEngineTest).
 *
 * Правила:
 *  - D0 0->1  -> создаётся новый открытый случай (время начала = now)
 *  - D0 1->0  -> случай закрывается по времени, показывается диалог причины
 *  - D4 вырос -> отправленные квитирования подтверждены ПЛК
 *  - при первом чтении, если авария уже идёт, случай восстанавливается
 */
object AlarmEngine {

    data class Result(
        val alarms: List<AlarmRecord>,
        val dialogAlarm: AlarmRecord?,
        val event: Event?
    )

    sealed interface Event {
        data class Started(val alarm: AlarmRecord) : Event
        data class Ended(val alarm: AlarmRecord) : Event
        data class AckConfirmed(val alarms: List<AlarmRecord>) : Event
    }

    fun poll(
        alarms: List<AlarmRecord>,
        prev: PlcSnapshot,
        cur: PlcSnapshot,
        firstRead: Boolean,
        now: Long,
        nextId: Long,
        shiftId: Long?
    ): Result {
        var list = alarms
        var dialog: AlarmRecord? = null
        var event: Event? = null

        if (firstRead) {
            if (cur.alarmActive && list.none { !it.closed }) {
                val alarm = newAlarm(nextId, now, shiftId, cur.startCounter)
                list = list + alarm
                event = Event.Started(alarm)
            }
            return Result(list, null, event)
        }

        val started = cur.alarmActive && !prev.alarmActive
        val ended = !cur.alarmActive && prev.alarmActive

        if (started) {
            val alarm = newAlarm(nextId, now, shiftId, cur.startCounter)
            list = list + alarm
            event = Event.Started(alarm)
        } else if (ended) {
            val open = list.lastOrNull { !it.closed }
            if (open != null) {
                val updated = open.copy(
                    startTime = now,
                    durationMs = (now - open.stopTime).coerceAtLeast(0L),
                    dialogShown = true
                )
                list = list.replace(updated)
                dialog = updated
                event = Event.Ended(updated)
            }
        }

        val ackDelta = (cur.ackCounter - prev.ackCounter) and 0xFFFF
        if (ackDelta > 0) {
            val pending = list.filter { it.ackPending }.takeLast(ackDelta)
            if (pending.isNotEmpty()) {
                val confirmed = pending.map { p ->
                    p.copy(
                        closed = true,
                        ackPending = false,
                        causeCode = p.causeCode ?: cur.lastCause.takeIf { it > 0 }
                    )
                }
                val byId = confirmed.associateBy { it.id }
                list = list.map { byId[it.id] ?: it }
                event = Event.AckConfirmed(confirmed)
            }
        }

        return Result(list, dialog, event)
    }

    /** Оператор выбрал причину — случай закрывается сразу, квитирование уходит в ПЛК. */
    fun acknowledge(
        alarms: List<AlarmRecord>,
        alarmId: Long,
        causeCode: Int,
        causePath: String?,
        causeText: String?
    ): List<AlarmRecord> {
        if (alarms.none { it.id == alarmId }) return alarms
        return alarms.map {
            if (it.id == alarmId) {
                it.copy(
                    causeCode = causeCode,
                    causePath = causePath,
                    causeText = causeText?.trim()?.takeIf { t -> t.isNotEmpty() },
                    closed = true,
                    ackPending = true,
                    dialogShown = true
                )
            } else {
                it
            }
        }
    }

    /** Диалог закрыт без выбора причины. */
    fun dismiss(alarms: List<AlarmRecord>, alarmId: Long): List<AlarmRecord> {
        if (alarms.none { it.id == alarmId }) return alarms
        return alarms.map { if (it.id == alarmId) it.copy(dialogShown = true) else it }
    }

    fun nextId(alarms: List<AlarmRecord>): Long = (alarms.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun newAlarm(id: Long, now: Long, shiftId: Long?, startCounter: Int) =
        AlarmRecord(id = id, stopTime = now, shiftId = shiftId, startCounter = startCounter)

    private fun List<AlarmRecord>.replace(alarm: AlarmRecord) =
        map { if (it.id == alarm.id) alarm else it }
}
