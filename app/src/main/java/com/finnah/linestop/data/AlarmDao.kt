package com.finnah.linestop.data

import android.content.ContentValues
import android.database.Cursor

/** Доступ к таблице аварий. */
class AlarmDao(private val db: AppDatabase) {

    fun insert(alarm: AlarmRecord): Long =
        db.writableDatabase.insert("alarms", null, toValues(alarm))

    fun update(alarm: AlarmRecord) {
        db.writableDatabase.update("alarms", toValues(alarm), "id = ?", arrayOf(alarm.id.toString()))
    }

    fun all(): List<AlarmRecord> {
        val result = mutableListOf<AlarmRecord>()
        db.readableDatabase.rawQuery("SELECT * FROM alarms ORDER BY stop_time", null).use { c ->
            while (c.moveToNext()) result += fromCursor(c)
        }
        return result
    }

    fun clear() {
        db.writableDatabase.delete("alarms", null, null)
    }

    private fun toValues(a: AlarmRecord) = ContentValues().apply {
        // id задаёт AlarmEngine (maxId + 1), поэтому вставляем его явно,
        // чтобы id в памяти и в базе совпадали
        if (a.id > 0) put("id", a.id)
        put("shift_id", a.shiftId)
        put("stop_time", a.stopTime)
        put("start_time", a.startTime)
        put("duration_ms", a.durationMs)
        put("cause_code", a.causeCode)
        put("cause_text", a.causeText)
        put("cause_path", a.causePath)
        put("closed", if (a.closed) 1 else 0)
        put("dialog_shown", if (a.dialogShown) 1 else 0)
        put("ack_pending", if (a.ackPending) 1 else 0)
        put("start_counter", a.startCounter)
    }

    private fun fromCursor(c: Cursor) = AlarmRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        shiftId = c.getLongOrNull("shift_id"),
        stopTime = c.getLong(c.getColumnIndexOrThrow("stop_time")),
        startTime = c.getLongOrNull("start_time"),
        durationMs = c.getLongOrNull("duration_ms"),
        causeCode = c.getIntOrNull("cause_code"),
        causeText = c.getStringOrNull("cause_text"),
        causePath = c.getStringOrNull("cause_path"),
        closed = c.getInt(c.getColumnIndexOrThrow("closed")) != 0,
        dialogShown = c.getInt(c.getColumnIndexOrThrow("dialog_shown")) != 0,
        ackPending = c.getInt(c.getColumnIndexOrThrow("ack_pending")) != 0,
        startCounter = c.getInt(c.getColumnIndexOrThrow("start_counter"))
    )
}
